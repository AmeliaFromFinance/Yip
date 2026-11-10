package dev.yipchat.feature.watch

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yipchat.core.common.AppException
import dev.yipchat.core.common.VisibleChats
import dev.yipchat.core.common.attempt
import dev.yipchat.core.data.account.AccountRepository
import dev.yipchat.core.data.chat.ChatCommands
import dev.yipchat.core.data.chat.ChatConnection
import dev.yipchat.core.data.chat.ChatRepository
import dev.yipchat.core.data.chat.ChatSession
import dev.yipchat.core.data.chat.EmoteStats
import dev.yipchat.core.data.chat.MentionNotifier
import dev.yipchat.core.data.chat.RaidTarget
import dev.yipchat.core.data.chat.ReplaySession
import dev.yipchat.core.data.cosmetics.ChannelCosmetics
import dev.yipchat.core.data.cosmetics.CosmeticsRepository
import dev.yipchat.core.data.history.WatchLaterItem
import dev.yipchat.core.data.history.WatchLaterRepository
import dev.yipchat.core.data.points.PointsRepository
import dev.yipchat.core.data.settings.SettingsRepository
import dev.yipchat.core.data.twitch.TwitchRepository
import dev.yipchat.core.model.AppSettings
import dev.yipchat.core.model.BufferProfile
import dev.yipchat.core.model.ChannelEvents
import dev.yipchat.core.model.ChannelPoll
import dev.yipchat.core.model.ChatItem
import dev.yipchat.core.model.CustomReward
import dev.yipchat.core.model.Emote
import dev.yipchat.core.model.FilterSettings
import dev.yipchat.core.model.LiveStream
import dev.yipchat.core.model.ModMacro
import dev.yipchat.core.model.PinnedMessage
import dev.yipchat.core.model.PollChoice
import dev.yipchat.core.model.PredictionOutcome
import dev.yipchat.core.model.RoomState
import dev.yipchat.core.model.StreamQuality
import dev.yipchat.core.model.User
import dev.yipchat.core.model.UserNote
import dev.yipchat.core.model.VideoChapter
import dev.yipchat.core.model.VideoExtras
import dev.yipchat.core.network.twitch.HelixApi
import dev.yipchat.core.playback.DownloadMeta
import dev.yipchat.core.playback.Downloads
import dev.yipchat.core.playback.PlaybackController
import dev.yipchat.core.playback.PlaybackSource
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** CHAT opens a channel's chat without the player, so it works when the channel is offline. */
enum class WatchKind { LIVE, VOD, CLIP, CHAT }

data class WatchInfo(
    val user: User? = null,
    val stream: LiveStream? = null,
    val title: String = "",
    val chapters: List<VideoChapter> = emptyList(),
    val extras: VideoExtras = VideoExtras(),
    val clipVodId: String? = null,
    val clipVodOffset: Int? = null,
    val error: String? = null,
)

data class UserCard(
    val login: String,
    val user: User? = null,
    val messages: List<ChatItem.Message> = emptyList(),
    val loading: Boolean = true,
    val logs: LogsState = LogsState.Idle,
)

sealed interface LogsState {
    data object Idle : LogsState
    data object Loading : LogsState
    /** Newest first. */
    data class Loaded(val messages: List<ChatItem.Message>) : LogsState
    data class Unavailable(val reason: String) : LogsState
}

/** Recently used emotes, shared across screens for this app session. */
internal object RecentEmotes {
    val list = MutableStateFlow<List<Emote>>(emptyList())
    fun use(e: Emote) = list.update { (listOf(e) + it.filterNot { x -> x.code == e.code }).take(30) }
}

@OptIn(UnstableApi::class)
@HiltViewModel
class WatchViewModel @Inject constructor(
    savedState: SavedStateHandle,
    val playback: PlaybackController,
    private val twitch: TwitchRepository,
    private val chatRepo: ChatRepository,
    private val cosmetics: CosmeticsRepository,
    private val accounts: AccountRepository,
    private val settingsRepo: SettingsRepository,
    private val points: PointsRepository,
    private val helix: HelixApi,
    private val replay: ReplaySession,
    private val mentions: MentionNotifier,
    private val downloads: Downloads,
    private val translator: ChatTranslator,
    private val emoteStats: EmoteStats,
    private val watchLater: WatchLaterRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
) : ViewModel() {

    val canTranslate get() = translator.available
    private val _translations = MutableStateFlow<Map<String, Translation>>(emptyMap())
    val translations = _translations.asStateFlow()

    fun translate(m: ChatItem.Message) {
        if (_translations.value[m.id] is Translation.Done) return
        _translations.update { it + (m.id to Translation.Working) }
        viewModelScope.launch { _translations.update { it + (m.id to translator.translate(m.raw)) } }
    }

    val downloadItems = downloads.items


    /** Download id for this VOD or clip, or null for live. */
    val downloadId: String? get() = when (kind) {
        WatchKind.VOD -> downloads.id("VOD", target)
        WatchKind.CLIP -> downloads.id("CLIP", target)
        else -> null
    }

    fun download(q: StreamQuality) = viewModelScope.launch {
        val src = playback.state.value.source ?: return@launch
        val user = _info.value.user
        var meta = when (src) {
            is PlaybackSource.Vod -> DownloadMeta("VOD", target, src.title, src.channelLogin, src.channelName, src.thumbnailUrl, src.durationSeconds, q.label)
            is PlaybackSource.ClipSrc -> DownloadMeta("CLIP", target, src.title, src.channelLogin, src.channelName, src.thumbnailUrl, 0, q.label)
            else -> return@launch
        }.copy(channelAvatarUrl = user?.profileImageUrl)
        // Clips opened from a link start with no thumbnail or channel; fill them in.
        if (meta.kind == "CLIP" && (meta.thumbnailUrl == null || meta.channelName.isBlank() || meta.title.isBlank())) {
            attempt { helix.clips(ids = listOf(target)).data.firstOrNull() }.getOrNull()?.let { c ->
                meta = meta.copy(
                    title = meta.title.ifBlank { c.title },
                    thumbnailUrl = meta.thumbnailUrl ?: c.thumbnailUrl,
                    channelName = meta.channelName.ifBlank { c.broadcasterName },
                    channelLogin = meta.channelLogin.ifBlank { c.broadcasterName.lowercase() },
                    durationSeconds = c.duration.toLong(),
                )
                if (meta.channelAvatarUrl == null) {
                    meta = meta.copy(channelAvatarUrl = attempt { twitch.channel(id = c.broadcasterId) }.getOrNull()?.user?.profileImageUrl)
                }
            }
        }
        downloads.start(q.url, meta.copy(title = meta.title.ifBlank { _info.value.title }))
    }

    fun removeDownload() { downloadId?.let(downloads::remove) }

    fun resolveAutoMod(item: ChatItem.AutoMod, allow: Boolean) = viewModelScope.launch {
        val me = accounts.userId() ?: return@launch
        val id = item.id.removePrefix("automod-")
        attempt { helix.resolveAutoMod(me, id, allow) }
            .onSuccess { session.value?.markAutoMod(id, if (allow) "approved" else "denied") }
            .onFailure { e -> session.value?.system("Couldn't ${if (allow) "allow" else "deny"} that message: ${e.message?.substringAfter(": ")?.take(120)}") }
    }

    /** Polls voted in this session (Twitch doesn't tell us). */
    private val _voted = MutableStateFlow<Set<String>>(emptySet())
    val votedPolls = _voted.asStateFlow()

    fun votePoll(poll: ChannelPoll, choice: PollChoice, extraVotes: Int = 0) = viewModelScope.launch {
        val error = points.votePoll(poll, choice, extraVotes)
        if (error == null) _voted.update { it + poll.id }
        session.value?.system(error?.let { "Vote failed: $it" } ?: "Voted for ${choice.title}")
    }

    fun predict(p: dev.yipchat.core.model.Prediction, outcome: PredictionOutcome, amount: Int) = viewModelScope.launch {
        val error = points.predict(p, outcome, amount)
        session.value?.system(error?.let { "Prediction failed: $it" } ?: "Predicted ${"%,d".format(amount)} on ${outcome.title}")
        if (error == null) _points.update { it?.minus(amount) }
    }

    private val _rewards = MutableStateFlow<RewardsState?>(null)
    val rewards = _rewards.asStateFlow()

    fun openRewards() {
        val login = _info.value.user?.login ?: return
        _rewards.value = RewardsState.Loading
        viewModelScope.launch {
            _rewards.value = points.rewards(login).fold({ RewardsState.Ready(it) }, { RewardsState.Failed(it.message ?: "Couldn't load rewards") })
        }
    }

    fun redeem(reward: CustomReward, text: String?) {
        val ready = _rewards.value as? RewardsState.Ready ?: return
        _rewards.value = ready.copy(busy = reward.id, message = null)
        viewModelScope.launch {
            val error = points.redeem(ready.data.channelId, reward, text)
            val fresh = _info.value.user?.login?.let { points.rewards(it).getOrNull() } ?: ready.data
            _rewards.value = RewardsState.Ready(fresh, message = error ?: "Redeemed ${reward.title}")
            if (error == null) fresh.balance?.let { _points.value = it }
        }
    }

    fun closeRewards() { _rewards.value = null }

    private val _clip = MutableStateFlow<ClipState?>(null)
    val clip = _clip.asStateFlow()

    /**
     * Rewind a live stream: opens the VOD being recorded, [backSeconds] behind what you see now.
     * [backSeconds] null starts from the beginning. Calls [onOpen] with the VOD id and offset, or reports why not.
     */
    fun rewind(backSeconds: Int?, onOpen: (String, Int) -> Unit) = viewModelScope.launch {
        val login = _info.value.user?.login ?: return@launch
        val archive = attempt { twitch.liveArchive(login) }.getOrNull()
        if (archive == null) { _rewindError.tryEmit("This channel doesn't save VODs, so there's nothing to rewind to."); return@launch }
        val started = (archive.second ?: _info.value.stream?.startedAt)?.toEpochMilliseconds()
        val latency = playback.state.value.liveLatencyMs ?: 0L
        val livePos = started?.let { ((System.currentTimeMillis() - it - latency) / 1000).toInt() } ?: 0
        val offset = if (backSeconds == null) 0 else (livePos - backSeconds).coerceAtLeast(0)
        onOpen(archive.first, offset)
    }

    private val _rewindError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val rewindError = _rewindError.asSharedFlow()

    /** Opens the clip setup: live clips the last N seconds, VODs pick a range around where you are. */
    fun createClip() {
        val title = _info.value.title.take(100)
        _clip.value = if (kind == WatchKind.VOD) {
            val pos = playback.player.currentPosition / 1000f
            val length = (playback.player.duration.takeIf { it > 0 } ?: 0L) / 1000f
            val windowStart = (pos - 90f).coerceAtLeast(0f)
            val windowEnd = if (length > 0) (pos + 30f).coerceAtMost(length) else pos + 30f
            val start = (pos - 30f).coerceAtLeast(windowStart)
            ClipState.VodSetup(title, windowStart, windowEnd.coerceAtLeast(start + MIN_CLIP), start, (start + 30f).coerceAtMost(windowEnd.coerceAtLeast(start + MIN_CLIP)))
        } else ClipState.LiveSetup(title)
    }

    fun createLiveClip(title: String, seconds: Int) = viewModelScope.launch {
        val ch = channelIdFlow.value ?: return@launch
        val setup = _clip.value as? ClipState.LiveSetup
        _clip.value = ClipState.Creating
        attempt { helix.createClip(ch, title.ifBlank { null }, seconds) }
            .onSuccess { id -> _clip.value = id?.let(ClipState::Ready) ?: ClipState.Failed("Twitch didn't return a clip.") }
            .onFailure { e -> _clip.value = (setup ?: ClipState.LiveSetup(title)).copy(error = e.message ?: "Clips may be off for this channel.") }
    }

    fun createVodClip(title: String, start: Float, end: Float) = viewModelScope.launch {
        val ch = channelIdFlow.value ?: return@launch
        val vod = playback.state.value.source as? PlaybackSource.Vod ?: return@launch
        val setup = _clip.value as? ClipState.VodSetup
        previewJob?.cancel()
        _clip.value = ClipState.Creating
        attempt { twitch.createVodClip(ch, vod.videoId, start.toDouble(), end.toDouble(), title.ifBlank { _info.value.title }) }
            .onSuccess { r -> _clip.value = ClipState.Ready(r.slug, note = r.note) }
            .onFailure { e -> _clip.value = setup?.copy(error = e.message ?: "Couldn't clip this VOD.") ?: ClipState.Failed(e.message ?: "Couldn't clip this VOD.") }
    }

    private var previewJob: Job? = null

    /** Plays [start]..[end] in the player behind the sheet. */
    fun previewVodClip(start: Float, end: Float) {
        previewJob?.cancel()
        playback.player.seekTo((start * 1000).toLong())
        playback.player.play()
        previewJob = viewModelScope.launch {
            while (playback.player.currentPosition < (end * 1000).toLong()) delay(100)
            playback.player.pause()
        }
    }

    fun dismissClip() { previewJob?.cancel(); _clip.value = null }

    fun saveNote(login: String, note: UserNote) = viewModelScope.launch {
        settingsRepo.update { s ->
            val key = login.lowercase()
            val notes = if (note.nickname.isBlank() && note.note.isBlank()) s.chat.userNotes - key else s.chat.userNotes + (key to note)
            s.copy(chat = s.chat.copy(userNotes = notes))
        }
    }

    fun toggleFavouriteEmote(e: Emote) = viewModelScope.launch {
        settingsRepo.update { s ->
            val fav = s.chat.favouriteEmotes
            s.copy(chat = s.chat.copy(favouriteEmotes = if (e.favouriteKey in fav) fav - e.favouriteKey else fav + e.favouriteKey))
        }
    }

    fun updateFilters(transform: (FilterSettings) -> FilterSettings) = viewModelScope.launch {
        settingsRepo.update { it.copy(filters = transform(it.filters)) }
    }

    /** Switches the live buffer between low latency and the steadier default, then restarts playback. */
    fun setLowLatency(on: Boolean) = viewModelScope.launch {
        val profile = if (on) BufferProfile.LOW_LATENCY else BufferProfile.BALANCED
        if (settingsRepo.current().player.bufferProfile == profile) return@launch
        settingsRepo.update { it.copy(player = it.player.copy(bufferProfile = profile)) }
        playback.recreate()
    }

    /** While chat is on screen, no pings for it; opening it clears old ones. */
    fun chatOnScreen(channelId: String, login: String, visible: Boolean) {
        if (visible) {
            VisibleChats.show(channelId)
            mentions.clear(login)
        } else {
            VisibleChats.hide(channelId)
        }
    }
    val kind: WatchKind = WatchKind.valueOf(savedState.get<String>("kind") ?: "LIVE")
    private val target: String = checkNotNull(savedState["id"])

    private val laterKind get() = if (kind == WatchKind.CLIP) "CLIP" else "VOD"

    val inWatchLater: StateFlow<Boolean> = watchLater.items
        .map { list -> list.any { it.kind == laterKind && it.id == target } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleWatchLater() {
        if (kind != WatchKind.VOD && kind != WatchKind.CLIP) return
        if (inWatchLater.value) { watchLater.remove(laterKind, target); return }
        val i = _info.value
        val src = playback.state.value.source
        watchLater.add(
            WatchLaterItem(
                laterKind, target, i.user?.login ?: src?.channelLogin.orEmpty(), i.user?.displayName ?: src?.channelName.orEmpty(),
                i.title.ifBlank { src?.title.orEmpty() }, src?.thumbnailUrl,
                (playback.player.duration.takeIf { it > 0 } ?: 0L) / 1000, System.currentTimeMillis(),
                vodId = (src as? PlaybackSource.ClipSrc)?.vodId,
                vodOffset = (src as? PlaybackSource.ClipSrc)?.vodOffset,
            ),
        )
    }

    private val offset: Int? = savedState.get<Int>("offset")?.takeIf { it >= 0 }

    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val playbackState = playback.state
    val signedIn = accounts.active.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** twitch.tv connected: voting, predictions, rewards. */
    val webConnected = accounts.active.map { it?.hasWebToken == true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _info = MutableStateFlow(WatchInfo())
    val info = _info.asStateFlow()

    /** Polls, predictions and Hype Train, refreshed every 20s while the screen is visible. */
    val events: StateFlow<ChannelEvents> = _info
        .map { if (kind == WatchKind.LIVE && it.stream != null) it.user?.login else null }
        .distinctUntilChanged()
        .flatMapLatest { login ->
            if (login == null) flowOf(ChannelEvents()) else kotlinx.coroutines.flow.flow {
                while (true) {
                    attempt { twitch.channelEvents(login) }.onSuccess { emit(it) }
                    delay(20_000)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelEvents())


    private val pinRefresh = MutableStateFlow(0)

    /** The channel's pinned chat message, checked every 30s and right after pinning. */
    val pinned: StateFlow<PinnedMessage?> = _info
        .map { if (kind == WatchKind.LIVE || kind == WatchKind.CHAT) it.user?.login else null }
        .distinctUntilChanged()
        .flatMapLatest { login ->
            if (login == null) flowOf<PinnedMessage?>(null) else pinRefresh.flatMapLatest {
                kotlinx.coroutines.flow.flow<PinnedMessage?> {
                    while (true) {
                        attempt { twitch.pinnedMessage(login) }.onSuccess { emit(it) }
                        delay(30_000)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** [seconds] null pins until it's removed or the stream ends. */
    fun pin(message: ChatItem.Message, seconds: Int?) = viewModelScope.launch {
        val ch = channelIdFlow.value ?: return@launch
        attempt { twitch.pinMessage(ch, message.id, seconds) }
            .onSuccess { pinRefresh.value++ }
            .onFailure { session.value?.system(if (it is AppException && it.kind == AppException.Kind.AUTH) "Connect twitch.tv in Accounts to pin messages" else "Pin failed: ${it.message}") }
    }

    fun unpin(p: PinnedMessage) = viewModelScope.launch {
        attempt { twitch.unpinMessage(p.id) }
            .onSuccess { pinRefresh.value++ }
            .onFailure { session.value?.system("Unpin failed: ${it.message}") }
    }

    private val session = MutableStateFlow<ChatSession?>(null)
    val chat: StateFlow<ImmutableList<ChatItem>> = session.flatMapLatest { it?.items ?: if (kind == WatchKind.VOD) replay.items else flowOf(persistentListOf()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), persistentListOf())
    val raid: StateFlow<RaidTarget?> = session.flatMapLatest { it?.raid ?: flowOf(null) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun dismissRaid() = session.value?.dismissRaid()

    val room: StateFlow<RoomState> = session.flatMapLatest { it?.room ?: flowOf(RoomState()) }.stateIn(viewModelScope, SharingStarted.Eagerly, RoomState())
    val connection: StateFlow<ChatConnection> = session.flatMapLatest { it?.connection ?: flowOf(ChatConnection()) }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatConnection())

    private val channelIdFlow = MutableStateFlow<String?>(null)
    val cosmeticsState: StateFlow<ChannelCosmetics?> = channelIdFlow.flatMapLatest { id -> id?.let { cosmetics.channel(it) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _twitchEmotes = MutableStateFlow<List<Emote>>(emptyList())
    val twitchEmotes = _twitchEmotes.asStateFlow()
    val recentEmotes = RecentEmotes.list.asStateFlow()

    private val _isModerator = MutableStateFlow(false)
    val isModerator = _isModerator.asStateFlow()
    private val _isBroadcaster = MutableStateFlow(false)
    val commands = combine(_isModerator, _isBroadcaster) { mod, owner -> ChatCommands.available(mod, owner) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatCommands.available(false, false))

    private val _points = MutableStateFlow<Int?>(null)
    val pointsBalance = _points.asStateFlow()

    private val _userCard = MutableStateFlow<UserCard?>(null)
    val userCard = _userCard.asStateFlow()

    private val _replyTo = MutableStateFlow<ChatItem.Message?>(null)
    val replyTo = _replyTo.asStateFlow()

    private var pointsJob: Job? = null
    private var replayJob: Job? = null

    private val savedStateLogin: String? = savedState["channelLogin"]
    private val savedStateTitle: String? = savedState["title"]
    private val savedStateVodId: String? = savedState["vodId"]

    init {
        if (kind == WatchKind.VOD || kind == WatchKind.CLIP) viewModelScope.launch {
            playback.state.map { it.ended && it.source != null }.distinctUntilChanged().collect { ended ->
                // Finished, so it leaves the queue.
                if (ended && inWatchLater.value) watchLater.remove(laterKind, target)
            }
        }
        load()
        viewModelScope.launch {
            session.flatMapLatest { it?.mentionsHere ?: emptyFlow() }.collect {
                if (settingsRepo.settings.value.chat.mentionVibrate) vibrate()
            }
        }
        viewModelScope.launch {
            session.flatMapLatest { it?.broadcasterHere ?: emptyFlow() }.collect {
                if (settingsRepo.settings.value.chat.broadcasterVibrate) vibrate()
            }
        }
        // Chat waits for the stream: hold live messages by the player's delay, minus a little for chat's own lag.
        if (kind == WatchKind.LIVE) viewModelScope.launch {
            combine(session, playback.state.map { it.liveLatencyMs }, settingsRepo.settings.map { it.chat.syncWithStream }) { ses, latency, sync ->
                Triple(ses, latency, sync)
            }.collect { (ses, latency, sync) ->
                ses?.displayDelayMs = if (sync && latency != null) (latency - CHAT_LAG_MS).coerceIn(0, MAX_CHAT_DELAY_MS) else 0
            }
        }
    }

    fun load() = viewModelScope.launch {
        _info.value = WatchInfo()
        when (kind) {
            WatchKind.LIVE -> attempt { twitch.channel(login = target) }.onSuccess { ci ->
                if (ci == null) { _info.value = WatchInfo(error = "Channel not found"); return@onSuccess }
                _info.value = WatchInfo(ci.user, ci.stream, ci.stream?.title.orEmpty())
                startChat(ci.user.id, ci.user.login)
                val stream = ci.stream
                if (stream == null) {
                    _info.update { it.copy(error = "${ci.user.displayName} is offline") }
                } else {
                    playback.play(PlaybackSource.Live(ci.user.id, ci.user.login, ci.user.displayName, stream.title, stream.thumbnail(640, 360), stream.gameName))
                }
                afterChannelKnown(ci.user)
            }.onFailure { _info.value = WatchInfo(error = it.message) }

            WatchKind.CHAT -> attempt { twitch.channel(login = target) }.onSuccess { ci ->
                if (ci == null) { _info.value = WatchInfo(error = "Channel not found"); return@onSuccess }
                _info.value = WatchInfo(ci.user, ci.stream, ci.stream?.title.orEmpty())
                startChat(ci.user.id, ci.user.login)
                afterChannelKnown(ci.user)
            }.onFailure { _info.value = WatchInfo(error = it.message) }

            WatchKind.VOD -> attempt { twitch.video(target) }.onSuccess { v ->
                if (v == null) { _info.value = WatchInfo(error = "Video not found"); return@onSuccess }
                val user = attempt { twitch.channel(login = v.userLogin.ifBlank { null }, id = v.userId.ifBlank { null }) }.getOrNull()?.user
                _info.value = WatchInfo(user, null, v.title)
                playback.play(PlaybackSource.Vod(v.id, v.userId, v.userLogin, v.userName, v.title, v.thumbnail(640, 360), v.durationSeconds, offset?.toLong()))
                launch { _info.update { it.copy(chapters = twitch.chapters(v.id)) } }
                launch { val extras = twitch.videoExtras(v.id); _info.update { it.copy(extras = extras) } }
                channelIdFlow.value = v.userId
                replay.start(v.id, v.userId)
                replayJob = launch {
                    while (isActive) {
                        val pos = (playback.player.currentPosition / 1000).toInt()
                        if (settings.value.chat.messageLimit > 0) replay.seekTo(pos)
                        if (settings.value.player.skipMutedParts && playback.player.isPlaying) {
                            _info.value.extras.muted.firstOrNull { pos in it.offsetSeconds until it.endSeconds - 1 }?.let { m ->
                                playback.player.seekTo(m.endSeconds * 1000L)
                            }
                        }
                        delay(1000)
                    }
                }
                user?.let(::afterChannelKnown)
            }.onFailure { _info.value = WatchInfo(error = it.message) }

            WatchKind.CLIP -> {
                val login = savedStateLogin
                val user = login?.let { attempt { twitch.channel(login = it) }.getOrNull()?.user }
                val title = savedStateTitle.orEmpty()
                _info.value = WatchInfo(user, null, title, clipVodId = savedStateVodId, clipVodOffset = offset)
                playback.play(PlaybackSource.ClipSrc(target, user?.id.orEmpty(), user?.login.orEmpty(), user?.displayName.orEmpty(), title, null, savedStateVodId, offset))
            }
        }
    }

    private fun startChat(channelId: String, login: String) {
        if (session.value?.channelId == channelId) return
        session.value?.let(chatRepo::close)
        channelIdFlow.value = channelId
        session.value = chatRepo.open(channelId, login)
    }

    private fun afterChannelKnown(user: User) {
        viewModelScope.launch { _twitchEmotes.value = cosmetics.userTwitchEmotes(user.id) }
        viewModelScope.launch {
            val me = accounts.userId() ?: return@launch
            _isBroadcaster.value = me == user.id
            _isModerator.value = me == user.id || attempt { helix.moderatedChannels(me) }.getOrDefault(emptyList()).contains(user.id)
        }
        if (kind == WatchKind.LIVE) startPoints(user.login)
    }

    private fun startPoints(login: String) {
        pointsJob?.cancel()
        pointsJob = viewModelScope.launch {
            while (isActive) {
                val exp = settings.value.experimental
                val connected = accounts.state.value.active?.hasWebToken == true
                if (connected && (exp.showPointsBalance || exp.autoClaimPoints)) {
                    val claimed = if (exp.autoClaimPoints) points.claimIfAvailable(login) else null
                    _points.value = claimed ?: points.status(login)?.balance ?: _points.value
                    if (claimed != null) session.value?.system("Claimed a channel points bonus")
                }
                delay(60_000)
            }
        }
    }

    fun send(text: String) = viewModelScope.launch {
        val s = session.value ?: return@launch
        s.send(text, _replyTo.value?.id)
        _replyTo.value = null
        emoteStats.record(text.split(' ').mapNotNull { w -> w.takeIf { it.isNotBlank() }?.let(::emoteFor) })
    }

    val emoteUses = emoteStats.uses

    fun setReply(m: ChatItem.Message?) { _replyTo.value = m }

    fun useEmote(e: Emote) = RecentEmotes.use(e)

    /** Candidates for Tab/@ completion: emotes and recent chatters. */
    fun completions(prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val s = settings.value.chat
        return if (prefix.startsWith("@")) {
            if (!s.autocompleteUsers) return emptyList()
            val p = prefix.drop(1)
            session.value?.chatters().orEmpty().filter { it.startsWith(p, ignoreCase = true) }.take(12).map { "@$it" }
        } else {
            if (!s.autocompleteEmotes || prefix.length < 2) return emptyList()
            val pool = (cosmeticsState.value?.emotes?.keys.orEmpty() + _twitchEmotes.value.map { it.code }).distinct()
            val starts = pool.filter { it.startsWith(prefix, ignoreCase = true) }.sortedBy { it.length }
            val contains = if (starts.size < 8) pool.filter { it.contains(prefix, ignoreCase = true) && it !in starts } else emptyList()
            (starts + contains).take(12)
        }
    }

    fun emoteFor(code: String): Emote? = cosmeticsState.value?.emotes?.get(code) ?: _twitchEmotes.value.firstOrNull { it.code == code }

    fun openUser(login: String) {
        _userCard.value = UserCard(login)
        viewModelScope.launch {
            val user = attempt { twitch.channel(login = login) }.getOrNull()?.user
            val msgs = chat.value.filterIsInstance<ChatItem.Message>().filter { it.user.login.equals(login, true) }.takeLast(50)
            _userCard.value = UserCard(login, user, msgs, loading = false)
        }
    }

    fun closeUser() { _userCard.value = null }

    private val _thread = MutableStateFlow<List<ChatItem.Message>?>(null)
    val thread = _thread.asStateFlow()
    fun openThread(m: ChatItem.Message) { _thread.value = session.value?.thread(m)?.ifEmpty { listOf(m) } ?: listOf(m) }
    fun closeThread() { _thread.value = null }

    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= 31) appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") appContext.getSystemService(Vibrator::class.java)
        v?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun loadUserLogs() {
        val card = _userCard.value ?: return
        val ses = session.value ?: return
        _userCard.value = card.copy(logs = LogsState.Loading)
        viewModelScope.launch {
            val state = ses.logsFor(card.login).fold(
                onSuccess = { list -> if (list == null) LogsState.Unavailable("This channel isn't in the public chat logs") else LogsState.Loaded(list) },
                onFailure = { LogsState.Unavailable("Couldn't load chat logs") },
            )
            _userCard.update { c -> c?.takeIf { it.login == card.login }?.copy(logs = state) ?: c }
        }
    }

    fun moderate(action: ModAction, userId: String, messageId: String? = null, reason: String? = null) = viewModelScope.launch {
        val me = accounts.userId() ?: return@launch
        val ch = channelIdFlow.value ?: return@launch
        attempt {
            when (action) {
                ModAction.DELETE -> helix.deleteMessage(ch, me, messageId)
                ModAction.TIMEOUT_1M -> helix.ban(ch, me, userId, 60, reason)
                ModAction.TIMEOUT_10M -> helix.ban(ch, me, userId, 600, reason)
                ModAction.TIMEOUT_1H -> helix.ban(ch, me, userId, 3600, reason)
                ModAction.BAN -> helix.ban(ch, me, userId, null, reason)
                ModAction.UNBAN -> helix.unban(ch, me, userId)
            }
        }.onFailure { session.value?.system("Moderation failed: ${it.message}") }
    }

    /** A quick button from settings: timeout for [ModMacro.seconds], or ban when null. */
    fun moderate(macro: ModMacro, userId: String) = viewModelScope.launch {
        val me = accounts.userId() ?: return@launch
        val ch = channelIdFlow.value ?: return@launch
        attempt { helix.ban(ch, me, userId, macro.seconds, macro.reason) }
            .onFailure { session.value?.system("Moderation failed: ${it.message}") }
    }

    fun block(userId: String) = viewModelScope.launch {
        attempt { helix.block(userId) }.onSuccess { session.value?.system("User blocked") }
    }

    override fun onCleared() {
        session.value?.let(chatRepo::close)
        pointsJob?.cancel()
        replayJob?.cancel()
    }
}

enum class ModAction { DELETE, TIMEOUT_1M, TIMEOUT_10M, TIMEOUT_1H, BAN, UNBAN }

private const val CHAT_LAG_MS = 700L
private const val MAX_CHAT_DELAY_MS = 30_000L
