package lol.sinist3r.yip.feature.browse

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
    var sortMenu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Following") },
                actions = {
                    IconButton(onClick = nav::openSearch) { Icon(Icons.Outlined.Search, "Search") }
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
                        fullSpan("hdr-history") { SectionHeader("Continue watching") }
                        fullSpan("history") { HistoryRow(state.history, nav::openHistory, viewModel::removeHistory) }
                    }
                    fullSpan("hdr-live") { SectionHeader("Live · ${state.live.size}") }
                    if (state.live.isEmpty()) fullSpan("nolive") { Text("Nobody you follow is live right now.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(state.live, key = { it.id }) { s ->
                        StreamCard(s, settings.appearance, onClick = { nav.openStream(s) }, onLongClick = { nav.addToMultiView(s.userLogin) })
                    }
                    if (state.offline.isNotEmpty()) {
                        fullSpan("hdr-off") { SectionHeader("Offline · ${state.offline.size}") }
                        items(state.offline, key = { "off-" + it.id }, span = { GridItemSpan(maxLineSpan) }) { c ->
                            ListItem(
                                headlineContent = { Text(c.displayName) },
                                leadingContent = { Avatar(c.profileImageUrl, null, 36.dp) },
                                modifier = Modifier.clip(MaterialTheme.shapes.small).clickable { nav.openChannel(c.login) },
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
    Scaffold(topBar = {
        Column {
            TopAppBar(title = { Text("Browse") }, actions = { IconButton(onClick = nav::openSearch) { Icon(Icons.Outlined.Search, "Search") } })
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Live channels") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Categories") })
            }
        }
    }) { padding ->
        Box(Modifier.padding(padding)) {
            if (tab == 0) {
                StreamGrid(streams, settings.appearance, viewModel::refreshStreams, viewModel::moreStreams, nav)
            } else {
                CategoryGrid(categories, viewModel::refreshCategories, viewModel::moreCategories) { nav.openCategory(it.id, it.name) }
            }
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
) {
    val grid = rememberLazyGridState()
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
                    StreamCard(s, appearance, onClick = { nav.openStream(s) }, onLongClick = { nav.addToMultiView(s.userLogin) })
                }
                if (paged.loading) fullSpan("loading") { Box(Modifier.fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryGrid(paged: Paged<Category>, onRefresh: () -> Unit, onMore: () -> Unit, onOpen: (Category) -> Unit) {
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
                items(paged.items, key = { it.id }) { c -> CategoryCard(c, onClick = { onOpen(c) }) }
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
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(viewModel.categoryName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Box(Modifier.padding(padding)) { StreamGrid(streams, settings.appearance, viewModel::refresh, viewModel::more, nav) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: BrowseNavigator, onBack: (() -> Unit)?, viewModel: SearchViewModel = hiltViewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
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
                MessageState("Search Twitch", "Type at least two characters.", Icons.Outlined.Search)
            } else if (tab == 0) {
                val grid = rememberLazyGridState()
                grid.OnNearEnd(onLoadMore = { viewModel.moreChannels() })
                if (!channels.initialized) LoadingState() else LazyVerticalGrid(GridCells.Fixed(1), state = grid, contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(channels.items, key = { it.id }) { c ->
                        ListItem(
                            headlineContent = { Text(c.displayName) },
                            supportingContent = { Text(listOfNotNull(if (c.isLive) "Live" else null, c.gameName, c.title).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Avatar(c.profileImageUrl, null, 40.dp, live = c.isLive) },
                            modifier = Modifier.clickable { nav.openChannel(c.login) },
                        )
                    }
                }
            } else {
                CategoryGrid(categories, {}, { viewModel.moreCategories() }) { nav.openCategory(it.id, it.name) }
            }
        }
    }
}

@Composable
internal fun FilterRow(options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        items(options.size) { i -> FilterChip(selected = options[i].second, onClick = { onSelect(i) }, label = { Text(options[i].first) }) }
    }
}
