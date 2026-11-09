package lol.sinist3r.yip.feature.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import lol.sinist3r.yip.core.designsystem.chat.EmoteImage
import lol.sinist3r.yip.core.model.Emote
import lol.sinist3r.yip.core.model.EmoteProvider

/** Long-press an emote: see it large, where it's from, who made it, and favourite it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmoteCardSheet(
    emote: Emote,
    animate: Boolean,
    favourite: Boolean,
    onToggleFavourite: () -> Unit,
    onInsert: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EmoteImage(emote.copy(urls = listOf(emote.url(4))), animate = animate, modifier = Modifier.size(112.dp))
            Text(emote.code, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                listOfNotNull(
                    emote.provider.label() + " · " + when { emote.isPersonal -> "personal"; emote.isChannel -> "channel"; else -> "global" },
                    if (emote.animated) "animated" else null,
                    if (emote.zeroWidth) "zero-width" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            emote.ownerName?.let { Text("By $it", style = MaterialTheme.typography.bodyMedium) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                FilledTonalButton(onClick = onToggleFavourite) {
                    Icon(if (favourite) Icons.Outlined.Star else Icons.Outlined.StarBorder, null, Modifier.size(18.dp))
                    Text(if (favourite) " Favourited" else " Favourite")
                }
                onInsert?.let { insert -> OutlinedButton(onClick = { insert(); onDismiss() }) { Text("Insert") } }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(emote.code)); onDismiss() }) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Text(" Copy")
                }
                emote.pageUrl()?.let { url -> OutlinedButton(onClick = { uri.openUri(url) }) { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp)); Text(" ${emote.provider.label()}") } }
            }
        }
    }
}

internal fun EmoteProvider.label() = when (this) {
    EmoteProvider.TWITCH -> "Twitch"
    EmoteProvider.SEVENTV -> "7TV"
    EmoteProvider.BTTV -> "BTTV"
    EmoteProvider.FFZ -> "FFZ"
}

private fun Emote.pageUrl(): String? = when (provider) {
    EmoteProvider.SEVENTV -> "https://7tv.app/emotes/$id"
    EmoteProvider.BTTV -> "https://betterttv.com/emotes/$id"
    EmoteProvider.FFZ -> "https://www.frankerfacez.com/emoticon/$id"
    EmoteProvider.TWITCH -> null
}
