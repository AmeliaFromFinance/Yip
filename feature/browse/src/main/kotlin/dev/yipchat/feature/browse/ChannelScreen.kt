package dev.yipchat.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yipchat.core.common.compactCount
import dev.yipchat.core.data.twitch.TwitchRepository
import dev.yipchat.core.designsystem.component.Avatar
import dev.yipchat.core.designsystem.component.ClipCard
import dev.yipchat.core.designsystem.component.ErrorState
import dev.yipchat.core.designsystem.component.LivePill
import dev.yipchat.core.designsystem.component.LoadingState
import dev.yipchat.core.designsystem.component.MessageState
import dev.yipchat.core.designsystem.component.VideoCard
import dev.yipchat.core.designsystem.util.OnNearEnd
import dev.yipchat.core.model.VideoType
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.toJavaInstant

private val dateFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    nav: BrowseNavigator,
    onBack: () -> Unit,
    onFollowToggle: (channelId: String, login: String, follow: Boolean) -> Unit,
    viewModel: ChannelViewModel = hiltViewModel(),
) {
    val header by viewModel.header.collectAsStateWithLifecycle()
    val watched by viewModel.watched.collectAsStateWithLifecycle()
    val later by viewModel.later.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var videoFilter by rememberSaveable { mutableIntStateOf(0) }
    var clipFilter by rememberSaveable { mutableIntStateOf(1) }
    val uri = LocalUriHandler.current

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(header.info?.user?.displayName ?: "") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        val info = header.info
        when {
            header.loading -> LoadingState(Modifier.padding(padding))
            header.error != null || info == null -> ErrorState(header.error ?: "Not found", { viewModel.load() }, Modifier.padding(padding))
            else -> {
                val grid = rememberLazyGridState()
                grid.OnNearEnd { if (tab == 1) viewModel.moreVideos() else if (tab == 2) viewModel.moreClips() }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(260.dp),
                    state = grid,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(padding).fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(info.user.profileImageUrl, info.user.displayName, 64.dp, live = info.stream != null)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(info.user.displayName, style = MaterialTheme.typography.headlineSmall)
                                    info.stream?.let { Row(verticalAlignment = Alignment.CenterVertically) {
                                        LivePill()
                                        Spacer(Modifier.width(8.dp))
                                        Text("${it.viewerCount.compactCount()} watching · ${it.gameName.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                                    } } ?: Text(if (info.user.isPartner) "Partner" else "Offline", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                info.stream?.let { s -> Button(onClick = { nav.openStream(s) }) { Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Watch live") } }
                                val quickVm: QuickActionsViewModel = hiltViewModel()
                                val pinned = quickVm.state.collectAsStateWithLifecycle().value.isPinned(info.user.login)
                                IconButton(onClick = { quickVm.togglePin(info.user.login) }) {
                                    Icon(if (pinned) Icons.Outlined.Star else Icons.Outlined.StarBorder, if (pinned) "Unpin from Following" else "Pin to Following",
                                        tint = if (pinned) MaterialTheme.colorScheme.primary else Color.Unspecified)
                                }
                                if (header.isMe) {
                                    FilledTonalButton(onClick = nav::openStreamManager) { Icon(Icons.Outlined.Tune, null); Spacer(Modifier.width(6.dp)); Text("Manage stream") }
                                } else if (header.following) {
                                    OutlinedButton(onClick = { viewModel.followTapped(info.user.id, false); onFollowToggle(info.user.id, info.user.login, false) }) { Icon(Icons.Outlined.Favorite, null); Spacer(Modifier.width(6.dp)); Text("Following") }
                                } else {
                                    FilledTonalButton(onClick = { viewModel.followTapped(info.user.id, true); onFollowToggle(info.user.id, info.user.login, true) }) { Icon(Icons.Outlined.FavoriteBorder, null); Spacer(Modifier.width(6.dp)); Text("Follow") }
                                }
                            }
                            info.stream?.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                            listOf("About", "Videos", "Clips", "Schedule").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
                            // Opens a full-height chat screen; works whether or not the channel is live.
                            Tab(false, { nav.openChat(info.user.login) }, text = { Text("Chat") })
                        }
                    }
                    when (tab) {
                        0 -> item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(info.user.description?.ifBlank { null } ?: "No bio yet.", style = MaterialTheme.typography.bodyMedium)
                                info.user.createdAt?.let { Text("Joined ${dateFmt.format(it.toJavaInstant().atZone(ZoneId.systemDefault()))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                OutlinedButton(onClick = { uri.openUri("https://www.twitch.tv/${info.user.login}/about") }) { Text("Panels on twitch.tv") }
                            }
                        }
                        1 -> {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                FilterRow(listOf("All", "Past broadcasts", "Highlights", "Uploads").mapIndexed { i, s -> s to (i == videoFilter) }) { i ->
                                    videoFilter = i
                                    viewModel.setVideoType(listOf(null, VideoType.ARCHIVE, VideoType.HIGHLIGHT, VideoType.UPLOAD)[i])
                                }
                            }
                            if (videos.initialized && videos.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(32.dp)) { MessageState("No videos") } }
                            items(videos.items, key = { "v" + it.id }) { v ->
                                val h = watched["VOD:${v.id}"]
                                VideoCard(v, h?.progress?.takeIf { h.durationSeconds > 0 }, onClick = { nav.openVideo(v) }, watched = h?.finished == true, inWatchLater = "VOD:${v.id}" in later, onWatchLater = { viewModel.toggleLater(v) })
                            }
                        }
                        2 -> {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                val periods = TwitchRepository.ClipPeriod.entries
                                FilterRow(listOf("24 hours", "7 days", "30 days", "All time").mapIndexed { i, s -> s to (i == clipFilter) }) { i ->
                                    clipFilter = i
                                    viewModel.setClipPeriod(periods[i])
                                }
                            }
                            if (clips.initialized && clips.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(32.dp)) { MessageState("No clips in this period") } }
                            items(clips.items, key = { "c" + it.id }) { c ->
                                ClipCard(c, onClick = { nav.openClip(c) }, watched = watched["CLIP:${c.id}"]?.finished == true, inWatchLater = "CLIP:${c.id}" in later, onWatchLater = { viewModel.toggleLater(c) })
                            }
                        }
                        else -> {
                            if (header.schedule.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(32.dp)) { MessageState("No schedule", "This channel hasn't published one, or you're signed out.") } }
                            items(header.schedule, key = { "s" + it.id }, span = { GridItemSpan(maxLineSpan) }) { seg ->
                                ListItem(
                                    headlineContent = { Text(seg.title.ifBlank { "Untitled stream" }) },
                                    supportingContent = {
                                        Text(listOfNotNull(dateFmt.format(seg.startTime.toJavaInstant().atZone(ZoneId.systemDefault())), seg.categoryName, if (seg.canceled) "Cancelled" else null).joinToString(" · "))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
