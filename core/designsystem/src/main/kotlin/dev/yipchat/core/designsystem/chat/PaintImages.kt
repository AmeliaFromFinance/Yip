package dev.yipchat.core.designsystem.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.gif.AnimatedImageDecoder
import coil3.gif.MovieDrawable
import coil3.gif.repeatCount
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

/**
 * One running animation per paint, shared by every name using it. Each tick draws the next
 * frame into the same bitmap; names sample it through a bitmap shader, so a new frame only
 * needs a redraw, never a text relayout.
 */
@Stable
internal class AnimatedPaint(private val drawable: Drawable, width: Int, height: Int) {
    private val bitmap: Bitmap = createBitmap(width, height)
    private val canvas = Canvas(bitmap)
    val image: ImageBitmap = bitmap.asImageBitmap()

    /** Read during draw so only the draw phase re-runs on each frame. */
    val frame = mutableIntStateOf(0)

    private var users = 0
    private var job: Job? = null

    // Owned by the paint, not a chat line: lines come and go, and the loop must outlive them.
    private val loopScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        drawable.setBounds(0, 0, width, height)
        renderFrame()
    }

    private fun renderFrame() {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawable.draw(canvas) // software canvas: advances the animation by wall-clock time
        frame.intValue++
    }

    // Main thread only: the bitmap is changed between frames, never while the UI is recording one.
    fun acquire() {
        users++
        if (job?.isActive == true) return
        (drawable as? Animatable)?.start()
        job = loopScope.launch {
            while (isActive) {
                delay(FRAME_MS)
                renderFrame()
            }
        }
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            job?.cancel()
            job = null
            (drawable as? Animatable)?.stop()
        }
    }

    companion object {
        private const val FRAME_MS = 40L // ~25 fps; paints are slow shimmers
    }
}

internal object AnimatedPaints {
    // Small: each entry is one 3x frame (~100 KB) plus its decoder.
    private val cache = LruCache<String, AnimatedPaint>(8)
    private val loading = HashMap<String, Deferred<AnimatedPaint?>>()

    fun cached(url: String): AnimatedPaint? = cache.get(url)

    suspend fun load(context: Context, url: String): AnimatedPaint? {
        cache.get(url)?.let { return it }
        val pending = synchronized(loading) {
            loading.getOrPut(url) {
                CoroutineScope(Dispatchers.Main.immediate).async {
                    val request = ImageRequest.Builder(context).data(url)
                        .decoderFactory(AnimatedImageDecoder.Factory())
                        .repeatCount(MovieDrawable.REPEAT_INFINITE)
                        .memoryCachePolicy(CachePolicy.DISABLED) // the drawable holds playback state; never share it
                        .build()
                    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@async null
                    val d = result.image.asDrawable(context.resources)
                    val w = d.intrinsicWidth.takeIf { it > 0 } ?: return@async null
                    val h = d.intrinsicHeight.takeIf { it > 0 } ?: return@async null
                    AnimatedPaint(d, w, h).also { cache.put(url, it) }
                }
            }
        }
        return try { pending.await() } finally { synchronized(loading) { loading.remove(url) } }
    }
}

/** Starts the paint animation while this name is on screen and the app is in the foreground. */
@Composable
internal fun rememberAnimatedPaint(url: String?): AnimatedPaint? {
    val context = LocalContext.current
    var paint by remember(url) { mutableStateOf(url?.let(AnimatedPaints::cached)) }
    LaunchedEffect(url) { if (url != null && paint == null) paint = AnimatedPaints.load(context, url) }
    val current = paint ?: return null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(current, lifecycle) {
        var held = false
        fun hold(on: Boolean) {
            if (on && !held) current.acquire() else if (!on && held) current.release()
            held = on
        }
        val observer = LifecycleEventObserver { _, _ -> hold(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            hold(false)
        }
    }
    return current
}

@Composable
internal fun rememberPaintImage(url: String?): ImageBitmap? {
    val context = LocalContext.current
    var image by remember(url) { mutableStateOf(url?.let(PaintImages::cached)) }
    LaunchedEffect(url) { if (url != null && image == null) image = PaintImages.load(context, url) }
    return image
}
