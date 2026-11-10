package lol.sinist3r.yip.feature.watch

import android.content.res.Configuration
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.data.chat.ChatRepository
import lol.sinist3r.yip.core.data.account.AccountRepository
import lol.sinist3r.yip.core.data.chat.ChatSession
import lol.sinist3r.yip.core.data.cosmetics.ChannelCosmetics
import lol.sinist3r.yip.core.data.cosmetics.CosmeticsRepository
import lol.sinist3r.yip.core.data.playback.PlaybackRepository
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.data.twitch.TwitchRepository
import lol.sinist3r.yip.core.designsystem.chat.ChatActions
import lol.sinist3r.yip.core.designsystem.chat.ChatRenderOptions
import lol.sinist3r.yip.core.designsystem.component.Pill
import lol.sinist3r.yip.core.model.ChatItem
import lol.sinist3r.yip.core.model.Emote
import lol.sinist3r.yip.core.playback.PlaybackController
import lol.sinist3r.yip.core.playback.PlayerFactory

data class MultiTile(val login: String, val displayName: String, val player: ExoPlayer?, val chat: ChatSession?, val error: String? = null)

@OptIn(UnstableApi::class)
@HiltViewModel
class MultiViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val factory: PlayerFactory,
    private val playbackRepo: PlaybackRepository,
    private val twitch: TwitchRepository,
    private val chats: ChatRepository,
    private val main: PlaybackController,
    private val cosmetics: CosmeticsRepository,
    accounts: AccountRepository,
    private val settingsRepo: SettingsRepository,
    private val emoteStats: lol.sinist3r.yip.core.data.chat.EmoteStats,
) : ViewModel() {
    val settings = settingsRepo.settings
    val signedIn = accounts.active.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val maxStreams get() = settings.value.experimental.multiViewMaxStreams.coerceIn(2, 4)
    /** The signed-in user's Twitch emotes per channel id, loaded when that chat is first shown. */
    private val _twitchEmotes = MutableStateFlow<Map<String, List<Emote>>>(emptyMap())
    val twitchEmotes = _twitchEmotes.asStateFlow()
    private val loadingEmotes = mutableSetOf<String>()
    private val _tiles = MutableStateFlow<List<MultiTile>>(emptyList())
    val tiles: StateFlow<List<MultiTile>> = _tiles.asStateFlow()
    private val _focused = MutableStateFlow(0)
    val focused = _focused.asStateFlow()

    init {
        main.stop()
        val logins = savedState.get<String>("logins").orEmpty().split(',').filter { it.isNotBlank() }.distinct()
        logins.take(maxStreams).forEach(::add)
        // Signing in (or switching account) changes which emotes you can use.
        viewModelScope.launch {
            signedIn.collect { on ->
                if (!on) return@collect
                _twitchEmotes.value = emptyMap()
                _tiles.value.mapNotNull { it.chat?.channelId }.forEach(::loadTwitchEmotes)
            }
        }
    }

    fun add(login: String) {
        if (_tiles.value.any { it.login.equals(login, true) } || _tiles.value.size >= maxStreams) return
        _tiles.update { it + MultiTile(login, login, null, null) }
        viewModelScope.launch {
            val info = attempt { twitch.channel(login = login) }.getOrNull()
            if (info?.stream == null) {
                update(login) { it.copy(error = "Offline", displayName = info?.user?.displayName ?: login) }
                return@launch
            }
            val resolved = attempt { playbackRepo.resolveLive(login) }.getOrElse {
                update(login) { t -> t.copy(error = it.message) }
                return@launch
            }
            val s = settings.value.player
            val player = factory.create(s, forMultiView = true)
            val url = resolved.qualities.filterNot { it.isAudioOnly }.firstOrNull { (it.height ?: 0) <= 720 }?.url ?: resolved.masterPlaylistUrl
            player.setMediaSource(factory.hlsSource(url, live = true, s.bufferProfile, MediaMetadata.Builder().setTitle(info.user.displayName).build()))
            player.prepare()
            player.play()
            val chat = chats.open(info.user.id, info.user.login)
            loadTwitchEmotes(info.user.id)
            update(login) { it.copy(player = player, chat = chat, displayName = info.user.displayName) }
            applyAudio()
        }
    }

    fun remove(index: Int) {
        val t = _tiles.value.getOrNull(index) ?: return
        t.player?.release()
        t.chat?.let(chats::close)
        _tiles.update { it.filterIndexed { i, _ -> i != index } }
        _focused.value = _focused.value.coerceAtMost((_tiles.value.size - 1).coerceAtLeast(0))
        applyAudio()
    }

    fun focus(index: Int) {
        _focused.value = index
        applyAudio()
    }

    fun cosmeticsFor(channelId: String): StateFlow<ChannelCosmetics> = cosmetics.channel(channelId)

    /** Loads once per channel; an empty result (e.g. not signed in yet) is retried on the next call. */
    fun loadTwitchEmotes(channelId: String) {
        if (_twitchEmotes.value[channelId].orEmpty().isNotEmpty() || !loadingEmotes.add(channelId)) return
        viewModelScope.launch {
            try {
                val list = cosmetics.userTwitchEmotes(channelId)
                _twitchEmotes.update { it + (channelId to list) }
            } finally {
                loadingEmotes.remove(channelId)
            }
        }
    }

    fun send(session: ChatSession, text: String) = viewModelScope.launch {
        session.send(text)
        emoteStats.record(text.split(' ').mapNotNull { w -> w.takeIf { it.isNotBlank() }?.let { emoteFor(session.channelId, it) } })
    }

    val emoteUses = emoteStats.uses

    private fun pool(channelId: String) = cosmetics.channel(channelId).value.emotes.keys + _twitchEmotes.value[channelId].orEmpty().map { it.code }

    fun completions(session: ChatSession, prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val c = settings.value.chat
        return if (prefix.startsWith("@")) {
            if (!c.autocompleteUsers) emptyList() else session.chatters().filter { it.startsWith(prefix.drop(1), true) }.take(12).map { "@$it" }
        } else {
            if (!c.autocompleteEmotes || prefix.length < 2) return emptyList()
            val all = pool(session.channelId).distinct()
            val starts = all.filter { it.startsWith(prefix, true) }.sortedBy { it.length }
            val contains = if (starts.size < 8) all.filter { it.contains(prefix, true) && it !in starts } else emptyList()
            (starts + contains).take(12)
        }
    }

    fun emoteFor(channelId: String, code: String): Emote? =
        cosmetics.channel(channelId).value.emotes[code] ?: _twitchEmotes.value[channelId]?.firstOrNull { it.code == code }

    fun toggleFavouriteEmote(e: Emote) = viewModelScope.launch {
        settingsRepo.update { s ->
            val fav = s.chat.favouriteEmotes
            s.copy(chat = s.chat.copy(favouriteEmotes = if (e.favouriteKey in fav) fav - e.favouriteKey else fav + e.favouriteKey))
        }
    }

    private fun applyAudio() = _tiles.value.forEachIndexed { i, t -> t.player?.volume = if (i == _focused.value) 1f else 0f }

    private fun update(login: String, block: (MultiTile) -> MultiTile) =
        _tiles.update { list -> list.map { if (it.login == login) block(it) else it } }

    override fun onCleared() {
        _tiles.value.forEach { t -> t.player?.release(); t.chat?.let(chats::close) }
        super.onCleared()
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@OptIn(UnstableApi::class)
@Composable
fun MultiViewScreen(nav: WatchNavigator, viewModel: MultiViewModel = hiltViewModel()) {
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()
    val focused by viewModel.focused.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var chatTab by rememberSaveable { mutableIntStateOf(0) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val animate = rememberAnimate(settings.chat.animateEmotes, settings.appearance.reduceMotion, settings.data.dataSaver, settings.data.batterySaver)
    val linkPreviews = rememberLinkPreviews(settings.chat.linkPreviews, settings.data.dataSaver)
    val options = remember(settings.chat, settings.thirdParty, animate, linkPreviews) { ChatRenderOptions(settings.chat, settings.thirdParty, animate, linkPreviews) }
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val allTwitchEmotes by viewModel.twitchEmotes.collectAsStateWithLifecycle()
    val recent by RecentEmotes.list.collectAsStateWithLifecycle()
    val emoteUses by viewModel.emoteUses.collectAsStateWithLifecycle()
    var insert by remember { mutableStateOf<String?>(null) }
    var showEmotes by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var emoteCard by remember { mutableStateOf<Emote?>(null) }
    val actions = remember {
        object : ChatActions {
            override fun onLink(url: String) = nav.openLink(url)
            override fun onEmote(emote: Emote) { insert = emote.code }
            override fun onEmoteLongPress(emote: Emote) { emoteCard = emote }
            override fun onUser(login: String, userId: String?) { insert = "@$login" }
        }
    }
    val canAdd = tiles.size < viewModel.maxStreams
    val host = LocalWatchHost.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        host?.setWatching(true, autoPip = false, keepScreenOn = true)
        onDispose { host?.setWatching(false, false, false) }
    }

    var focusLayout by rememberSaveable { mutableStateOf(false) }
    val tile = @Composable { i: Int, t: MultiTile, mod: Modifier, small: Boolean ->
        Box(
            mod.aspectRatio(16f / 9f).background(Color.Black)
                .border(2.dp, if (i == focused) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable(onClickLabel = "Listen to ${t.displayName}") { viewModel.focus(i); chatTab = i },
        ) {
            t.player?.let { PlayerSurface(player = it, surfaceType = SURFACE_TYPE_TEXTURE_VIEW, modifier = Modifier.fillMaxSize()) }
            if (!small) Pill(t.displayName + if (i == focused) " · audio" else "", Modifier.align(Alignment.TopStart).padding(6.dp))
            t.error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center)) }
            if (!small) IconButton(onClick = { viewModel.remove(i) }, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Outlined.Close, "Remove ${t.displayName}", tint = Color.White) }
        }
    }
    val grid = @Composable { mod: Modifier ->
        if (focusLayout && tiles.size > 1) {
            // Focused stream big, the rest in a strip below. Tap a small one to swap.
            Column(mod) {
                val f = focused.coerceIn(0, tiles.lastIndex)
                androidx.compose.runtime.key(tiles[f].login) { tile(f, tiles[f], Modifier.fillMaxWidth(), false) }
                Row {
                    tiles.forEachIndexed { i, t ->
                        if (i != f) androidx.compose.runtime.key(t.login) { tile(i, t, Modifier.weight(1f), true) }
                    }
                    repeat(3 - (tiles.size - 1)) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            LazyVerticalGrid(GridCells.Fixed(if (tiles.size <= 1) 1 else 2), modifier = mod, userScrollEnabled = false) {
                itemsIndexed(tiles, key = { _, t -> t.login }) { i, t -> tile(i, t, Modifier, false) }
            }
        }
    }
    val chat = @Composable { mod: Modifier ->
        Column(mod) {
            if (tiles.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrimaryScrollableTabRow(selectedTabIndex = chatTab.coerceIn(0, tiles.lastIndex), edgePadding = 8.dp, modifier = Modifier.weight(1f)) {
                        tiles.forEachIndexed { i, t -> Tab(chatTab == i, { chatTab = i }, text = { Text(t.displayName) }) }
                    }
                    if (landscape && tiles.size > 1) IconButton(onClick = { focusLayout = !focusLayout }) {
                        Icon(if (focusLayout) Icons.Outlined.GridView else Icons.Outlined.ViewAgenda, if (focusLayout) "Grid layout" else "Focus layout")
                    }
                    if (landscape && canAdd) IconButton(onClick = { showAdd = true }) { Icon(Icons.Outlined.Add, "Add a stream") }
                }
                val session = tiles.getOrNull(chatTab.coerceIn(0, tiles.lastIndex))?.chat
                // The selected tab's chat is on screen, so no pings for it.
                session?.let { ses ->
                    androidx.compose.runtime.DisposableEffect(ses) {
                        lol.sinist3r.yip.core.common.VisibleChats.show(ses.channelId)
                        onDispose { lol.sinist3r.yip.core.common.VisibleChats.hide(ses.channelId) }
                    }
                }
                val items: ImmutableList<ChatItem> = session?.items?.collectAsStateWithLifecycle()?.value ?: persistentListOf()
                ChatList(items, options, actions, Modifier.weight(1f))
                session?.let { ses ->
                    LaunchedEffect(ses.channelId, signedIn) { viewModel.loadTwitchEmotes(ses.channelId) }
                    val connection by ses.connection.collectAsStateWithLifecycle()
                    // One composer per chat, so a half-typed message stays with its channel.
                    androidx.compose.runtime.key(ses.channelId) {
                        Composer(
                            enabled = connection.connected && !connection.readOnly,
                            signedIn = signedIn,
                            replyTo = null,
                            sendOnEnter = settings.chat.sendOnEnter,
                            keepOnSend = settings.chat.keepComposerOnSend,
                            tabCompletion = settings.chat.tabCompletion,
                            recent = recent,
                            showRecent = settings.chat.showRecentEmotes,
                            completions = { viewModel.completions(ses, it) },
                            emoteFor = { viewModel.emoteFor(ses.channelId, it) },
                            onSend = { viewModel.send(ses, it) },
                            onCancelReply = {},
                            onOpenEmotes = { showEmotes = true },
                            onSignIn = { host?.openSignIn() },
                            insert = insert,
                            onInserted = { insert = null },
                            commands = lol.sinist3r.yip.core.data.chat.ChatCommands.available(false, false),
                            quickReplies = settings.chat.quickReplies,
                            modifier = Modifier.imePadding(),
                        )
                    }
                }
            }
        }
    }

    val selected = tiles.getOrNull(chatTab.coerceIn(0, (tiles.size - 1).coerceAtLeast(0)))?.chat
    if (showEmotes && selected != null) {
        val cos by viewModel.cosmeticsFor(selected.channelId).collectAsStateWithLifecycle()
        EmotePickerSheet(
            cos, allTwitchEmotes[selected.channelId].orEmpty(), recent, animate,
            onPick = { insert = it.code; RecentEmotes.use(it); showEmotes = false },
            onDismiss = { showEmotes = false },
            favourites = settings.chat.favouriteEmotes,
            onLongPress = { emoteCard = it },
            showLocked = settings.chat.showLockedEmotes,
            mostUsed = lol.sinist3r.yip.core.data.chat.EmoteStats.top(emoteUses, 24),
            channelName = tiles.firstOrNull { it.chat == selected }?.displayName,
        )
    }
    emoteCard?.let { e ->
        EmoteCardSheet(
            e, animate,
            favourite = e.favouriteKey in settings.chat.favouriteEmotes,
            onToggleFavourite = { viewModel.toggleFavouriteEmote(e) },
            onInsert = if (signedIn) ({ insert = e.code; RecentEmotes.use(e); showEmotes = false; emoteCard = null }) else null,
            onDismiss = { emoteCard = null },
            timesUsed = emoteUses[e.favouriteKey]?.count ?: 0,
        )
    }
    if (showAdd) {
        AddStreamSheet(
            exclude = tiles.map { it.login.lowercase() }.toSet(),
            onPick = { viewModel.add(it); showAdd = false; chatTab = tiles.size },
            onDismiss = { showAdd = false },
        )
    }

    if (landscape) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            grid(Modifier.weight(1f).fillMaxHeight())
            chat(Modifier.fillMaxHeight().fillMaxWidth(0.32f))
        }
    } else {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
            TopAppBar(
                title = { Text("Multi-view · ${tiles.size}") },
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (tiles.size > 1) IconButton(onClick = { focusLayout = !focusLayout }) {
                        Icon(if (focusLayout) Icons.Outlined.GridView else Icons.Outlined.ViewAgenda, if (focusLayout) "Grid layout" else "Focus layout")
                    }
                    if (canAdd) IconButton(onClick = { showAdd = true }) { Icon(Icons.Outlined.Add, "Add a stream") }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
            )
            grid(Modifier.fillMaxWidth())
            chat(Modifier.weight(1f))
        }
    }
}
