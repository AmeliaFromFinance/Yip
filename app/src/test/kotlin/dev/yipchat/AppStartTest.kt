package dev.yipchat

import android.os.Looper
import java.security.KeyStoreException
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Starts the real Application and fails if anything it launches crashes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = YipApplication::class)
class AppStartTest {
    @Test fun startsWithoutCrashing() {
        var crash: Throwable? = null
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        // Robolectric has no AndroidKeyStore, so encrypted account storage can't open here. Not a device issue.
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            if (generateSequence(e) { it.cause }.none { it is KeyStoreException }) crash = crash ?: e
        }
        try {
            RuntimeEnvironment.getApplication() // onCreate has run
            // Let background startup work run and post back to the main thread.
            repeat(20) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(100)
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertNull("Crashed on start: $crash", crash)
    }
}
