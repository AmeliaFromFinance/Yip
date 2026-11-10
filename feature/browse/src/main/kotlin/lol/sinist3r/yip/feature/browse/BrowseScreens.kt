package lol.sinist3r.yip.feature.browse

import androidx.compose.material.icons.outlined.Star
import androidx.compose.foundation.combinedClickable
import lol.sinist3r.yip.core.model.RecentKind
import lol.sinist3r.yip.core.model.RecentSearch
import androidx.compose.material.icons.outlined.History
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import lol.sinist3r.yip.core.data.history.HistoryEntry
import lol.sinist3r.yip.core.designsystem.component.Avatar
import lol.sinist3r.yip.core.designsystem.component.CategoryCard
import lol.sinist3r.yip.core.designsystem.component.ErrorState
import lol.sinist3r.yip.core.designsystem.component.LoadingState
import lol.sinist3r.yip.core.designsystem.component.MessageState
import lol.sinist3r.yip.core.designsystem.component.SectionHeader
import lol.sinist3r.yip.core.designsystem.component.StreamCard
import lol.sinist3r.yip.core.designsystem.util.OnNearEnd
import lol.sinist3r.yip.core.designsystem.util.Paged
import lol.sinist3r.yip.core.model.AppearanceSettings
import lol.sinist3r.yip.core.model.CardLayout
import lol.sinist3r.yip.core.model.Category
import lol.sinist3r.yip.core.model.Clip
import lol.sinist3r.yip.core.model.FollowingSort
import lol.sinist3r.yip.core.model.LiveStream
import lol.sinist3r.yip.core.model.Video

interface BrowseNavigator {
    fun openStream(stream: LiveStream)
    fun openChannel(login: String)
    fun openCategory(id: String, name: String)
    fun openVideo(video: Video)
    fun openClip(clip: Clip)
    fun openHistory(entry: HistoryEntry)
    fun openLogin()
    fun addToMultiView(login: String)
    fun openSearch()
    fun openChat(login: String)
    fun openStreamManager()
    fun openInbox() {}
    fun openLibrary() {}
    fun openManageFollows() {}
}

internal fun gridCells(appearance: AppearanceSettings): GridCells = when {
    appearance.cardLayout != CardLayout.GRID -> GridCells.Fixed(1)
    appearance.gridColumns > 0 -> GridCells.Fixed(appearance.gridColumns)
    else -> GridCells.Adaptive(170.dp)
}

private fun LazyGridScope.fullSpan(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowingScreen(nav: BrowseNavigator, viewModel: FollowingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val categories by viewModel.followedCategories.collectAsStateWithLifecycle()
    var sortMenu by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf<QuickTarget?>(null) }
    quick?.let { t ->
        QuickActionsSheet(t, onAddToMultiView = nav::addToMultiView, onDismiss = { quick = null }, allowHide = false, onWatch = nav::openStream)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Following") },
                actions = {
                    val unread by viewModel.inboxUnread.collectAsStateWithLifecycle()
                    IconButton(onClick = nav::openInbox) {
                        androidx.compose.material3.BadgedBox(badge = { if (unread > 0) androidx.compose.material3.Badge { Text(if (unread > 99) "99+" else "$unread") } }) {
                            Icon(Icons.Outlined.Inbox, if (unread > 0) "Inbox, $unread unread" else "Inbox")
                        }
                    }
                    IconButton(onClick = nav::openSearch) { Icon(Icons.Outlined.Search, "Search") }
                    IconButton(onClick = nav::openLibrary) { Icon(Icons.Outlined.VideoLibrary, "History and downloads") }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                        DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                            FollowingSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label()) },
                                    onClick = { viewModel.setSort(s); sortMenu = false },
                                    trailingIcon = { if (settings.general.followingSort == s) Icon(Icons.Outlined.Favorite, null) },
                                )
                            }
                            androidx.compose.material3.HorizontalDivider()
                            DropdownMenuItem(text = { Text("Manage follows") }, onClick = { sortMenu = false; nav.openManageFollows() })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (!state.signedIn) {
            MessageState(
                "Sign in to see who you follow",
                "Browsing and watching work without an account. Signing in adds your follows, chat and notifications.",
                Icons.Outlined.Favorite, "Sign in", nav::openLogin, Modifier.padding(padding),
            )
            return@Scaffold
        }
        PullToRefreshBox(isRefreshing = state.loading && state.live.isNotEmpty(), onRefresh = viewModel::refresh, modifier = Modifier.padding(padding)) {
            when {
                state.loading && state.live.isEmpty() -> LoadingState()
                state.error != null && state.live.isEmpty() -> ErrorState(state.error!!, viewModel::refresh)
                else -> LazyVerticalGrid(
                    columns = gridCells(settings.appearance),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (state.history.isNotEmpty()) {
                        fullSpan("hdr-history") { SectionHeader("Continue watching") { androidx.compose.material3.TextButton(onClick = nav::openLibrary) { Text("See all") } } }
                        fullSpan("history") { HistoryRow(state.history, nav::openHistory, viewModel::removeHistory) }
                    }
                    if (categories.isNotEmpty()) {
                        fullSpan("hdr-categories") { SectionHeader("Followed categories") }
                        fullSpan("categories") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(categories, key = { it.id }) { c ->
                                    Column(Modifier.width(96.dp).clip(MaterialTheme.shapes.medium).clickable(onClickLabel = "Open ${c.name}") { nav.openCategory(c.id, c.name) }) {
                                        AsyncImage(c.boxArtUrl, null, Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(MaterialTheme.shapes.medium))
                                        Text(c.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                    if (state.pinned.isNotEmpty()) {
                        fullSpan("hdr-pinned") { SectionHeader("Pinned") }
                        state.pinned.filter { it.stream != null }.forEach { p ->
                            item(key = "pin-live-${p.login}") {
                                val st = p.stream!!
                                StreamCard(st, settings.appearance, onClick = { nav.openStream(st) }, onLongClick = { quick = QuickTarget(p.login, p.displayName, st.gameName, st) })
                            }
                        }
                        state.pinned.filter { it.stream == null }.forEach { p ->
                            fullSpan("pin-off-${p.login}") {
                                ListItem(
                                    headlineContent = { Text(p.displayName) },
                                    supportingContent = { Text("Offline") },
                                    leadingContent = { Avatar(p.avatar, null, 36.dp) },
                                    trailingContent = { Icon(Icons.Outlined.Star, "Pinned", tint = MaterialTheme.colorScheme.primary) },
                                    modifier = Modifier.clip(MaterialTheme.shapes.small)
                                        .combinedClickable(onClick = { nav.openChannel(p.login) }, onLongClick = { quick = QuickTarget(p.login, p.displayName) }),
                                )
                            }
                        }
                    }
                    fullSpan("hdr-live") { SectionHeader("Live · ${state.live.size}") }
                    if (state.live.isEmpty()) fullSpan("nolive") { Text("Nobody you follow is live right now.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(state.live, key = { it.id }) { s ->
                        StreamCard(s, settings.appearance, onClick = { nav.openStream(s) }, onLongClick = { quick = QuickTarget(s.userLogin, s.userName, s.gameName, s) })
                    }
                    if (state.offline.isNotEmpty()) {
                        fullSpan("hdr-off") { SectionHeader("Offline · ${state.offline.size}") }
                        items(state.offline, key = { "off-" + it.id }, span = { GridItemSpan(maxLineSpan) }) { c ->
                            ListItem(
                                headlineContent = { Text(c.displayName) },
                                leadingContent = { Avatar(c.profileImageUrl, null, 36.dp) },
                                modifier = Modifier.clip(MaterialTheme.shapes.small)
                                    .combinedClickable(onClick = { nav.openChannel(c.login) }, onLongClick = { quick = QuickTarget(c.login, c.displayName) }),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun FollowingSort.label() = when (this) {
    FollowingSort.VIEWERS -> "Most viewers"
    FollowingSort.RECENTLY_STARTED -> "Recently started"
    FollowingSort.NAME -> "Name"
    FollowingSort.CATEGORY -> "Category"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(entries: List<HistoryEntry>, onOpen: (HistoryEntry) -> Unit, onRemove: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(entries, key = { it.key }) { e ->
            Column(
                Modifier.width(200.dp).clip(MaterialTheme.shapes.medium).clickable { onOpen(e) },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box {
                    AsyncImage(
                        e.thumbnailUrl?.replace("{width}", "320")?.replace("{height}", "180")?.replace("%{width}", "320")?.replace("%{height}", "180"),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium),
                    )
                    IconButton(onClick = { onRemove(e.key) }, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Outlined.Close, "Remove from history") }
                    if (e.progress > 0f) LinearProgressIndicator(progress = { e.progress }, Modifier.align(Alignment.BottomCenter).fillMaxWidth(), drawStopIndicator = {})
                    if (e.durationSeconds > 0) {
                        val left = ((e.durationSeconds - e.positionSeconds) / 60).coerceAtLeast(1)
                        Text(
                            if (left >= 60) "${left / 60}h ${left % 60}m left" else "${left}m left",
                            style = MaterialTheme.typography.labelSmall, color = Color.White,
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(e.channelName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(e.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(nav: BrowseNavigator, viewModel: BrowseViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val streams by viewModel.streams.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var hideCategory by remember { mutableStateOf<String?>(null) }
    val quickVm: QuickActionsViewModel = hiltViewModel()
    Scaffold(topBar = {
        Column {
            TopAppBar(title = { Text("Browse") }, actions = { IconButton(onClick = nav::openSearch) { Icon(Icons.Outlined.Search, "Search") } })
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Live channels") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Categories") })
            }
            LanguageChips(settings.general.streamLanguages, viewModel::setLanguages)
        }
    }) { padding ->
        Box(Modifier.padding(padding)) {
            if (tab == 0) {
                StreamGrid(streams, settings.appearance, viewModel::refreshStreams, viewModel::moreStreams, nav)
            } else {
                CategoryGrid(categories, viewModel::refreshCategories, viewModel::moreCategories, onLongClick = { hideCategory = it.name }) { nav.openCategory(it.id, it.name) }
            }
        }
    }
    hideCategory?.let { name ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { hideCategory = null },
            title = { Text("Hide $name?") },
            text = { Text("It won't show in Browse. Undo under Settings → General → Hidden.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { quickVm.hideCategory(name); hideCategory = null }) { Text("Hide") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { hideCategory = null }) { Text("Cancel") } },
        )
    }
}

private val LANGUAGES = listOf(
    "en" to "English", "de" to "German", "es" to "Spanish", "fr" to "French", "it" to "Italian", "pt" to "Portuguese",
    "nl" to "Dutch", "pl" to "Polish", "sv" to "Swedish", "tr" to "Turkish", "ru" to "Russian", "uk" to "Ukrainian",
    "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "ar" to "Arabic", "th" to "Thai", "id" to "Indonesian",
)

/** Quick language filter for Browse; same setting as Settings → General → Stream languages. */
@Composable
private fun LanguageChips(selected: List<String>, onChange: (List<String>) -> Unit) {
    val extra = selected.filter { code -> LANGUAGES.none { it.first == code } }.map { it to it.uppercase() }
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "all") { androidx.compose.material3.FilterChip(selected = selected.isEmpty(), onClick = { onChange(emptyList()) }, label = { Text("All languages") }) }
        items((extra + LANGUAGES).size, key = { (extra + LANGUAGES)[it].first }) { i ->
            val (code, name) = (extra + LANGUAGES)[i]
            val on = code in selected
            androidx.compose.material3.FilterChip(selected = on, onClick = { onChange(if (on) selected - code else selected + code) }, label = { Text(name) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StreamGrid(
    paged: Paged<LiveStream>,
    appearance: AppearanceSettings,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    nav: BrowseNavigator,
    header: (LazyGridScope.() -> Unit)? = null,
    allowHideCategory: Boolean = true,
) {
    val grid = rememberLazyGridState()
    var quick by remember { mutableStateOf<QuickTarget?>(null) }
    quick?.let { t -> QuickActionsSheet(t, onAddToMultiView = nav::addToMultiView, onDismiss = { quick = null }, onWatch = nav::openStream) }
    grid.OnNearEnd(onLoadMore = onMore)
    when {
        !paged.initialized -> LoadingState()
        paged.error != null && paged.items.isEmpty() -> ErrorState(paged.error!!, onRefresh)
        paged.items.isEmpty() -> MessageState("No live channels", "Try a different language filter in settings.")
        else -> PullToRefreshBox(isRefreshing = false, onRefresh = onRefresh) {
            LazyVerticalGrid(
                columns = gridCells(appearance),
                state = grid,
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                header?.invoke(this)
                items(paged.items, key = { it.id }) { s ->
                    StreamCard(s, appearance, onClick = { nav.openStream(s) }, onLongClick = { quick = QuickTarget(s.userLogin, s.userName, s.gameName.takeIf { allowHideCategory }, s) })
                }
                if (paged.loading) fullSpan("loading") { Box(Modifier.fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryGrid(paged: Paged<Category>, onRefresh: () -> Unit, onMore: () -> Unit, onLongClick: ((Category) -> Unit)? = null, onOpen: (Category) -> Unit) {
    val grid = rememberLazyGridState()
    grid.OnNearEnd(onLoadMore = onMore)
    when {
        !paged.initialized -> LoadingState()
        paged.error != null && paged.items.isEmpty() -> ErrorState(paged.error!!, onRefresh)
        else -> PullToRefreshBox(isRefreshing = false, onRefresh = onRefresh) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                state = grid,
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(paged.items, key = { it.id }) { c -> CategoryCard(c, onClick = { onOpen(c) }, onLongClick = onLongClick?.let { { it(c) } }) }
                if (paged.loading) fullSpan("loading") { Box(Modifier.fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(nav: BrowseNavigator, onBack: () -> Unit, viewModel: CategoryViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val streams by viewModel.streams.collectAsStateWithLifecycle()
    val followed by viewModel.followed.collectAsStateWithLifecycle()
    val connected by viewModel.webConnected.collectAsStateWithLifecycle()
    val alert = viewModel.categoryName.lowercase() in settings.notifications.categoryAlerts
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(viewModel.categoryName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = viewModel::toggleAlert) {
                    Icon(if (alert) Icons.Filled.NotificationsActive else Icons.Outlined.NotificationsNone, if (alert) "Turn off alerts for this category" else "Alert me when someone I follow streams this")
                }
                if (connected) IconButton(onClick = viewModel::toggleFollow) {
                    Icon(if (followed) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (followed) "Unfollow category" else "Follow category")
                }
            },
        )
    }, snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.padding(padding)) { StreamGrid(streams, settings.appearance, viewModel::refresh, viewModel::more, nav, allowHideCategory = false) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: BrowseNavigator, onBack: (() -> Unit)?, viewModel: SearchViewModel = hiltViewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val openChannel = { c: lol.sinist3r.yip.core.model.Channel ->
        viewModel.rememberResult(RecentSearch(RecentKind.CHANNEL, c.login, c.displayName, c.profileImageUrl), query)
        nav.openChannel(c.login)
    }
    val openCategory = { c: lol.sinist3r.yip.core.model.Category ->
        viewModel.rememberResult(RecentSearch(RecentKind.CATEGORY, c.id, c.name, c.boxArt(72, 96)), query)
        nav.openCategory(c.id, c.name)
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    // Pop the keyboard only when search was opened on purpose, not when tapping the Search tab.
    androidx.compose.runtime.LaunchedEffect(Unit) { if (onBack != null) runCatching { focus.requestFocus() } }
    Scaffold(topBar = {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onQuery,
                    placeholder = { Text("Search channels and categories") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { viewModel.onQuery("") }) { Icon(Icons.Outlined.Close, "Clear") } },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    // Pressing search without opening a result keeps the query for later.
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { viewModel.rememberQuery() }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Channels") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Categories") })
            }
        }
    }) { padding ->
        Box(Modifier.padding(padding)) {
            if (query.trim().length < 2) {
                if (recent.isEmpty()) {
                    MessageState("Search Twitch", "Type at least two characters.", Icons.Outlined.Search)
                } else {
                    RecentSearchList(
                        items = recent,
                        onOpen = { r ->
                            when (r.kind) {
                                RecentKind.CHANNEL -> { viewModel.remember(r); nav.openChannel(r.key) }
                                RecentKind.CATEGORY -> { viewModel.remember(r); nav.openCategory(r.key, r.label) }
                                RecentKind.QUERY -> viewModel.onQuery(r.key)
                            }
                        },
                        onRemove = viewModel::forget,
                        onClear = viewModel::clearRecent,
                    )
                }
            } else if (tab == 0) {
                val grid = rememberLazyGridState()
                grid.OnNearEnd(onLoadMore = { viewModel.moreChannels() })
                if (!channels.initialized) LoadingState() else LazyVerticalGrid(GridCells.Fixed(1), state = grid, contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(channels.items, key = { it.id }) { c ->
                        ListItem(
                            headlineContent = { Text(c.displayName) },
                            supportingContent = { Text(listOfNotNull(if (c.isLive) "Live" else null, c.gameName, c.title).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Avatar(c.profileImageUrl, null, 40.dp, live = c.isLive) },
                            modifier = Modifier.clickable { openChannel(c) },
                        )
                    }
                }
            } else {
                CategoryGrid(categories, {}, { viewModel.moreCategories() }) { openCategory(it) }
            }
        }
    }
}

@Composable
private fun RecentSearchList(items: List<RecentSearch>, onOpen: (RecentSearch) -> Unit, onRemove: (RecentSearch) -> Unit, onClear: () -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onClear) { Text("Clear all") }
            }
        }
        items(items.size, key = { "${items[it].kind}:${items[it].key}" }) { i ->
            val r = items[i]
            ListItem(
                headlineContent = { Text(r.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(when (r.kind) { RecentKind.CHANNEL -> "Channel"; RecentKind.CATEGORY -> "Category"; RecentKind.QUERY -> "Search" }) },
                leadingContent = {
                    when (r.kind) {
                        RecentKind.CHANNEL -> Avatar(r.imageUrl, null, 40.dp)
                        RecentKind.CATEGORY -> coil3.compose.AsyncImage(
                            model = r.imageUrl, contentDescription = null,
                            modifier = Modifier.size(30.dp, 40.dp).clip(MaterialTheme.shapes.extraSmall),
                        )
                        RecentKind.QUERY -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.History, null) }
                    }
                },
                trailingContent = { IconButton(onClick = { onRemove(r) }) { Icon(Icons.Outlined.Close, "Remove ${r.label}") } },
                modifier = Modifier.clickable { onOpen(r) },
            )
        }
    }
}

@Composable
internal fun FilterRow(options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        items(options.size) { i -> FilterChip(selected = options[i].second, onClick = { onSelect(i) }, label = { Text(options[i].first) }) }
    }
}
