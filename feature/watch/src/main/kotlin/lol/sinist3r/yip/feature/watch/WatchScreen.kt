package lol.sinist3r.yip.feature.watch

import android.content.res.Configuration
import android.net.ConnectivityManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import lol.sinist3r.yip.core.common.compactCount
import lol.sinist3r.yip.core.designsystem.chat.ChatActions
import lol.sinist3r.yip.core.designsystem.chat.ChatRenderOptions
import lol.sinist3r.yip.core.designsystem.component.Avatar
import lol.sinist3r.yip.core.designsystem.component.rememberUptime
import lol.sinist3r.yip.core.model.AnimateEmotes
import lol.sinist3r.yip.core.model.ChatItem
import lol.sinist3r.yip.core.model.Emote
import lol.sinist3r.yip.core.model.LandscapeChat
import lol.sinist3r.yip.core.playback.PlaybackSource

interface WatchNavigator {
    fun back()
    fun openChannel(login: String)
    fun openVod(videoId: String, offsetSeconds: Int)
    fun openLink(url: String)
}

@Composable
internal fun rememberAnimate(mode: AnimateEmotes, reduceMotion: Boolean, dataSaver: Boolean): Boolean {
    val context = LocalContext.current
    return remember(mode, reduceMotion, dataSaver) {
        val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered != false
        when {
            reduceMotion -> false
            dataSaver && metered -> false
            mode == AnimateEmotes.ALWAYS -> true
            mode == AnimateEmotes.NEVER -> false
            else -> !metered
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun WatchScreen(nav: WatchNavigator, inPip: Boolean, viewModel: WatchViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playState by viewModel.playbackState.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val room by viewModel.room.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val cosmetics by viewModel.cosmeticsState.collectAsStateWithLifecycle()
    val twitchEmotes by viewModel.twitchEmotes.collectAsStateWithLifecycle()
    val recent by viewModel.recentEmotes.collectAsStateWithLifecycle()
    val isMod by viewModel.isModerator.collectAsStateWithLifecycle()
    val points by viewModel.pointsBalance.collectAsStateWithLifecycle()
    val userCard by viewModel.userCard.collectAsStateWithLifecycle()
    val replyTo by viewModel.replyTo.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val host = LocalWatchHost.current

    var showQuality by remember { mutableStateOf(false) }
    var showEmotes by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ChatItem.Message?>(null) }
    var insert by remember { mutableStateOf<String?>(null) }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val animate = rememberAnimate(settings.chat.animateEmotes, settings.appearance.reduceMotion, settings.data.dataSaver)
    val options = remember(settings.chat, settings.thirdParty, animate) { ChatRenderOptions(settings.chat, settings.thirdParty, animate) }
    val actions = remember {
        object : ChatActions {
            override fun onUser(login: String, userId: String?) {
                if (viewModel.settings.value.chat.tapNameToMention) insert = "@$login" else viewModel.openUser(login)
            }
            override fun onEmote(emote: Emote) { insert = emote.code }
            override fun onMessageLongPress(message: ChatItem.Message) { menuFor = message }
            override fun onLink(url: String) = nav.openLink(url)
        }
    }

    val chatOnly = viewModel.kind == WatchKind.CHAT
    DisposableEffect(settings.player.autoPip, settings.player.keepScreenOn, chatOnly) {
        if (!chatOnly) host?.setWatching(true, settings.player.autoPip, settings.player.keepScreenOn)
        onDispose { host?.setWatching(false, false, false) }
    }
    val context = LocalContext.current
    val lastMention = chat.lastOrNull() as? ChatItem.Message
    LaunchedEffect(lastMention?.id) {
        if (lastMention != null && !lastMention.isHistory && lastMention.highlight == lol.sinist3r.yip.core.model.HighlightKind.MENTION && settings.chat.mentionVibrate) {
            context.getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
    LaunchedEffect(settings.player.sleepTimerMinutes) {
        if (!chatOnly && settings.player.sleepTimerMinutes > 0 && playState.sleepAtMillis == null) viewModel.playback.setSleepTimer(settings.player.sleepTimerMinutes)
    }

    val src = playState.source
    val isLive = viewModel.kind == WatchKind.LIVE
    val duration = (src as? PlaybackSource.Vod)?.durationSeconds ?: 0L
    val title = info.title.ifBlank { info.user?.displayName.orEmpty() }
    val subtitle = listOfNotNull(info.user?.displayName, info.stream?.gameName).joinToString(" · ")

    val player = @Composable { mod: Modifier ->
        PlayerArea(
            player = viewModel.playback.player,
            state = playState,
            settings = settings.player,
            title = title,
            subtitle = subtitle,
            isLive = isLive,
            durationSeconds = duration,
            chapters = info.chapters,
            inPip = inPip,
            showAdBanner = settings.adBlock.showAdBreakBanner,
            onBack = nav::back,
            onQuality = { showQuality = true },
            onRetry = { if (info.error != null) viewModel.load() else viewModel.playback.retry() },
            modifier = mod,
        )
    }

    val chatEnabled = viewModel.kind != WatchKind.CLIP
    val chatPane = @Composable { mod: Modifier, overlay: Boolean ->
        Column(mod) {
            if (!overlay) RoomStateBar(room, connection, points)
            ChatList(chat, options, actions, Modifier.weight(1f))
            if ((isLive || chatOnly) && !overlay) {
                Composer(
                    enabled = connection.connected && !connection.readOnly,
                    signedIn = signedIn,
                    replyTo = replyTo,
                    sendOnEnter = settings.chat.sendOnEnter,
                    keepOnSend = settings.chat.keepComposerOnSend,
                    tabCompletion = settings.chat.tabCompletion,
                    recent = recent,
                    showRecent = settings.chat.showRecentEmotes,
                    completions = viewModel::completions,
                    emoteFor = viewModel::emoteFor,
                    onSend = viewModel::send,
                    onCancelReply = { viewModel.setReply(null) },
                    onOpenEmotes = { showEmotes = true },
                    onSignIn = { host?.openSignIn() },
                    insert = insert,
                    onInserted = { insert = null },
                )
            }
        }
    }

    if (inPip && !chatOnly) {
        player(Modifier.fillMaxSize())
        return
    }

    if (chatOnly) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            ChatOnlyHeader(info, nav)
            HorizontalDivider()
            info.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            chatPane(Modifier.weight(1f), false)
        }
    } else if (landscape) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                !chatEnabled || settings.player.landscapeChat == LandscapeChat.HIDDEN -> player(Modifier.fillMaxSize())
                settings.player.landscapeChat == LandscapeChat.SIDE -> Row(Modifier.fillMaxSize()) {
                    player(Modifier.weight(1f).fillMaxHeight())
                    chatPane(Modifier.width(340.dp).fillMaxHeight().background(MaterialTheme.colorScheme.background).safeDrawingPadding(), false)
                }
                else -> {
                    player(Modifier.fillMaxSize())
                    chatPane(
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(settings.player.overlayChatWidthFraction.coerceIn(0.2f, 0.6f))
                            .background(Color.Black.copy(alpha = settings.player.overlayChatOpacity)).safeDrawingPadding(),
                        true,
                    )
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(Modifier.background(Color.Black).statusBarsPadding()) { player(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
            ChannelRow(info, nav)
            HorizontalDivider()
            if (chatEnabled) {
                chatPane(Modifier.weight(1f), false)
            } else {
                Column(Modifier.padding(16.dp)) {
                    Text(info.title, style = MaterialTheme.typography.titleMedium)
                    info.clipVodId?.let { vod ->
                        TextButton(onClick = { nav.openVod(vod, info.clipVodOffset ?: 0) }) { Text("Watch the full broadcast from this moment") }
                    }
                }
            }
        }
    }

    if (showQuality) QualitySheet(
        qualities = playState.qualities,
        selected = playState.selected,
        auto = playState.auto,
        showAuto = viewModel.kind != WatchKind.CLIP,
        speeds = settings.player.vodSpeeds,
        speed = playState.speed,
        showSpeed = !isLive,
        sleepAtMillis = playState.sleepAtMillis,
        onQuality = { viewModel.playback.selectQuality(it); showQuality = false },
        onSpeed = viewModel.playback::setSpeed,
        onSleep = viewModel.playback::setSleepTimer,
        onDismiss = { showQuality = false },
    )
    if (showEmotes) EmotePickerSheet(cosmetics, twitchEmotes, recent, animate, onPick = { insert = it.code; viewModel.useEmote(it); showEmotes = false }, onDismiss = { showEmotes = false })
    userCard?.let { card ->
        UserCardSheet(
            card, options, isMod,
            onMention = { insert = "@$it"; viewModel.closeUser() },
            onOpenChannel = { viewModel.closeUser(); nav.openChannel(it) },
            onBlock = { viewModel.block(it); viewModel.closeUser() },
            onMod = { a, id -> viewModel.moderate(a, id) },
            onDismiss = viewModel::closeUser,
        )
    }
    menuFor?.let { m ->
        MessageMenuSheet(
            m, isMod, signedIn,
            onReply = { viewModel.setReply(m) },
            onMod = { viewModel.moderate(it, m.user.id, m.id) },
            onUser = { viewModel.openUser(m.user.login) },
            onDismiss = { menuFor = null },
        )
    }
}

@Composable
private fun ChatOnlyHeader(info: WatchInfo, nav: WatchNavigator) {
    Row(Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.IconButton(onClick = nav::back) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        val user = info.user
        if (user != null) {
            Avatar(user.profileImageUrl, user.displayName, 32.dp, live = info.stream != null)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(user.displayName, style = MaterialTheme.typography.titleSmall)
                Text(if (info.stream != null) "Live" else "Offline chat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ChannelRow(info: WatchInfo, nav: WatchNavigator) {
    val user = info.user ?: return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user.profileImageUrl, user.displayName, 36.dp, live = info.stream != null)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(user.displayName, style = MaterialTheme.typography.titleSmall)
            Text(info.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            info.stream?.let { s ->
                val up = rememberUptime(s)
                Text("${s.viewerCount.compactCount()} watching · ${s.gameName.orEmpty()} · $up", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        TextButton(onClick = { nav.openChannel(user.login) }) { Text("Channel") }
    }
}
