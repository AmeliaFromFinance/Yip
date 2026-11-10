package lol.sinist3r.yip.feature.watch

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import lol.sinist3r.yip.core.common.secondsClock

sealed interface ClipState {
    /** Pick length and title before anything is made. Clips the last [seconds] of the stream. */
    data class LiveSetup(val title: String, val seconds: Int = 30, val error: String? = null) : ClipState
    /** Pick a 5-60s range of the VOD and a title. [windowStart]..[windowEnd] is what the slider covers. */
    data class VodSetup(
        val title: String,
        val windowStart: Float,
        val windowEnd: Float,
        val start: Float,
        val end: Float,
        val error: String? = null,
    ) : ClipState
    data object Creating : ClipState
    /** [draft]: made, but needs finishing on Twitch (trim, title, publish). */
    data class Ready(val id: String, val draft: Boolean = false) : ClipState {
        val url get() = "https://clips.twitch.tv/$id"
        val editUrl get() = "https://clips.twitch.tv/$id/edit"
    }
    data class Failed(val reason: String) : ClipState
}

internal const val MIN_CLIP = 5f
internal const val MAX_CLIP = 60f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipSheet(
    state: ClipState,
    onDismiss: () -> Unit,
    onCreateLive: (title: String, seconds: Int) -> Unit = { _, _ -> },
    onCreateVod: (title: String, start: Float, end: Float) -> Unit = { _, _, _ -> },
    onPreviewVod: (start: Float, end: Float) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                is ClipState.LiveSetup -> LiveClipSetup(state, onCreateLive)
                is ClipState.VodSetup -> VodClipSetup(state, onCreateVod, onPreviewVod)
                ClipState.Creating -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("Making your clip…", style = MaterialTheme.typography.bodyLarge)
                }
                is ClipState.Failed -> {
                    Text("Couldn't make a clip", style = MaterialTheme.typography.titleMedium)
                    Text(state.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is ClipState.Ready -> if (state.draft) {
                    Text("Clip saved as a draft", style = MaterialTheme.typography.titleMedium)
                    Text("Twitch didn't publish it straight away. Open it on Twitch to finish.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { uri.openUri(state.editUrl) }) { Icon(Icons.Outlined.Edit, null); Text(" Open on Twitch") }
                } else {
                    Text("Clip made", style = MaterialTheme.typography.titleMedium)
                    Text("It can take a few seconds before the link plays.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, state.url), null))
                        }) { Icon(Icons.Outlined.Share, null); Text(" Share") }
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(state.url)) }) { Icon(Icons.Outlined.ContentCopy, null); Text(" Copy") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleField(title: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = title, onValueChange = { onChange(it.take(100)) },
        label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("${title.length}/100") },
    )
}

@Composable
private fun LiveClipSetup(state: ClipState.LiveSetup, onCreate: (String, Int) -> Unit) {
    var title by rememberSaveable { mutableStateOf(state.title) }
    var seconds by remember { mutableFloatStateOf(state.seconds.toFloat()) }
    Text("New clip", style = MaterialTheme.typography.titleMedium)
    Text("Clips the last ${seconds.toInt()} seconds of the stream, from where you tap Create.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Slider(value = seconds, onValueChange = { seconds = it }, valueRange = MIN_CLIP..MAX_CLIP, steps = 10)
    TitleField(title) { title = it }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = { onCreate(title, seconds.toInt()) }, modifier = Modifier.fillMaxWidth()) { Text("Create clip") }
}

@Composable
private fun VodClipSetup(state: ClipState.VodSetup, onCreate: (String, Float, Float) -> Unit, onPreview: (Float, Float) -> Unit) {
    var title by rememberSaveable { mutableStateOf(state.title) }
    var range by remember { mutableStateOf(state.start..state.end) }
    Text("New clip", style = MaterialTheme.typography.titleMedium)
    RangeSlider(
        value = range,
        onValueChange = { r ->
            // Keep it 5 to 60 seconds; the end you didn't drag moves along.
            var s = r.start; var e = r.endInclusive
            if (e - s < MIN_CLIP) { if (s != range.start) s = (e - MIN_CLIP).coerceAtLeast(state.windowStart) else e = (s + MIN_CLIP).coerceAtMost(state.windowEnd) }
            if (e - s > MAX_CLIP) { if (s != range.start) e = s + MAX_CLIP else s = e - MAX_CLIP }
            range = s..e
        },
        valueRange = state.windowStart..state.windowEnd,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${range.start.toLong().secondsClock()} to ${range.endInclusive.toLong().secondsClock()} · ${(range.endInclusive - range.start).toInt()}s",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { onPreview(range.start, range.endInclusive) }) { Icon(Icons.Outlined.PlayArrow, null); Text(" Preview") }
    }
    TitleField(title) { title = it }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = { onCreate(title, range.start, range.endInclusive) }, modifier = Modifier.fillMaxWidth()) { Text("Create clip") }
}
