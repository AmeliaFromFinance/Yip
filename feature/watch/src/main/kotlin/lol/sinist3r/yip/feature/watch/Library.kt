package lol.sinist3r.yip.feature.watch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lol.sinist3r.yip.core.data.history.HistoryEntry
import lol.sinist3r.yip.core.data.history.HistoryRepository
import lol.sinist3r.yip.core.model.StreamQuality
import lol.sinist3r.yip.core.playback.DownloadItem
import lol.sinist3r.yip.core.playback.DownloadStatus
import lol.sinist3r.yip.core.playback.Downloads

interface LibraryNavigator {
    fun back()
    fun openHistory(entry: HistoryEntry)
    fun openDownload(kind: String, contentId: String)
}

@androidx.annotation.OptIn(UnstableApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(private val history: HistoryRepository, private val downloads: Downloads) : ViewModel() {
    val entries = history.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val downloadItems = downloads.items
    fun remove(key: String) = viewModelScope.launch { history.remove(key) }
    fun clear() = viewModelScope.launch { history.clear() }
    fun removeDownload(id: String) = downloads.remove(id)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(nav: LibraryNavigator, startTab: Int = 0, viewModel: LibraryViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val items by viewModel.downloadItems.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Library") },
                    navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = { if (tab == 0 && entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") } },
                )
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("History") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Downloads") })
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                tab == 0 && entries.isEmpty() -> LibraryEmpty("Nothing watched yet.")
                tab == 0 -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(entries, key = { it.key }) { e ->
                        MediaRow(
                            e.thumbnailUrl, e.title.ifBlank { e.channelName }, "${e.channelName} · ${e.kind.name.lowercase().replaceFirstChar(Char::uppercase)} · ${ago(e.updatedAt)}",
                            progress = e.progress.takeIf { e.durationSeconds > 0 }, onClick = { nav.openHistory(e) },
                        ) { IconButton(onClick = { viewModel.remove(e.key) }) { Icon(Icons.Outlined.Delete, "Remove from history") } }
                    }
                }
                items.isEmpty() -> LibraryEmpty("No downloads. Open a video or clip and tap the download button.")
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(items, key = { it.id }) { d ->
                        MediaRow(
                            d.meta.thumbnailUrl, d.meta.title.ifBlank { d.meta.channelName }, "${d.meta.channelName.ifBlank { "Unknown channel" }} · ${d.meta.quality} · ${statusText(d)}",
                            avatar = d.meta.channelAvatarUrl,
                            progress = (d.percent / 100f).takeIf { d.status == DownloadStatus.DOWNLOADING || d.status == DownloadStatus.QUEUED },
                            onClick = { if (d.status == DownloadStatus.DONE) nav.openDownload(d.meta.kind, d.meta.contentId) },
                        ) { IconButton(onClick = { viewModel.removeDownload(d.id) }) { Icon(Icons.Outlined.Delete, "Delete download") } }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear watch history?") },
        text = { Text("Removes everything here and from Continue watching. Resume points are lost.") },
        confirmButton = { TextButton(onClick = { viewModel.clear(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun MediaRow(thumb: String?, title: String, subtitle: String, progress: Float?, onClick: () -> Unit, avatar: String? = null, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            AsyncImage(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter), drawStopIndicator = {}) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                avatar?.let { AsyncImage(it, null, Modifier.padding(end = 6.dp).width(18.dp).aspectRatio(1f).clip(androidx.compose.foundation.shape.CircleShape)) }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

@Composable
private fun LibraryEmpty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun statusText(d: DownloadItem) = when (d.status) {
    DownloadStatus.DONE -> sizeText(d.bytes)
    DownloadStatus.DOWNLOADING -> "${d.percent}%"
    DownloadStatus.QUEUED -> "Waiting"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.FAILED -> "Failed"
}

internal fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}

internal fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - at) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "${m}m ago"
        m < 60 * 24 -> "${m / 60}h ago"
        m < 60 * 24 * 7 -> "${m / (60 * 24)}d ago"
        else -> java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    }
}

/** Pick a quality to save. Shows progress and Remove once started. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(qualities: List<StreamQuality>, durationSeconds: Long, existing: DownloadItem?, onPick: (StreamQuality) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Download", style = MaterialTheme.typography.titleMedium)
            if (existing != null) {
                Text("${existing.meta.quality} · ${statusText(existing)}", style = MaterialTheme.typography.bodyLarge)
                if (existing.status != DownloadStatus.DONE) LinearProgressIndicator(progress = { existing.percent / 100f }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { onRemove(); onDismiss() }) { Icon(Icons.Outlined.Delete, null); Text(if (existing.status == DownloadStatus.DONE) " Delete download" else " Cancel") }
                return@Column
            }
            Text("Saved in Yip for watching offline. Find it under Library → Downloads.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            qualities.filter { it.id != "offline" }.forEach { q ->
                val size = q.bandwidth?.takeIf { durationSeconds > 0 }?.let { " · about ${sizeText(it / 8 * durationSeconds)}" }.orEmpty()
                Text(
                    q.label + size,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(q); onDismiss() }.padding(vertical = 12.dp, horizontal = 4.dp),
                )
            }
        }
    }
}
