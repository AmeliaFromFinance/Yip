package lol.sinist3r.yip.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.model.AppSettings

/** Pins and hides. Pinned favourites share the list used for "Only favourites" notifications. */
@HiltViewModel
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QuickActionsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val factory: lol.sinist3r.yip.core.playback.PlayerFactory,
    private val playbackRepo: lol.sinist3r.yip.core.data.playback.PlaybackRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    val state = settings.settings

    /** Muted, low-quality player for the long-press preview. */
    val preview = kotlinx.coroutines.flow.MutableStateFlow<androidx.media3.exoplayer.ExoPlayer?>(null)
    private var previewJob: kotlinx.coroutines.Job? = null

    fun startPreview(login: String) {
        stopPreview()
        // Data saver on mobile data: no preview.
        val metered = context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
        if (settings.settings.value.data.dataSaver && metered) return
        previewJob = viewModelScope.launch {
            val resolved = lol.sinist3r.yip.core.common.attempt { playbackRepo.resolveLive(login) }.getOrNull() ?: return@launch
            val url = resolved.qualities.filterNot { it.isAudioOnly }.lastOrNull { (it.height ?: 0) >= 160 }?.url ?: resolved.masterPlaylistUrl
            val p = factory.create(settings.settings.value.player, forMultiView = true)
            p.volume = 0f
            p.setMediaSource(factory.hlsSource(url, live = true, settings.settings.value.player.bufferProfile, androidx.media3.common.MediaMetadata.EMPTY))
            p.prepare()
            p.play()
            preview.value = p
        }
    }

    fun stopPreview() {
        previewJob?.cancel()
        preview.value?.release()
        preview.value = null
    }

    override fun onCleared() { stopPreview(); super.onCleared() }

    fun togglePin(login: String) = viewModelScope.launch {
        val l = login.lowercase()
        settings.update { s ->
            val fav = s.notifications.favourites
            s.copy(notifications = s.notifications.copy(favourites = if (l in fav) fav - l else fav + l))
        }
    }

    fun hideChannel(login: String) = viewModelScope.launch {
        val l = login.lowercase()
        settings.update { s -> s.copy(general = s.general.copy(hiddenChannels = (s.general.hiddenChannels + l).distinct())) }
    }

    fun hideCategory(name: String) = viewModelScope.launch {
        val n = name.trim().lowercase().takeIf { it.isNotEmpty() } ?: return@launch
        settings.update { s -> s.copy(general = s.general.copy(hiddenCategories = (s.general.hiddenCategories + n).distinct())) }
    }
}

fun AppSettings.isPinned(login: String) = login.lowercase() in notifications.favourites
fun AppSettings.isHiddenChannel(login: String) = login.lowercase() in general.hiddenChannels
fun AppSettings.isHiddenCategory(name: String?) = name != null && name.lowercase() in general.hiddenCategories

/** What a long-press acts on. [category] is null for channels with nothing live. */
/** [stream] is set for live streams; long-press then shows a muted preview. */
data class QuickTarget(val login: String, val displayName: String, val category: String? = null, val stream: lol.sinist3r.yip.core.model.LiveStream? = null)

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun QuickActionsSheet(
    target: QuickTarget,
    onAddToMultiView: ((String) -> Unit)?,
    onDismiss: () -> Unit,
    allowHide: Boolean = true,
    onWatch: ((lol.sinist3r.yip.core.model.LiveStream) -> Unit)? = null,
    viewModel: QuickActionsViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val pinned = s.isPinned(target.login)
    val player by viewModel.preview.collectAsStateWithLifecycle()
    androidx.compose.runtime.DisposableEffect(target.login) {
        if (target.stream != null) viewModel.startPreview(target.login)
        onDispose { viewModel.stopPreview() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            target.stream?.let { st ->
                androidx.compose.foundation.layout.Box(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)
                        .background(androidx.compose.ui.graphics.Color.Black)
                        .clickable(enabled = onWatch != null, onClickLabel = "Watch ${target.displayName}") { onDismiss(); onWatch?.invoke(st) },
                ) {
                    // Thumbnail until the preview starts.
                    coil3.compose.AsyncImage(st.thumbnail(640, 360), null, Modifier.fillMaxSize())
                    player?.let { p -> androidx.media3.ui.compose.PlayerSurface(player = p, surfaceType = androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW, modifier = Modifier.fillMaxSize()) }
                }
                Text(st.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(target.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (target.stream != null && onWatch != null) androidx.compose.material3.FilledTonalButton(onClick = { onDismiss(); onWatch(target.stream) }) { Text("Watch") }
            }
            ListItem(
                headlineContent = { Text(if (pinned) "Unpin from Following" else "Pin to top of Following") },
                leadingContent = { Icon(if (pinned) Icons.Outlined.Star else Icons.Outlined.StarBorder, null) },
                modifier = Modifier.clickable { viewModel.togglePin(target.login); onDismiss() },
            )
            if (onAddToMultiView != null) ListItem(
                headlineContent = { Text("Add to multi-view") },
                leadingContent = { Icon(Icons.Outlined.ViewQuilt, null) },
                modifier = Modifier.clickable { onAddToMultiView(target.login); onDismiss() },
            )
            if (allowHide) {
                ListItem(
                    headlineContent = { Text("Hide ${target.displayName} from Browse") },
                    leadingContent = { Icon(Icons.Outlined.VisibilityOff, null) },
                    modifier = Modifier.clickable { viewModel.hideChannel(target.login); onDismiss() },
                )
                target.category?.takeIf { it.isNotBlank() }?.let { cat ->
                    ListItem(
                        headlineContent = { Text("Hide $cat from Browse") },
                        leadingContent = { Icon(Icons.Outlined.VisibilityOff, null) },
                        modifier = Modifier.clickable { viewModel.hideCategory(cat); onDismiss() },
                    )
                }
                Text(
                    "Undo under Settings → General → Hidden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}
