package lol.sinist3r.yip.feature.watch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranslateTest {
    /** No on-device translator here: it must say so, not crash. */
    @Test fun failsCleanlyWithoutATranslator() = runBlocking {
        val result = ChatTranslator(RuntimeEnvironment.getApplication()).translate("Hallo, wie geht's euch heute?")
        assertTrue("got $result", result is Translation.Failed)
    }

}
