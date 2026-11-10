package dev.yipchat.core.designsystem.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.yipchat.core.model.LinkPreview

/** Loads a preview for a link, or null when there isn't one. Provided by screens that show chat. */
fun interface LinkPreviewLoader {
    fun cached(url: String): LinkPreview? = null
    suspend fun load(url: String): LinkPreview?
}

val LocalLinkPreviews = compositionLocalOf<LinkPreviewLoader?> { null }

@Composable
internal fun LinkPreviewCard(url: String, onOpen: (String) -> Unit) {
    val loader = LocalLinkPreviews.current ?: return
    var preview by remember(url) { mutableStateOf(loader.cached(url)) }
    LaunchedEffect(url) { if (preview == null) preview = loader.load(url) }
    val p = preview ?: return
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 4.dp).widthIn(max = 420.dp).clip(RoundedCornerShape(8.dp)).clickable { onOpen(p.url) },
    ) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            p.imageUrl?.let {
                AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(width = 80.dp, height = 45.dp).clip(RoundedCornerShape(4.dp)))
            }
            Column(Modifier.padding(start = 8.dp)) {
                Text(p.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                p.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
