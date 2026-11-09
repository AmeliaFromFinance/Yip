package lol.sinist3r.yip.core.designsystem

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.PorterDuff
import android.graphics.drawable.AnimatedImageDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import androidx.compose.ui.graphics.asAndroidBitmap
import coil3.ImageLoader
import coil3.asDrawable
import coil3.gif.AnimatedImageDecoder
import coil3.gif.MovieDrawable
import coil3.gif.repeatCount
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import lol.sinist3r.yip.core.designsystem.chat.AnimatedPaint
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks that drawing an animated WebP into a bitmap advances frames, which animated paints rely on. */
@RunWith(AndroidJUnit4::class)
class AnimatedPaintDeviceTest {
    @Test fun softwareCanvasAdvancesFrames() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val bytes = ctx.assets.open("paint_anim.webp").use { it.readBytes() }
        val frames = HashSet<Int>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) as AnimatedImageDrawable
            val bmp = Bitmap.createBitmap(d.intrinsicWidth, d.intrinsicHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, bmp.width, bmp.height)
            d.start()
            repeat(30) {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                d.draw(canvas)
                frames += bmp.asSequence().hashCode()
                Thread.sleep(40)
            }
        }
        assertTrue("only ${frames.size} distinct frames", frames.size > 3)
    }

    /** Same path the app uses: Coil's animated decoder feeding AnimatedPaint's frame loop. */
    @Test fun animatedPaintThroughCoil() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.context
        val bytes = ctx.assets.open("paint_anim.webp").use { it.readBytes() }
        val loader = ImageLoader.Builder(ctx).components { add(AnimatedImageDecoder.Factory()) }.build()
        val result = runBlocking {
            loader.execute(ImageRequest.Builder(ctx).data(bytes).repeatCount(MovieDrawable.REPEAT_INFINITE).build())
        } as SuccessResult
        val d = result.image.asDrawable(ctx.resources)
        lateinit var paint: AnimatedPaint
        inst.runOnMainSync { paint = AnimatedPaint(d, d.intrinsicWidth, d.intrinsicHeight) }
        val scope = CoroutineScope(Dispatchers.Main)
        inst.runOnMainSync { paint.acquire(scope) }
        val frames = HashSet<Int>()
        repeat(30) {
            Thread.sleep(40)
            inst.runOnMainSync { frames += paint.image.asAndroidBitmap().asSequence().hashCode() }
        }
        inst.runOnMainSync { paint.release() }
        scope.cancel()
        assertTrue("only ${frames.size} distinct frames", frames.size > 3)
    }

    private fun Bitmap.asSequence(): List<Int> = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }.toList()
}
