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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

sealed interface ClipState {
    data object Creating : ClipState
    data class Ready(val id: String) : ClipState {
        val url get() = "https://clips.twitch.tv/$id"
        val editUrl get() = "https://clips.twitch.tv/$id/edit"
    }
    data class Failed(val reason: String) : ClipState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipSheet(state: ClipState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                ClipState.Creating -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("Clipping the last 30 seconds…", style = MaterialTheme.typography.bodyLarge)
                }
                is ClipState.Failed -> {
                    Text("Couldn't make a clip", style = MaterialTheme.typography.titleMedium)
                    Text(state.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is ClipState.Ready -> {
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
