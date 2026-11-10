package dev.yipchat.feature.browse

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yipchat.core.common.isPowerSaving
import dev.yipchat.core.data.playback.PlaybackRepository
import dev.yipchat.core.data.settings.SettingsRepository
import dev.yipchat.core.model.AppSettings
import dev.yipchat.core.model.LiveStream
import dev.yipchat.core.playback.PlayerFactory
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Pins and hides. Pinned favourites share the list used for "Only favourites" notifications. */
@HiltViewModel
@androidx.annotation.OptIn(UnstableApi::class)
class QuickActionsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val factory: PlayerFactory,
    private val playbackRepo: PlaybackRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : ViewModel() {
    val state = settings.settings

    /** Muted, low-quality player for the long-press preview. */
    val preview = MutableStateFlow<ExoPlayer?>(null)
    private var previewJob: Job? = null

    fun startPreview(login: String) {
        stopPreview()
        val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered != false
        if (settings.settings.value.data.dataSaver && metered) return
        if (settings.settings.value.data.batterySaver && context.isPowerSaving()) return
        previewJob = viewModelScope.launch {
            val resolved = dev.yipchat.core.common.attempt { playbackRepo.resolveLive(login) }.getOrNull() ?: return@launch
            val url = resolved.qualities.filterNot { it.isAudioOnly }.lastOrNull { (it.height ?: 0) >= 160 }?.url ?: resolved.masterPlaylistUrl
            val p = factory.create(settings.settings.value.player, forMultiView = true)
            p.volume = 0f
            p.setMediaSource(factory.hlsSource(url, live = true, settings.settings.value.player.bufferProfile, MediaMetadata.EMPTY))
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

    override fun onCleared() = stopPreview()

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

/** What a long-press acts on. [category] is null with nothing live; [stream] adds a muted preview. */
data class QuickTarget(val login: String, val displayName: String, val category: String? = null, val stream: LiveStream? = null)

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun QuickActionsSheet(
    target: QuickTarget,
    onAddToMultiView: ((String) -> Unit)?,
    onDismiss: () -> Unit,
    allowHide: Boolean = true,
    onWatch: ((LiveStream) -> Unit)? = null,
    viewModel: QuickActionsViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val pinned = s.isPinned(target.login)
    val player by viewModel.preview.collectAsStateWithLifecycle()
    DisposableEffect(target.login) {
        if (target.stream != null) viewModel.startPreview(target.login)
        onDispose { viewModel.stopPreview() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            target.stream?.let { st ->
                Box(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)
                        .background(Color.Black)
                        .clickable(enabled = onWatch != null, onClickLabel = "Watch ${target.displayName}") { onDismiss(); onWatch?.invoke(st) },
                ) {
                    AsyncImage(st.thumbnail(640, 360), null, Modifier.fillMaxSize())
                    player?.let { p -> PlayerSurface(player = p, surfaceType = SURFACE_TYPE_TEXTURE_VIEW, modifier = Modifier.fillMaxSize()) }
                }
                Text(st.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(target.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (target.stream != null && onWatch != null) FilledTonalButton(onClick = { onDismiss(); onWatch(target.stream) }) { Text("Watch") }
            }
            ListItem(
                headlineContent = { Text(if (pinned) "Unpin from Following" else "Pin to top of Following") },
                leadingContent = { Icon(if (pinned) Icons.Outlined.Star else Icons.Outlined.StarBorder, null) },
                modifier = Modifier.clickable { viewModel.togglePin(target.login); onDismiss() },
            )
            if (onAddToMultiView != null) ListItem(
                headlineContent = { Text("Add to multi-view") },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.ViewQuilt, null) },
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
