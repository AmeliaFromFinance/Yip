package dev.yipchat.feature.watch

import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SpeakerNotesOff
import androidx.compose.material.icons.outlined.Toll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import dev.yipchat.core.common.compactCount
import dev.yipchat.core.common.isPowerSaving
import dev.yipchat.core.data.chat.EmoteStats
import dev.yipchat.core.designsystem.chat.ChatActions
import dev.yipchat.core.designsystem.chat.ChatRenderOptions
import dev.yipchat.core.designsystem.component.Avatar
import dev.yipchat.core.designsystem.component.rememberUptime
import dev.yipchat.core.designsystem.util.clipLink
import dev.yipchat.core.designsystem.util.rememberBedtimeActive
import dev.yipchat.core.designsystem.util.shareLink
import dev.yipchat.core.model.AnimateEmotes
import dev.yipchat.core.model.BufferProfile
import dev.yipchat.core.model.ChatItem
import dev.yipchat.core.model.Emote
import dev.yipchat.core.model.LandscapeChat
import dev.yipchat.core.model.LinkPreviewMode
import dev.yipchat.core.model.ModMacro
import dev.yipchat.core.playback.PlaybackSource

interface WatchNavigator {
    fun back()
    fun openChannel(login: String)
    fun openVod(videoId: String, offsetSeconds: Int)
    fun openLink(url: String)
    /** Replaces this screen with multi-view. */
    fun openMultiView(logins: List<String>) {}
    /** Replaces this screen with another live stream. */
    fun openLive(login: String) {}
    /** Replaces this live screen with its VOD at [offsetSeconds]. */
    fun openRewind(videoId: String, offsetSeconds: Int) = openVod(videoId, offsetSeconds)
    fun openWhisper(login: String) {}
    fun openConnectWeb() {}
}

/** Share link for what's playing; VOD links start at the current time. */
private fun shareFor(src: PlaybackSource?): Pair<(Long) -> String, String?>? = when (src) {
    is PlaybackSource.Live -> ({ _: Long -> "https://www.twitch.tv/${src.channelLogin}" }) to src.title.ifBlank { null }
    is PlaybackSource.Vod -> ({ s: Long -> "https://www.twitch.tv/videos/${src.videoId}" + if (s > 5) "?t=" + twitchTime(s) else "" }) to src.title.ifBlank { null }
    is PlaybackSource.ClipSrc -> ({ _: Long -> clipLink(src.slug) }) to src.title.ifBlank { null }
    null -> null
}

/** Twitch's ?t= format, e.g. 1h2m3s. */
internal fun twitchTime(seconds: Long): String {
    val h = seconds / 3600; val m = seconds / 60 % 60; val s = seconds % 60
    return buildString { if (h > 0) append("${h}h"); if (h > 0 || m > 0) append("${m}m"); append("${s}s") }
}

@Composable
internal fun rememberLinkPreviews(mode: LinkPreviewMode, dataSaver: Boolean): Boolean {
    val context = LocalContext.current
    return remember(mode, dataSaver) {
        val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered != false
        when (mode) {
            LinkPreviewMode.OFF -> false
            LinkPreviewMode.WIFI -> !metered
            LinkPreviewMode.ALWAYS -> !(dataSaver && metered)
        }
    }
}

/** Window brightness while bedtime dims the player. */
private const val BEDTIME_BRIGHTNESS = 0.08f

@Composable
internal fun rememberAnimate(mode: AnimateEmotes, reduceMotion: Boolean, dataSaver: Boolean, batterySaver: Boolean = false): Boolean {
    val context = LocalContext.current
    return remember(mode, reduceMotion, dataSaver, batterySaver) {
        val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered != false
        when {
            reduceMotion -> false
            batterySaver && context.isPowerSaving() -> false
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
    val raid by viewModel.raid.collectAsStateWithLifecycle()
    val commands by viewModel.commands.collectAsStateWithLifecycle()
    val emoteUses by viewModel.emoteUses.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val cosmetics by viewModel.cosmeticsState.collectAsStateWithLifecycle()
    val twitchEmotes by viewModel.twitchEmotes.collectAsStateWithLifecycle()
    val recent by viewModel.recentEmotes.collectAsStateWithLifecycle()
    val isMod by viewModel.isModerator.collectAsStateWithLifecycle()
    val inLater by viewModel.inWatchLater.collectAsStateWithLifecycle()
    val macros = remember(settings.chat.modMacros) { settings.chat.modMacros.mapNotNull(ModMacro::parse) }
    val points by viewModel.pointsBalance.collectAsStateWithLifecycle()
    val userCard by viewModel.userCard.collectAsStateWithLifecycle()
    val replyTo by viewModel.replyTo.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val host = LocalWatchHost.current

    var showQuality by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showAddStream by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showDownload by remember { mutableStateOf(false) }
    var landscapeChatOpen by rememberSaveable { mutableStateOf(settings.player.landscapeChat != LandscapeChat.HIDDEN) }
    var emoteCard by remember { mutableStateOf<Emote?>(null) }
    var showEmotes by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ChatItem.Message?>(null) }
    var insert by remember { mutableStateOf<String?>(null) }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val twoPane = LocalConfiguration.current.let { isTablet(it) && it.screenWidthDp >= 840 }
    val animate = rememberAnimate(settings.chat.animateEmotes, settings.appearance.reduceMotion, settings.data.dataSaver, settings.data.batterySaver)
    val linkPreviews = rememberLinkPreviews(settings.chat.linkPreviews, settings.data.dataSaver)
    val options = remember(settings.chat, settings.thirdParty, animate, linkPreviews) { ChatRenderOptions(settings.chat, settings.thirdParty, animate, linkPreviews) }
    val actions = remember {
        object : ChatActions {
            override fun onUser(login: String, userId: String?) {
                if (viewModel.settings.value.chat.tapNameToMention) insert = "@$login" else viewModel.openUser(login)
            }
            override fun onEmote(emote: Emote) { insert = emote.code }
            override fun onEmoteLongPress(emote: Emote) { emoteCard = emote }
            override fun onMessageLongPress(message: ChatItem.Message) { menuFor = message }
            override fun onThread(message: ChatItem.Message) = viewModel.openThread(message)
            override fun onLink(url: String) = nav.openLink(url)
            override fun onAutoMod(item: ChatItem.AutoMod, allow: Boolean) { viewModel.resolveAutoMod(item, allow) }
        }
    }

    val chatOnly = viewModel.kind == WatchKind.CHAT
    DisposableEffect(settings.player.autoPip, settings.player.keepScreenOn, chatOnly) {
        if (!chatOnly) host?.setWatching(true, settings.player.autoPip, settings.player.keepScreenOn)
        onDispose { host?.setWatching(false, false, false) }
    }
    val chatUser = info.user
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(chatUser?.id, lifecycle, inPip) {
        val user = chatUser ?: return@DisposableEffect onDispose { }
        var shown = false
        fun set(on: Boolean) { if (on != shown) { shown = on; viewModel.chatOnScreen(user.id, user.login, on) } }
        val observer = LifecycleEventObserver { _, _ ->
            set(!inPip && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); set(false) }
    }
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.rewindError.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    LaunchedEffect(settings.player.sleepTimerMinutes) {
        if (!chatOnly && settings.player.sleepTimerMinutes > 0 && playState.sleepAtMillis == null && !playState.sleepAtEnd) viewModel.playback.setSleepTimer(settings.player.sleepTimerMinutes)
    }
    // Bedtime: dim the screen and start a sleep timer if none is set.
    val bedtime = settings.general.bedtime
    val bedtimeActive = rememberBedtimeActive(bedtime)
    LaunchedEffect(bedtimeActive) {
        if (bedtimeActive && !chatOnly && bedtime.sleepTimerMinutes > 0 && playState.sleepAtMillis == null && !playState.sleepAtEnd) {
            viewModel.playback.setSleepTimer(bedtime.sleepTimerMinutes)
        }
    }
    val dim = bedtimeActive && bedtime.dimPlayer
    DisposableEffect(dim) {
        val window = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()?.window
        if (dim && window != null) {
            val lp = window.attributes
            lp.screenBrightness = BEDTIME_BRIGHTNESS
            window.attributes = lp
        }
        onDispose {
            if (dim && window != null) {
                val lp = window.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }
    }

    val src = playState.source
    val isLive = viewModel.kind == WatchKind.LIVE
    val duration = (src as? PlaybackSource.Vod)?.durationSeconds ?: 0L
    val title = info.title.ifBlank { info.user?.displayName.orEmpty() }
    val subtitle = listOfNotNull(info.user?.displayName, info.stream?.gameName).joinToString(" · ")

    val chatEnabled = viewModel.kind != WatchKind.CLIP
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
            extras = info.extras,
            onShare = shareFor(playState.source)?.let { (url, t) -> { context.shareLink(url(viewModel.playback.player.currentPosition / 1000), t) } },
            speeds = when (viewModel.kind) {
                WatchKind.CLIP -> (listOf(0.25f) + settings.player.vodSpeeds).distinct().sorted()
                WatchKind.VOD -> settings.player.vodSpeeds
                else -> emptyList()
            },
            onSpeed = viewModel.playback::setSpeed,
            inWatchLater = if (viewModel.kind == WatchKind.VOD || viewModel.kind == WatchKind.CLIP) inLater else null,
            onWatchLater = viewModel::toggleWatchLater,
            onRewind = if (isLive && info.stream != null) ({ back -> viewModel.rewind(back) { id, off -> nav.openRewind(id, off) } }) else null,
            onGoLive = info.extras.liveLogin?.let { login -> { nav.openLive(login) } },
            inPip = inPip,
            showAdBanner = settings.adBlock.showAdBreakBanner,
            onBack = nav::back,
            onQuality = { showQuality = true },
            stats = viewModel.playback.stats,
            onToggleMute = viewModel.playback::toggleMute,
            onMultiView = if (isLive && info.user != null) ({ showAddStream = true }) else null,
            onClip = if (signedIn && ((isLive && info.stream != null) || viewModel.kind == WatchKind.VOD)) ({ viewModel.createClip() }) else null,
            onCatchUp = if (isLive) ({ viewModel.playback.catchUp() }) else null,
            onToggleChat = if (landscape && !twoPane && chatEnabled) ({ landscapeChatOpen = !landscapeChatOpen }) else null,
            chatOpen = landscapeChatOpen,
            controlsEndInset = if (landscape && !twoPane && chatEnabled && landscapeChatOpen && settings.player.landscapeChat != LandscapeChat.SIDE)
                (LocalConfiguration.current.screenWidthDp * settings.player.overlayChatWidthFraction.coerceIn(0.2f, 0.6f)).dp else 0.dp,
            onDownload = if (!isLive && viewModel.downloadId != null && playState.qualities.isNotEmpty()) ({ showDownload = true }) else null,
            onRetry = { if (info.error != null) viewModel.load() else viewModel.playback.retry() },
            modifier = mod,
        )
    }

    val chatPane = @Composable { mod: Modifier, overlay: Boolean ->
        Column(mod) {
            if (!overlay) RoomStateBar(room, connection, points)
            if (!overlay && !events.isEmpty) {
                val voted by viewModel.votedPolls.collectAsStateWithLifecycle()
                val connected by viewModel.webConnected.collectAsStateWithLifecycle()
                EventBanners(
                    events,
                    actions = if (connected) EventActions(voted, points, viewModel::votePoll, { pr, o, n -> viewModel.predict(pr, o, n) }) else null,
                )
            }
            val pin by viewModel.pinned.collectAsStateWithLifecycle()
            var hiddenPin by rememberSaveable { mutableStateOf<String?>(null) }
            pin?.takeIf { it.id != hiddenPin }?.let { p ->
                PinnedBanner(p, canUnpin = isMod, onUnpin = { viewModel.unpin(p) }, onHide = { hiddenPin = p.id }, onUser = viewModel::openUser)
            }
            raid?.let { r -> RaidBanner(r, onGo = { viewModel.dismissRaid(); nav.openLive(r.login) }, onDismiss = viewModel::dismissRaid) }
            val translations by viewModel.translations.collectAsStateWithLifecycle()
            val ctx = LocalContext.current
            ChatList(chat, options, actions, Modifier.weight(1f), translations, onTranslationFix = { openTranslationSettings(ctx, it) })
            if (isLive || chatOnly) {
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
                    commands = commands,
                    quickReplies = settings.chat.quickReplies,
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
            ChatOnlyHeader(info, nav, onFilters = { showFilters = true }, onSearch = { showSearch = true })
            HorizontalDivider()
            info.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            chatPane(Modifier.weight(1f), false)
        }
    } else if (twoPane && host?.isFullscreenRequested() != true) {
        // Tablets and unfolded foldables in landscape: player and chat side by side, bars visible.
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.background(Color.Black).statusBarsPadding()) { player(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
                ChannelRow(info, nav, onFilters = { showFilters = true }, onSearch = { showSearch = true }, onRewards = if (isLive) viewModel::openRewards else null)
                if (!chatEnabled) info.clipVodId?.let { vod ->
                    TextButton(onClick = { nav.openVod(vod, info.clipVodOffset ?: 0) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Watch the full broadcast from this moment") }
                }
            }
            if (chatEnabled) {
                VerticalDivider()
                chatPane(Modifier.width(380.dp).fillMaxHeight().safeDrawingPadding(), false)
            }
        }
    } else if (landscape) {
        // Theatre mode: chat in a collapsible panel with its own chat box, beside or over the video.
        val panelWidth = (LocalConfiguration.current.screenWidthDp * settings.player.overlayChatWidthFraction.coerceIn(0.2f, 0.6f)).dp
        val showPanel = chatEnabled && landscapeChatOpen
        val panel = @Composable { mod: Modifier ->
            Column(mod) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Chat", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showSearch = true }) { Icon(Icons.Outlined.Search, "Search chat") }
                    IconButton(onClick = { landscapeChatOpen = false }) {
                        Icon(Icons.Outlined.SpeakerNotesOff, "Hide chat")
                    }
                }
                chatPane(Modifier.weight(1f), false)
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (settings.player.landscapeChat == LandscapeChat.SIDE) {
                Row(Modifier.fillMaxSize()) {
                    player(Modifier.weight(1f).fillMaxHeight())
                    AnimatedVisibility(showPanel) {
                        panel(Modifier.width(panelWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surface).safeDrawingPadding())
                    }
                }
            } else {
                player(Modifier.fillMaxSize())
                AnimatedVisibility(showPanel, modifier = Modifier.align(Alignment.TopEnd)) {
                    // Theme surface behind the text, never too see-through to read.
                    panel(
                        Modifier.width(panelWidth).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = settings.player.overlayChatOpacity.coerceIn(MIN_OVERLAY_OPACITY, 1f)))
                            .safeDrawingPadding(),
                    )
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(Modifier.background(Color.Black).statusBarsPadding()) { player(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
            ChannelRow(info, nav, onFilters = { showFilters = true }, onSearch = { showSearch = true }, onRewards = if (isLive) viewModel::openRewards else null)
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

    val clip by viewModel.clip.collectAsStateWithLifecycle()
    clip?.let { ClipSheet(it, onDismiss = viewModel::dismissClip, onCreateLive = viewModel::createLiveClip, onCreateVod = viewModel::createVodClip, onPreviewVod = viewModel::previewVodClip) }
    if (showAddStream) info.user?.let { u ->
        AddStreamSheet(exclude = setOf(u.login.lowercase()), onPick = { showAddStream = false; nav.openMultiView(listOf(u.login, it)) }, onDismiss = { showAddStream = false })
    }
    if (showDownload) {
        val items by viewModel.downloadItems.collectAsStateWithLifecycle()
        DownloadSheet(
            playState.qualities, duration, items.firstOrNull { it.id == viewModel.downloadId },
            onPick = { viewModel.download(it) }, onRemove = viewModel::removeDownload, onDismiss = { showDownload = false },
        )
    }
    val rewards by viewModel.rewards.collectAsStateWithLifecycle()
    rewards?.let { r ->
        RewardsSheet(r, onRedeem = { reward, text -> viewModel.redeem(reward, text) }, onConnect = { viewModel.closeRewards(); nav.openConnectWeb() }, onDismiss = viewModel::closeRewards)
    }
    val thread by viewModel.thread.collectAsStateWithLifecycle()
    thread?.let { t -> ThreadSheet(t, options, actions, onReply = { viewModel.setReply(t.last()); viewModel.closeThread() }, onDismiss = viewModel::closeThread) }
    if (showSearch) ChatSearchSheet(chat, options, actions, onDismiss = { showSearch = false })
    if (showFilters) ChatFiltersSheet(settings.filters, viewModel::updateFilters, onDismiss = { showFilters = false })
    if (showQuality) QualitySheet(
        qualities = playState.qualities,
        selected = playState.selected,
        auto = playState.auto,
        showAuto = viewModel.kind != WatchKind.CLIP,
        speeds = settings.player.vodSpeeds,
        speed = playState.speed,
        showSpeed = !isLive,
        sleepAtMillis = playState.sleepAtMillis,
        sleepAtEnd = playState.sleepAtEnd,
        sleepAtEndLabel = if (isLive) "End of stream" else "End of video",
        onQuality = { viewModel.playback.selectQuality(it); showQuality = false },
        onSpeed = viewModel.playback::setSpeed,
        onSleep = viewModel.playback::setSleepTimer,
        onDismiss = { showQuality = false },
        lowLatency = if (isLive) settings.player.bufferProfile == BufferProfile.LOW_LATENCY else null,
        onLowLatency = viewModel::setLowLatency,
        audioOnly = if (playState.qualities.any { it.isAudioOnly }) playState.audioOnly else null,
        onAudioOnly = viewModel.playback::toggleAudioOnly,
        volume = if (settings.player.perChannelMemory) playState.volume else null,
        onVolume = viewModel.playback::setVolume,
    )
    if (showEmotes) EmotePickerSheet(
        cosmetics, twitchEmotes, recent, animate,
        onPick = { insert = it.code; viewModel.useEmote(it); showEmotes = false },
        onDismiss = { showEmotes = false },
        favourites = settings.chat.favouriteEmotes,
        onLongPress = { emoteCard = it },
        showLocked = settings.chat.showLockedEmotes,
        mostUsed = EmoteStats.top(emoteUses, 24),
        channelName = info.user?.displayName,
    )
    emoteCard?.let { e ->
        EmoteCardSheet(
            e, animate,
            favourite = e.favouriteKey in settings.chat.favouriteEmotes,
            onToggleFavourite = { viewModel.toggleFavouriteEmote(e) },
            onInsert = if (signedIn) ({ insert = e.code; viewModel.useEmote(e); showEmotes = false }) else null,
            onDismiss = { emoteCard = null },
            timesUsed = emoteUses[e.favouriteKey]?.count ?: 0,
        )
    }
    userCard?.let { card ->
        UserCardSheet(
            card, options, isMod,
            onMention = { insert = "@$it"; viewModel.closeUser() },
            onOpenChannel = { viewModel.closeUser(); nav.openChannel(it) },
            onBlock = { viewModel.block(it); viewModel.closeUser() },
            onMod = { a, id -> viewModel.moderate(a, id) },
            onDismiss = viewModel::closeUser,
            note = settings.chat.userNotes[card.login.lowercase()],
            onSaveNote = { viewModel.saveNote(card.login, it) },
            reasons = settings.chat.modReasons,
            onModWithReason = { a, id, r -> viewModel.moderate(a, id, reason = r) },
            onLoadLogs = if (isLive || chatOnly) viewModel::loadUserLogs else null,
            onWhisper = if (signedIn) ({ viewModel.closeUser(); nav.openWhisper(it) }) else null,
            macros = macros,
            onMacro = { m, id -> viewModel.moderate(m, id) },
            hidden = card.login.lowercase() in settings.filters.hiddenUsers,
            onHidden = { hide ->
                viewModel.updateFilters { f -> f.copy(hiddenUsers = if (hide) (f.hiddenUsers + card.login.lowercase()).distinct() else f.hiddenUsers - card.login.lowercase()) }
            },
        )
    }
    menuFor?.let { m ->
        MessageMenuSheet(
            m, isMod, signedIn,
            onReply = { viewModel.setReply(m) },
            onMod = { viewModel.moderate(it, m.user.id, m.id) },
            onUser = { viewModel.openUser(m.user.login) },
            onDismiss = { menuFor = null },
            onHideUser = { viewModel.updateFilters { f -> f.copy(hiddenUsers = (f.hiddenUsers + m.user.login.lowercase()).distinct()) } },
            onMarkBot = { viewModel.updateFilters { f -> f.copy(botNames = (f.botNames + m.user.login.lowercase()).distinct(), hideBots = true) } },
            onTranslate = if (viewModel.canTranslate) ({ viewModel.translate(m) }) else null,
            onThread = if (m.threadId != null) ({ viewModel.openThread(m) }) else null,
            macros = macros,
            onMacro = { mac -> viewModel.moderate(mac, m.user.id) },
            onPin = if (isMod && (isLive || chatOnly) && !m.isHistory) ({ secs -> viewModel.pin(m, secs) }) else null,
        )
    }
}

@Composable
private fun ChatOnlyHeader(info: WatchInfo, nav: WatchNavigator, onFilters: () -> Unit, onSearch: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = nav::back) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        val user = info.user
        if (user != null) {
            Avatar(user.profileImageUrl, user.displayName, 32.dp, live = info.stream != null)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(user.displayName, style = MaterialTheme.typography.titleSmall)
                Text(if (info.stream != null) "Live" else "Offline chat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Spacer(Modifier.weight(1f))
        IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "Search chat") }
        IconButton(onClick = onFilters) { Icon(Icons.Outlined.FilterAlt, "Chat filters") }
    }
}

@Composable
private fun ChannelRow(info: WatchInfo, nav: WatchNavigator, onFilters: () -> Unit, onSearch: () -> Unit, onRewards: (() -> Unit)? = null) {
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
        onRewards?.let { IconButton(onClick = it) { Icon(Icons.Outlined.Toll, "Channel points and rewards") } }
        IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "Search chat") }
        IconButton(onClick = onFilters) { Icon(Icons.Outlined.FilterAlt, "Chat filters") }
        TextButton(onClick = { nav.openChannel(user.login) }) { Text("Channel") }
    }
}


/** Tablets and unfolded foldables: the short side is at least 600dp. */
fun isTablet(config: Configuration): Boolean = config.smallestScreenWidthDp >= 600

private const val MIN_OVERLAY_OPACITY = 0.6f
