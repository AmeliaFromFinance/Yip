package lol.sinist3r.yip.feature.watch

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

sealed interface ClipState {
    data object Creating : ClipState
    /** [draft]: made, but needs finishing on Twitch (trim, title, publish). */
    data class Ready(val id: String, val draft: Boolean = false) : ClipState {
        val url get() = "https://clips.twitch.tv/$id"
        val editUrl get() = "https://clips.twitch.tv/$id/edit"
    }
    data class Failed(val reason: String) : ClipState
    /** A draft to trim: [rawDuration] seconds of video at [videoUrl]. */
    data class Editing(
        val slug: String,
        val videoUrl: String,
        val rawDuration: Double,
        val suggestedStart: Double,
        val title: String,
        val publishing: Boolean = false,
        val error: String? = null,
    ) : ClipState
}

private const val MIN_CLIP = 5f
private const val MAX_CLIP = 60f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipSheet(state: ClipState, onDismiss: () -> Unit, onPublish: (ClipState.Editing, String, Double, Double) -> Unit = { _, _, _, _ -> }) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                ClipState.Creating -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("Making a clip…", style = MaterialTheme.typography.bodyLarge)
                }
                is ClipState.Editing -> ClipEditor(state, onPublish)
                is ClipState.Failed -> {
                    Text("Couldn't make a clip", style = MaterialTheme.typography.titleMedium)
                    Text(state.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is ClipState.Ready -> if (state.draft) {
                    Text("Clip draft made", style = MaterialTheme.typography.titleMedium)
                    Text("Twitch didn't send the video to edit here in time. Finish trimming and publishing on Twitch.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { uri.openUri(state.editUrl) }) { Icon(Icons.Outlined.Edit, null); Text(" Edit on Twitch") }
                } else {
                    Text("Clip made", style = MaterialTheme.typography.titleMedium)
                    Text("It can take a few seconds before the link plays.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, state.url), null))
                        }) { Icon(Icons.Outlined.Share, null); Text(" Share") }
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(state.url)) }) { Icon(Icons.Outlined.ContentCopy, null); Text(" Copy") }
                        OutlinedButton(onClick = { uri.openUri(state.editUrl) }) { Icon(Icons.Outlined.Edit, null); Text(" Title") }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipEditor(state: ClipState.Editing, onPublish: (ClipState.Editing, String, Double, Double) -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var total by androidx.compose.runtime.remember(state.slug) { androidx.compose.runtime.mutableFloatStateOf(state.rawDuration.toFloat().coerceAtLeast(MIN_CLIP)) }
    var range by androidx.compose.runtime.remember(state.slug) {
        val start = state.suggestedStart.toFloat().coerceIn(0f, (total - MIN_CLIP).coerceAtLeast(0f))
        androidx.compose.runtime.mutableStateOf(start..(start + 30f).coerceAtMost(total))
    }
    var title by androidx.compose.runtime.saveable.rememberSaveable(state.slug) { androidx.compose.runtime.mutableStateOf(state.title) }

    // Preview loops the picked part.
    val preview = androidx.compose.runtime.remember(state.videoUrl) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(state.videoUrl))
            volume = 0.6f
            prepare()
            playWhenReady = true
        }
    }
    androidx.compose.runtime.DisposableEffect(preview) { onDispose { preview.release() } }
    androidx.compose.runtime.LaunchedEffect(range) {
        preview.seekTo((range.start * 1000).toLong())
        while (true) {
            // The draft's real length is only known once it loads.
            val d = preview.duration
            if (d > 0 && d != androidx.media3.common.C.TIME_UNSET && kotlin.math.abs(d / 1000f - total) > 0.5f) {
                total = d / 1000f
                if (range.endInclusive > total) range = (total - (range.endInclusive - range.start)).coerceAtLeast(0f)..total
            }
            if (preview.currentPosition >= (range.endInclusive * 1000).toLong()) preview.seekTo((range.start * 1000).toLong())
            kotlinx.coroutines.delay(100)
        }
    }

    Text("Edit clip", style = MaterialTheme.typography.titleMedium)
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth().aspectRatio16x9().background(androidx.compose.ui.graphics.Color.Black, MaterialTheme.shapes.medium),
    ) {
        androidx.media3.ui.compose.PlayerSurface(player = preview, modifier = Modifier.fillMaxWidth().aspectRatio16x9())
    }
    androidx.compose.material3.RangeSlider(
        value = range,
        onValueChange = { r ->
            // Keep it between 5 and 60 seconds; move the other end along if needed.
            var s = r.start; var e = r.endInclusive
            if (e - s < MIN_CLIP) { if (s != range.start) s = (e - MIN_CLIP).coerceAtLeast(0f) else e = (s + MIN_CLIP).coerceAtMost(total) }
            if (e - s > MAX_CLIP) { if (s != range.start) e = s + MAX_CLIP else s = e - MAX_CLIP }
            range = s..e
        },
        valueRange = 0f..total,
    )
    Text(
        "${clock(range.start)} to ${clock(range.endInclusive)} · ${"%.0f".format(range.endInclusive - range.start)}s",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    androidx.compose.material3.OutlinedTextField(
        value = title, onValueChange = { title = it.take(100) },
        label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            enabled = !state.publishing,
            onClick = { onPublish(state, title, range.start.toDouble(), (range.endInclusive - range.start).toDouble()) },
        ) { Text(if (state.publishing) "Publishing…" else "Publish") }
        androidx.compose.material3.TextButton(onClick = { uri.openUri("https://clips.twitch.tv/${state.slug}/edit") }) { Text("Edit on Twitch") }
    }
    Text("Not published until you tap Publish.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun Modifier.aspectRatio16x9() = aspectRatio(16f / 9f)

private fun clock(s: Float): String = "%d:%02d".format(s.toInt() / 60, s.toInt() % 60)

