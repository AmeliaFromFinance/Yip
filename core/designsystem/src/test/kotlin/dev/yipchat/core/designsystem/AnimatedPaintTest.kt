package dev.yipchat.core.designsystem

import android.graphics.drawable.ColorDrawable
import android.os.Looper
import dev.yipchat.core.designsystem.chat.AnimatedPaint
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The frame loop must outlive whichever chat line happened to start it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnimatedPaintTest {
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun keepsAnimatingWhenFirstHolderLeaves() {
        val paint = AnimatedPaint(ColorDrawable(0xFFFF0000.toInt()), 8, 8)
        // Each chat line showing the paint has its own composition scope.
        val first = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val second = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        paint.acquire()
        paint.acquire()
        idle(200)
        // First line scrolls off: its composition scope is cancelled and it releases.
        first.cancel()
        paint.release()
        val before = paint.frame.intValue
        idle(400)
        assertTrue("frames stopped at $before", paint.frame.intValue > before + 3)
        paint.release()
        second.cancel()
    }

    @Test fun stopsWhenNobodyHoldsIt() {
        val paint = AnimatedPaint(ColorDrawable(0xFFFF0000.toInt()), 8, 8)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        paint.acquire()
        idle(200)
        paint.release()
        val stopped = paint.frame.intValue
        idle(400)
        assertEquals(stopped, paint.frame.intValue)
        scope.cancel()
    }
}
