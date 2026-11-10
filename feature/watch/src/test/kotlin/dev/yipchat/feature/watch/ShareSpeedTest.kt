package dev.yipchat.feature.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareSpeedTest {
    @Test fun twitchTimestamps() {
        assertEquals("42s", twitchTime(42))
        assertEquals("1m5s", twitchTime(65))
        assertEquals("1h0m3s", twitchTime(3603))
        assertEquals("2h15m0s", twitchTime(8100))
    }

    @Test fun speedLabels() {
        assertEquals("1x", speedLabel(1f))
        assertEquals("0.25x", speedLabel(0.25f))
        assertEquals("1.5x", speedLabel(1.5f))
    }
}
