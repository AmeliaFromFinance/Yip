package lol.sinist3r.yip.navigation

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import lol.sinist3r.yip.BuildConfig
import lol.sinist3r.yip.MainActivity
import lol.sinist3r.yip.core.data.follow.FollowResult
import lol.sinist3r.yip.core.data.history.HistoryEntry
import lol.sinist3r.yip.core.database.HistoryKind
import lol.sinist3r.yip.core.model.AppSettings
import lol.sinist3r.yip.core.model.Clip
import lol.sinist3r.yip.core.model.LinkOpening
import lol.sinist3r.yip.core.model.LiveStream
import lol.sinist3r.yip.core.model.StartScreen
import lol.sinist3r.yip.core.model.Video
import lol.sinist3r.yip.core.playback.PlaybackSource
import lol.sinist3r.yip.feature.browse.BrowseNavigator
import lol.sinist3r.yip.feature.browse.BrowseScreen
import lol.sinist3r.yip.feature.browse.CategoryScreen
import lol.sinist3r.yip.feature.browse.ChannelScreen
import lol.sinist3r.yip.feature.browse.FollowingScreen
import lol.sinist3r.yip.feature.browse.SearchScreen
import lol.sinist3r.yip.feature.settings.AboutScreen
import lol.sinist3r.yip.feature.settings.AccountsScreen
import lol.sinist3r.yip.feature.settings.ConnectWebScreen
import lol.sinist3r.yip.feature.settings.LoginScreen
import lol.sinist3r.yip.feature.settings.LogsScreen
import lol.sinist3r.yip.feature.settings.SettingsNavigator
import lol.sinist3r.yip.feature.settings.SettingsPage
import lol.sinist3r.yip.feature.settings.SettingsRoot
import lol.sinist3r.yip.feature.watch.MultiViewScreen
import lol.sinist3r.yip.feature.watch.WatchNavigator
import lol.sinist3r.yip.feature.watch.WatchScreen

private data class TopLevel(val route: Any, val label: String, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(FollowingRoute, "Following", Icons.Outlined.Favorite),
    TopLevel(BrowseRoute, "Browse", Icons.Outlined.Explore),
    TopLevel(SearchRoute, "Search", Icons.Outlined.Search),
    TopLevel(SettingsRoute, "Settings", Icons.Outlined.Settings),
)

@OptIn(UnstableApi::class)
@Composable
fun YipApp(activity: MainActivity, settings: AppSettings, inPip: Boolean) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val multiQueue = remember { mutableStateListOf<String>() }
    val playState by activity.playback.state.collectAsStateWithLifecycle()
    // Chat-only screens count as "watching" for the nav bar, but keep the mini player visible.
    val chatOnly = destination?.hasRoute<WatchRoute>() == true && runCatching { backStack?.toRoute<WatchRoute>()?.kind == "CHAT" }.getOrDefault(false)
    val onWatch = destination?.hasRoute<WatchRoute>() == true || destination?.hasRoute<MultiViewRoute>() == true
    val isTopLevel = topLevel.any { t -> destination?.hierarchy?.any { it.hasRoute(t.route::class) } == true }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var exitArmed by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(settings.notifications.enabled) {
        if (settings.notifications.enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    // The PiP window is landscape-shaped; that isn't a rotation, so leave fullscreen alone.
    // Tablets keep player and chat side by side in landscape, so rotating doesn't go fullscreen there.
    val tablet = lol.sinist3r.yip.feature.watch.isTablet(LocalConfiguration.current)
    LaunchedEffect(onWatch, landscape, inPip) { if (onWatch && !inPip && !tablet) activity.setFullscreen(landscape && activity.isFullscreen()) }
    activity.openSignInRequest = { nav.navigate(LoginRoute) }

    fun openLink(url: String) {
        val uri = Uri.parse(url)
        if (uri.host?.endsWith("twitch.tv") == true && handleTwitchUri(uri, nav)) return
        when (settings.general.openLinks) {
            LinkOpening.BROWSER -> activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            else -> CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, uri)
        }
    }

    LaunchedEffect(Unit) {
        activity.deepLinks.collect { uri ->
            // yip://whisper/<login> from whisper notifications.
            if (uri.scheme == "yip" && uri.host == "whisper") uri.lastPathSegment?.let { nav.navigate(WhisperRoute(it)) } else handleTwitchUri(uri, nav)
        }
    }

    val browseNav = remember {
        object : BrowseNavigator {
            override fun openStream(stream: LiveStream) = nav.navigate(WatchRoute("LIVE", stream.userLogin))
            override fun openChannel(login: String) = nav.navigate(ChannelRoute(login))
            override fun openCategory(id: String, name: String) = nav.navigate(CategoryRoute(id, name))
            override fun openVideo(video: Video) = nav.navigate(WatchRoute("VOD", video.id))
            override fun openClip(clip: Clip) = nav.navigate(WatchRoute("CLIP", clip.id, clip.vodOffset ?: -1, null, clip.title, clip.videoId))
            override fun openHistory(entry: HistoryEntry) = when (entry.kind) {
                HistoryKind.LIVE -> nav.navigate(WatchRoute("LIVE", entry.channelLogin))
                // Pick up exactly where it was left.
                HistoryKind.VOD -> nav.navigate(WatchRoute("VOD", entry.contentId, entry.positionSeconds.toInt()))
                HistoryKind.CLIP -> nav.navigate(WatchRoute("CLIP", entry.contentId))
            }
            override fun openLogin() = nav.navigate(LoginRoute)
            override fun openSearch() = nav.navigate(SearchRoute)
            override fun openChat(login: String) = nav.navigate(WatchRoute("CHAT", login))
            override fun openStreamManager() = nav.navigate(StreamManagerRoute)
            override fun openInbox() = nav.navigate(InboxRoute)
            override fun openLibrary() = nav.navigate(LibraryRoute())
            override fun openManageFollows() = nav.navigate(ManageFollowsRoute)
            override fun addToMultiView(login: String) {
                if (login !in multiQueue) multiQueue += login
                scope.launch {
                    val r = snackbar.showSnackbar("Added $login to multi-view (${multiQueue.size})", actionLabel = "Open", duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) {
                        nav.navigate(MultiViewRoute(multiQueue.joinToString(",")))
                        multiQueue.clear()
                    }
                }
            }
        }
    }
    val libraryNav = remember {
        object : lol.sinist3r.yip.feature.watch.LibraryNavigator {
            override fun back() { nav.popBackStack() }
            override fun openHistory(entry: HistoryEntry) = browseNav.openHistory(entry)
            override fun openDownload(kind: String, contentId: String) = nav.navigate(WatchRoute(kind, contentId))
        }
    }
    val inboxNav = remember {
        object : lol.sinist3r.yip.feature.watch.InboxNavigator {
            override fun back() { nav.popBackStack() }
            override fun openLive(login: String) = nav.navigate(WatchRoute("LIVE", login))
            override fun openWhisper(login: String) = nav.navigate(WhisperRoute(login))
            override fun openLink(url: String) = openLink(url)
        }
    }
    val watchNav = remember {
        object : WatchNavigator {
            override fun back() { nav.popBackStack() }
            override fun openChannel(login: String) = nav.navigate(ChannelRoute(login))
            override fun openVod(videoId: String, offsetSeconds: Int) = nav.navigate(WatchRoute("VOD", videoId, offsetSeconds))
            override fun openLink(url: String) = openLink(url)
            override fun openWhisper(login: String) = nav.navigate(WhisperRoute(login))
            override fun openConnectWeb() = nav.navigate(ConnectWebRoute())
            override fun openLive(login: String) {
                nav.popBackStack()
                nav.navigate(WatchRoute("LIVE", login))
            }
            override fun openMultiView(logins: List<String>) {
                nav.popBackStack()
                nav.navigate(MultiViewRoute(logins.joinToString(",")))
            }
        }
    }
    val settingsNav = remember {
        object : SettingsNavigator {
            override fun back() { nav.popBackStack() }
            override fun openPage(key: String) = nav.navigate(SettingsPageRoute(key))
            override fun openLogin() = nav.navigate(LoginRoute)
            override fun openConnectWeb() = nav.navigate(ConnectWebRoute())
            override fun openLogs() = nav.navigate(LogsRoute)
            override fun openDataUsage() = nav.navigate(DataUsageRoute)
        }
    }

    val onFollow: (String, String, Boolean) -> Unit = { channelId, login, follow ->
        scope.launch {
            when (val r = activity.follows.setFollowing(channelId, follow)) {
                FollowResult.Done -> snackbar.showSnackbar(if (follow) "Following $login" else "Unfollowed $login")
                // Following needs the twitch.tv connection; offer it rather than bouncing to the browser.
                FollowResult.NeedsWeb -> {
                    val r = snackbar.showSnackbar("Connect twitch.tv to follow from Yip", actionLabel = "Connect", withDismissAction = true, duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) nav.navigate(ConnectWebRoute())
                }
                is FollowResult.Failed -> snackbar.showSnackbar(r.reason)
            }
        }
    }

    if (settings.general.confirmBeforeExit && isTopLevel) {
        BackHandler(enabled = nav.previousBackStackEntry == null) {
            if (exitArmed) activity.finish() else {
                exitArmed = true
                scope.launch { snackbar.showSnackbar("Press back again to exit"); exitArmed = false }
            }
        }
    }

    val start: Any = when (settings.general.startScreen) {
        StartScreen.FOLLOWING -> FollowingRoute
        StartScreen.BROWSE -> BrowseRoute
        StartScreen.SEARCH -> SearchRoute
    }

    val content = @Composable {
        Box(Modifier.fillMaxSize()) {
            // Short fades instead of the 700 ms default; none at all with reduce motion on.
            val reduce = settings.appearance.reduceMotion
            NavHost(
                nav, startDestination = start, modifier = Modifier.fillMaxSize(),
                enterTransition = { if (reduce) EnterTransition.None else fadeIn(tween(150)) },
                exitTransition = { if (reduce) ExitTransition.None else fadeOut(tween(90)) },
                // Going back is instant, whether by button or swipe. The swipe has its own
                // transition pair (predictivePop*), which otherwise defaults to a fade/scale.
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
                predictivePopEnterTransition = { EnterTransition.None },
                predictivePopExitTransition = { ExitTransition.None },
                sizeTransform = null,
            ) {
                composable<FollowingRoute> { FollowingScreen(browseNav) }
                composable<BrowseRoute> { BrowseScreen(browseNav) }
                composable<SearchRoute> { SearchScreen(browseNav, onBack = if (nav.previousBackStackEntry != null) ({ nav.popBackStack() }) else null) }
                composable<CategoryRoute> { CategoryScreen(browseNav, onBack = { nav.popBackStack() }) }
                composable<ChannelRoute> { ChannelScreen(browseNav, onBack = { nav.popBackStack() }, onFollowToggle = onFollow) }
                composable<WatchRoute>(deepLinks = emptyList()) { WatchScreen(watchNav, inPip) }
                composable<MultiViewRoute> { MultiViewScreen(watchNav) }
                composable<LibraryRoute> { e -> lol.sinist3r.yip.feature.watch.LibraryScreen(libraryNav, startTab = e.toRoute<LibraryRoute>().tab) }
                composable<ManageFollowsRoute> { lol.sinist3r.yip.feature.browse.ManageFollowsScreen(onBack = { nav.popBackStack() }, onOpenChannel = { nav.navigate(ChannelRoute(it)) }) }
                composable<DataUsageRoute> { lol.sinist3r.yip.feature.settings.DataUsageScreen(onBack = { nav.popBackStack() }) }
                composable<InboxRoute> { lol.sinist3r.yip.feature.watch.InboxScreen(inboxNav) }
                composable<WhisperRoute> { lol.sinist3r.yip.feature.watch.WhisperScreen(inboxNav) }
                composable<SettingsRoute> { SettingsRoot(settingsNav, showBack = false) }
                composable<SettingsPageRoute> { entry ->
                    when (val key = entry.toRoute<SettingsPageRoute>().key) {
                        "account" -> AccountsScreen(settingsNav)
                        "about" -> AboutScreen(BuildConfig.VERSION_NAME, settingsNav)
                        else -> SettingsPage(key, settingsNav)
                    }
                }
                composable<LoginRoute> {
                    // Signing in carries straight on to connecting twitch.tv, unless it's already connected.
                    LoginScreen(
                        onDone = {
                            scope.launch {
                                val connected = activity.accounts.webToken() != null
                                nav.popBackStack()
                                if (!connected) nav.navigate(ConnectWebRoute(afterLogin = true))
                            }
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable<ConnectWebRoute> { e -> ConnectWebScreen(onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() }, afterLogin = e.toRoute<ConnectWebRoute>().afterLogin) }
                composable<LogsRoute> { LogsScreen(onBack = { nav.popBackStack() }) }
                composable<StreamManagerRoute> {
                    lol.sinist3r.yip.feature.browse.StreamManagerScreen(
                        onBack = { nav.popBackStack() },
                        onOpenChat = { nav.navigate(WatchRoute("CHAT", it)) },
                        onSignIn = { nav.navigate(LoginRoute) },
                    )
                }
            }
            val src = playState.source
            if ((!onWatch || chatOnly) && src != null && settings.player.miniPlayer && !inPip) {
                MiniPlayer(
                    title = src.title.ifBlank { src.channelName },
                    channel = src.channelName,
                    playing = playState.playing,
                    onToggle = { if (playState.playing) activity.playback.player.pause() else activity.playback.player.play() },
                    onClose = { activity.playback.stop() },
                    onOpen = {
                        when (src) {
                            is PlaybackSource.Live -> nav.navigate(WatchRoute("LIVE", src.channelLogin))
                            is PlaybackSource.Vod -> nav.navigate(WatchRoute("VOD", src.videoId))
                            is PlaybackSource.ClipSrc -> nav.navigate(WatchRoute("CLIP", src.slug, src.vodOffset ?: -1, src.channelLogin, src.title, src.vodId))
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
        }
    }

    // The scaffold stays in the tree on every screen so the NavHost is never torn down and rebuilt;
    // the bar is just hidden off top-level screens.
    val adaptive = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(androidx.compose.material3.adaptive.currentWindowAdaptiveInfo())
    run {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                topLevel.forEach { t ->
                    val selected = destination?.hierarchy?.any { it.hasRoute(t.route::class) } == true
                    item(
                        selected = selected,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = if (settings.appearance.bottomBarLabels) ({ Text(t.label) }) else null,
                    )
                }
            },
            layoutType = if (onWatch || inPip || !isTopLevel) NavigationSuiteType.None else adaptive,
        ) { content() }
    }
    if (!inPip) lol.sinist3r.yip.feature.settings.UpdatePrompt()
}

/** Maps twitch.tv URLs to screens. Returns false for pages Yip doesn't handle. */
private fun handleTwitchUri(uri: Uri, nav: NavHostController): Boolean {
    val segs = uri.pathSegments.filter { it.isNotBlank() }
    if (uri.host == "clips.twitch.tv" && segs.isNotEmpty()) { nav.navigate(WatchRoute("CLIP", segs[0])); return true }
    if (segs.isEmpty()) return false
    val reserved = setOf("directory", "settings", "subscriptions", "inventory", "wallet", "drops", "downloads", "jobs", "p", "search", "login", "signup", "turbo", "prime", "store")
    return when {
        segs[0] == "videos" && segs.size > 1 -> {
            val t = uri.getQueryParameter("t")?.let(::parseTwitchTime) ?: -1
            nav.navigate(WatchRoute("VOD", segs[1], t)); true
        }
        segs.size >= 3 && segs[1] == "clip" -> { nav.navigate(WatchRoute("CLIP", segs[2], channelLogin = segs[0])); true }
        segs[0] in reserved -> false
        segs.size == 1 -> { nav.navigate(WatchRoute("LIVE", segs[0].lowercase())); true }
        segs.size >= 2 && segs[1] in setOf("videos", "clips", "about", "schedule") -> { nav.navigate(ChannelRoute(segs[0].lowercase())); true }
        else -> false
    }
}

/** "1h2m3s" -> 3723 */
private fun parseTwitchTime(t: String): Int {
    var total = 0
    Regex("""(\d+)([hms])""").findAll(t).forEach { total += it.groupValues[1].toInt() * when (it.groupValues[2]) { "h" -> 3600; "m" -> 60; else -> 1 } }
    return if (total == 0) t.toIntOrNull() ?: -1 else total
}

@Composable
private fun MiniPlayer(title: String, channel: String, playing: Boolean, onToggle: () -> Unit, onClose: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(channel, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggle) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play") }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Stop") }
        }
    }
}
