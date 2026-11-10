package dev.yipchat.core.data

import dev.yipchat.core.data.history.HistoryEntry
import dev.yipchat.core.database.HistoryKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingTest {
    private fun vod(pos: Long, dur: Long) = HistoryEntry("VOD:1", HistoryKind.VOD, "1", "c", "c", "C", "t", null, pos, dur, 0)

    @Test fun finishedNearTheEnd() {
        assertTrue(vod(3_550, 3_600).finished) // under a minute left
        assertTrue(vod(9_800, 10_000).finished) // 98%
        assertFalse(vod(1_800, 3_600).finished)
        assertFalse(vod(100, 0).finished) // unknown length
    }
}
