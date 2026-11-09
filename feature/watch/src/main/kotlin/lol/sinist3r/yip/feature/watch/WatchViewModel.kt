package lol.sinist3r.yip.feature.watch

import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.data.account.AccountRepository
import lol.sinist3r.yip.core.data.chat.ChatConnection
import lol.sinist3r.yip.core.data.chat.ChatRepository
import lol.sinist3r.yip.core.data.chat.ChatSession
import lol.sinist3r.yip.core.data.chat.ReplaySession
import lol.sinist3r.yip.core.data.cosmetics.ChannelCosmetics
import lol.sinist3r.yip.core.data.cosmetics.CosmeticsRepository
import lol.sinist3r.yip.core.data.points.PointsRepository
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.data.twitch.TwitchRepository
import lol.sinist3r.yip.core.model.AppSettings
import lol.sinist3r.yip.core.model.ChatItem
import lol.sinist3r.yip.core.model.Emote
import lol.sinist3r.yip.core.model.LiveStream
import lol.sinist3r.yip.core.model.RoomState
import lol.sinist3r.yip.core.model.User
import lol.sinist3r.yip.core.model.VideoChapter
import lol.sinist3r.yip.core.network.twitch.HelixApi
import lol.sinist3r.yip.core.playback.PlaybackController
import lol.sinist3r.yip.core.playback.PlaybackSource

/** CHAT opens a channel's chat without the player, so it works when the channel is offline. */
enum class WatchKind { LIVE, VOD, CLIP, CHAT }

data class WatchInfo(
    val user: User? = null,
    val stream: LiveStream? = null,
    val title: String = "",
    val chapters: List<VideoChapter> = emptyList(),
    val clipVodId: String? = null,
    val clipVodOffset: Int? = null,
    val error: String? = null,
)

data class UserCard(
    val login: String,
    val user: User? = null,
    val messages: List<ChatItem.Message> = emptyList(),
    val loading: Boolean = true,
)

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
    private val mentions: lol.sinist3r.yip.core.data.chat.MentionNotifier,
) : ViewModel() {

    fun resolveAutoMod(item: ChatItem.AutoMod, allow: Boolean) = viewModelScope.launch {
        val me = accounts.userId() ?: return@launch
        val id = item.id.removePrefix("automod-")
        attempt { helix.resolveAutoMod(me, id, allow) }
            .onSuccess { session.value?.markAutoMod(id, if (allow) "approved" else "denied") }
            .onFailure { e -> session.value?.system("Couldn't ${if (allow) "allow" else "deny"} that message: ${e.message?.substringAfter(": ")?.take(120)}") }
    }

    private val _clip = MutableStateFlow<ClipState?>(null)
    val clip = _clip.asStateFlow()

    fun createClip() = viewModelScope.launch {
        val ch = channelIdFlow.value ?: return@launch
        _clip.value = ClipState.Creating
        _clip.value = attempt { helix.createClip(ch) }.fold(
            onSuccess = { id -> id?.let(ClipState::Ready) ?: ClipState.Failed("Twitch didn't return a clip.") },
            onFailure = { ClipState.Failed(it.message ?: "Unknown error. Clips may be off for this channel.") },
        )
    }

    fun dismissClip() { _clip.value = null }

    fun toggleFavouriteEmote(e: lol.sinist3r.yip.core.model.Emote) = viewModelScope.launch {
        settingsRepo.update { s ->
            val fav = s.chat.favouriteEmotes
            s.copy(chat = s.chat.copy(favouriteEmotes = if (e.favouriteKey in fav) fav - e.favouriteKey else fav + e.favouriteKey))
        }
    }

    fun updateFilters(transform: (lol.sinist3r.yip.core.model.FilterSettings) -> lol.sinist3r.yip.core.model.FilterSettings) = viewModelScope.launch {
        settingsRepo.update { it.copy(filters = transform(it.filters)) }
    }

    /** Switches the live buffer between low latency and the steadier default, then restarts playback. */
    fun setLowLatency(on: Boolean) = viewModelScope.launch {
        val profile = if (on) lol.sinist3r.yip.core.model.BufferProfile.LOW_LATENCY else lol.sinist3r.yip.core.model.BufferProfile.BALANCED
        if (settingsRepo.current().player.bufferProfile == profile) return@launch
        settingsRepo.update { it.copy(player = it.player.copy(bufferProfile = profile)) }
        playback.recreate()
    }

    /** While chat is on screen, no pings for it; opening it clears old ones. */
    fun chatOnScreen(channelId: String, login: String, visible: Boolean) {
        if (visible) {
            lol.sinist3r.yip.core.common.VisibleChats.show(channelId)
            mentions.clear(login)
        } else {
            lol.sinist3r.yip.core.common.VisibleChats.hide(channelId)
        }
    }
    val kind: WatchKind = WatchKind.valueOf(savedState.get<String>("kind") ?: "LIVE")
    private val target: String = checkNotNull(savedState["id"])
    private val offset: Int? = savedState.get<Int>("offset")?.takeIf { it >= 0 }

    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val playbackState = playback.state
    val signedIn = accounts.active.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _info = MutableStateFlow(WatchInfo())
    val info = _info.asStateFlow()

    private val session = MutableStateFlow<ChatSession?>(null)
    val chat: StateFlow<ImmutableList<ChatItem>> = session.flatMapLatest { it?.items ?: if (kind == WatchKind.VOD) replay.items else flowOf(persistentListOf()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), persistentListOf())
    val raid: StateFlow<lol.sinist3r.yip.core.data.chat.RaidTarget?> = session.flatMapLatest { it?.raid ?: flowOf(null) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
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

    init { load() }

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
                channelIdFlow.value = v.userId
                replay.start(v.id, v.userId)
                replayJob = launch {
                    while (isActive) {
                        val pos = (playback.player.currentPosition / 1000).toInt()
                        if (settings.value.chat.messageLimit > 0) replay.seekTo(pos)
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
    }

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

    fun moderate(action: ModAction, userId: String, messageId: String? = null) = viewModelScope.launch {
        val me = accounts.userId() ?: return@launch
        val ch = channelIdFlow.value ?: return@launch
        attempt {
            when (action) {
                ModAction.DELETE -> helix.deleteMessage(ch, me, messageId)
                ModAction.TIMEOUT_1M -> helix.ban(ch, me, userId, 60, null)
                ModAction.TIMEOUT_10M -> helix.ban(ch, me, userId, 600, null)
                ModAction.TIMEOUT_1H -> helix.ban(ch, me, userId, 3600, null)
                ModAction.BAN -> helix.ban(ch, me, userId, null, null)
                ModAction.UNBAN -> helix.unban(ch, me, userId)
            }
        }.onFailure { session.value?.system("Moderation failed: ${it.message}") }
    }

    fun block(userId: String) = viewModelScope.launch {
        attempt { helix.block(userId) }.onSuccess { session.value?.system("User blocked") }
    }

    fun systemMessage(text: String) = session.value?.system(text)

    override fun onCleared() {
        session.value?.let(chatRepo::close)
        pointsJob?.cancel()
        replayJob?.cancel()
        super.onCleared()
    }
}

enum class ModAction { DELETE, TIMEOUT_1M, TIMEOUT_10M, TIMEOUT_1H, BAN, UNBAN }
