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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.data.chat.ChatRepository
import lol.sinist3r.yip.core.data.chat.ChatSession
import lol.sinist3r.yip.core.data.playback.PlaybackRepository
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.data.twitch.TwitchRepository
import lol.sinist3r.yip.core.designsystem.chat.ChatActions
import lol.sinist3r.yip.core.designsystem.chat.ChatRenderOptions
import lol.sinist3r.yip.core.designsystem.component.Pill
import lol.sinist3r.yip.core.model.ChatItem
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
    settingsRepo: SettingsRepository,
) : ViewModel() {
    val settings = settingsRepo.settings
    private val _tiles = MutableStateFlow<List<MultiTile>>(emptyList())
    val tiles: StateFlow<List<MultiTile>> = _tiles.asStateFlow()
    private val _focused = MutableStateFlow(0)
    val focused = _focused.asStateFlow()

    init {
        main.stop()
        val logins = savedState.get<String>("logins").orEmpty().split(',').filter { it.isNotBlank() }.distinct()
        val max = settings.value.experimental.multiViewMaxStreams.coerceIn(2, 4)
        logins.take(max).forEach(::add)
    }

    fun add(login: String) {
        if (_tiles.value.any { it.login.equals(login, true) }) return
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
    val animate = rememberAnimate(settings.chat.animateEmotes, settings.appearance.reduceMotion, settings.data.dataSaver)
    val options = remember(settings.chat, settings.thirdParty, animate) { ChatRenderOptions(settings.chat, settings.thirdParty, animate) }
    val actions = remember { object : ChatActions { override fun onLink(url: String) = nav.openLink(url) } }
    val host = LocalWatchHost.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        host?.setWatching(true, autoPip = false, keepScreenOn = true)
        onDispose { host?.setWatching(false, false, false) }
    }

    val grid = @Composable { mod: Modifier ->
        LazyVerticalGrid(GridCells.Fixed(if (tiles.size <= 1) 1 else 2), modifier = mod, userScrollEnabled = false) {
            itemsIndexed(tiles, key = { _, t -> t.login }) { i, t ->
                Box(
                    Modifier.aspectRatio(16f / 9f).background(Color.Black)
                        .border(2.dp, if (i == focused) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { viewModel.focus(i); chatTab = i },
                ) {
                    t.player?.let { PlayerSurface(player = it, surfaceType = SURFACE_TYPE_TEXTURE_VIEW, modifier = Modifier.fillMaxSize()) }
                    Pill(t.displayName + if (i == focused) " · audio" else "", Modifier.align(Alignment.TopStart).padding(6.dp))
                    t.error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center)) }
                    IconButton(onClick = { viewModel.remove(i) }, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Outlined.Close, "Remove", tint = Color.White) }
                }
            }
        }
    }
    val chat = @Composable { mod: Modifier ->
        Column(mod) {
            if (tiles.isNotEmpty()) {
                PrimaryScrollableTabRow(selectedTabIndex = chatTab.coerceIn(0, tiles.lastIndex), edgePadding = 8.dp) {
                    tiles.forEachIndexed { i, t -> Tab(chatTab == i, { chatTab = i }, text = { Text(t.displayName) }) }
                }
                val session = tiles.getOrNull(chatTab)?.chat
                // The selected tab's chat is on screen, so no pings for it.
                session?.let { ses ->
                    androidx.compose.runtime.DisposableEffect(ses) {
                        lol.sinist3r.yip.core.common.VisibleChats.show(ses.channelId)
                        onDispose { lol.sinist3r.yip.core.common.VisibleChats.hide(ses.channelId) }
                    }
                }
                val items: ImmutableList<ChatItem> = session?.items?.collectAsStateWithLifecycle()?.value ?: persistentListOf()
                ChatList(items, options, actions, Modifier.weight(1f))
            }
        }
    }

    if (landscape) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            grid(Modifier.weight(1f).fillMaxHeight())
            chat(Modifier.fillMaxHeight().fillMaxWidth(0.32f))
        }
    } else {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            TopAppBar(
                title = { Text("Multi-view · ${tiles.size}") },
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
            )
            grid(Modifier.fillMaxWidth())
            chat(Modifier.weight(1f))
        }
    }
}
