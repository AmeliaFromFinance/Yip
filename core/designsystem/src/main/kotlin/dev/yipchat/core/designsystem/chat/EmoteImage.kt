package dev.yipchat.core.designsystem.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest
import dev.yipchat.core.model.Emote

/** An emote with optional zero-width overlays stacked on top. */
@Composable
fun EmoteImage(emote: Emote, overlays: List<Emote> = emptyList(), animate: Boolean = true, modifier: Modifier = Modifier) {
    Box(modifier) {
        SingleEmote(emote, animate)
        overlays.forEach { SingleEmote(it, animate) }
    }
}

@Composable
private fun SingleEmote(emote: Emote, animate: Boolean) {
    val context = LocalContext.current
    // Built once per emote so Coil doesn't re-resolve it on every recomposition.
    val request = remember(emote.id, emote.provider, animate) {
        ImageRequest.Builder(context)
            .data(emote.url(2))
            .memoryCacheKey("${emote.provider}:${emote.id}:${if (animate) "a" else "s"}")
            .apply { if (!animate && emote.animated) decoderFactory(BitmapFactoryDecoder.Factory()) }
            .build()
    }
    AsyncImage(model = request, contentDescription = emote.code, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
}
