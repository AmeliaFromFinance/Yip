package lol.sinist3r.yip.core.designsystem.chat

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

/** Bitmaps for image-based 7TV paints. Few distinct paints are on screen at once, so a small cache is plenty. */
internal object PaintImages {
    private val cache = LruCache<String, ImageBitmap>(48)

    fun cached(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(context: Context, url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        // Software bitmap: shaders can't sample hardware ones.
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        return result.image.toBitmap().asImageBitmap().also { cache.put(url, it) }
    }
}

@Composable
internal fun rememberPaintImage(url: String?): ImageBitmap? {
    val context = LocalContext.current
    var image by remember(url) { mutableStateOf(url?.let(PaintImages::cached)) }
    LaunchedEffect(url) { if (url != null && image == null) image = PaintImages.load(context, url) }
    return image
}
