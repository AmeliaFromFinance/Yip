package dev.yipchat.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.yipchat.core.common.clock
import dev.yipchat.core.common.compactCount
import dev.yipchat.core.common.secondsClock
import dev.yipchat.core.designsystem.util.shareLink
import dev.yipchat.core.designsystem.util.shareUrl
import dev.yipchat.core.model.AppearanceSettings
import dev.yipchat.core.model.CardLayout
import dev.yipchat.core.model.Category
import dev.yipchat.core.model.Clip
import dev.yipchat.core.model.LiveStream
import dev.yipchat.core.model.Video
import kotlin.time.Clock
import kotlinx.coroutines.delay

@Composable
private fun Thumb(url: String?, modifier: Modifier = Modifier, overlay: @Composable BoxScope.() -> Unit = {}) {
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        overlay()
    }
}

@Composable
fun rememberUptime(stream: LiveStream): String {
    val text by produceState(initialValue = (Clock.System.now() - stream.startedAt).clock(), stream.startedAt) {
        while (true) {
            delay(30_000)
            value = (Clock.System.now() - stream.startedAt).clock()
        }
    }
    return text
}

@Composable
fun StreamCard(
    stream: LiveStream,
    appearance: AppearanceSettings,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val uptime = if (appearance.showUptime) rememberUptime(stream) else null
    val desc = "${stream.userName}, ${stream.viewerCount} viewers, ${stream.title}"
    when (appearance.cardLayout) {
        CardLayout.GRID -> Column(
            modifier.clip(MaterialTheme.shapes.medium).combinedClickable(onClick = onClick, onLongClick = onLongClick).semantics { contentDescription = desc },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Thumb(stream.thumbnail(640, 360)) {
                LivePill(Modifier.align(Alignment.TopStart).padding(8.dp))
                if (appearance.showViewerCount) Pill(stream.viewerCount.compactCount(), Modifier.align(Alignment.BottomStart).padding(8.dp))
                uptime?.let { Pill(it, Modifier.align(Alignment.BottomEnd).padding(8.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar(stream.profileImageUrl, null, 28.dp)
                Column(Modifier.weight(1f)) {
                    Text(stream.userName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stream.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (appearance.showCategory) stream.gameName?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (appearance.showTags && stream.tags.isNotEmpty()) {
                        Text(stream.tags.take(3).joinToString("  ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        CardLayout.LIST -> Row(
            modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .semantics { contentDescription = desc }.padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.width(160.dp)) {
                Thumb(stream.thumbnail(320, 180)) {
                    if (appearance.showViewerCount) Pill(stream.viewerCount.compactCount(), Modifier.align(Alignment.BottomStart).padding(6.dp))
                    uptime?.let { Pill(it, Modifier.align(Alignment.BottomEnd).padding(6.dp)) }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(stream.profileImageUrl, null, 20.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stream.userName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(stream.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (appearance.showCategory) stream.gameName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
        }
        CardLayout.COMPACT -> Row(
            modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .semantics { contentDescription = desc }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(stream.profileImageUrl, null, 36.dp, live = true)
            Column(Modifier.weight(1f)) {
                Text(stream.userName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    listOfNotNull(stream.gameName.takeIf { appearance.showCategory }, stream.title).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (appearance.showViewerCount) Text(stream.viewerCount.compactCount(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CategoryCard(category: Category, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    Column(modifier.clip(MaterialTheme.shapes.medium).combinedClickable(onClick = onClick, onLongClick = onLongClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AsyncImage(
            category.boxArt(285, 380), contentDescription = category.name, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Text(category.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        category.viewerCount?.let { Text("${it.compactCount()} viewers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun VideoCard(
    video: Video,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watched: Boolean = false,
    inWatchLater: Boolean? = null,
    onWatchLater: () -> Unit = {},
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier.clip(MaterialTheme.shapes.medium).combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Thumb(video.thumbnail(640, 360), Modifier.alpha(if (watched) WATCHED_ALPHA else 1f)) {
                Pill(video.durationSeconds.secondsClock(), Modifier.align(Alignment.BottomEnd).padding(8.dp))
                Pill("${video.viewCount.compactCount()} views", Modifier.align(Alignment.BottomStart).padding(8.dp))
                progress?.takeIf { it > 0f }?.let {
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp), drawStopIndicator = {})
                }
            }
            if (watched) WatchedBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
            CardMenu(menu, { menu = false }, inWatchLater, onWatchLater) { context.shareLink("https://www.twitch.tv/videos/${video.id}", video.title) }
        }
        Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(if (watched) WATCHED_TEXT_ALPHA else 1f))
        Text(video.userName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val WATCHED_ALPHA = 0.45f
private const val WATCHED_TEXT_ALPHA = 0.7f

/** Shown on videos and clips watched to the end. */
@Composable
private fun WatchedBadge(modifier: Modifier) {
    Row(
        modifier.background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CheckCircle, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(" Watched", color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CardMenu(open: Boolean, onDismiss: () -> Unit, inWatchLater: Boolean?, onWatchLater: () -> Unit, onShare: () -> Unit) {
    DropdownMenu(open, onDismissRequest = onDismiss) {
        inWatchLater?.let { saved ->
            DropdownMenuItem(
                text = { Text(if (saved) "Remove from Watch later" else "Watch later") },
                leadingIcon = { Icon(if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkAdd, null) },
                onClick = { onWatchLater(); onDismiss() },
            )
        }
        DropdownMenuItem(
            text = { Text("Share") },
            leadingIcon = { Icon(Icons.Outlined.Share, null) },
            onClick = { onShare(); onDismiss() },
        )
    }
}

@Composable
fun ClipCard(
    clip: Clip,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showChannel: Boolean = false,
    share: Boolean = true,
    watched: Boolean = false,
    inWatchLater: Boolean? = null,
    onWatchLater: () -> Unit = {},
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Column(modifier.clip(MaterialTheme.shapes.medium).combinedClickable(onClick = onClick, onLongClick = { menu = true }), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            Thumb(clip.thumbnailUrl, Modifier.alpha(if (watched) WATCHED_ALPHA else 1f)) {
                Pill(clip.durationSeconds.toLong().secondsClock(), Modifier.align(Alignment.BottomEnd).padding(8.dp))
                Pill("${clip.viewCount.compactCount()} views", Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
            if (watched) WatchedBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
            CardMenu(menu, { menu = false }, inWatchLater, onWatchLater) { context.shareLink(clip.shareUrl, clip.title) }
        }
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(clip.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (showChannel) "${clip.broadcasterName} · clipped by ${clip.creatorName}" else "Clipped by ${clip.creatorName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (share) IconButton(onClick = { context.shareLink(clip.shareUrl, clip.title) }) {
                Icon(Icons.Outlined.Share, "Share clip", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
