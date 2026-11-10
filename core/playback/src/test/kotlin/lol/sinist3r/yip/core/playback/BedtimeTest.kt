package lol.sinist3r.yip.core.playback

import lol.sinist3r.yip.core.model.BedtimeSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BedtimeTest {
    private fun at(h: Int, m: Int = 0) = h * 60 + m

    @Test fun wrapsPastMidnight() {
        val b = BedtimeSettings(enabled = true, startMinutes = at(23), endMinutes = at(7))
        assertTrue(b.activeAt(at(23)))
        assertTrue(b.activeAt(at(2, 30)))
        assertTrue(b.activeAt(at(6, 59)))
        assertFalse(b.activeAt(at(7)))
        assertFalse(b.activeAt(at(12)))
        assertFalse(b.activeAt(at(22, 59)))
    }

    @Test fun sameDayWindow() {
        val b = BedtimeSettings(enabled = true, startMinutes = at(13), endMinutes = at(15))
        assertTrue(b.activeAt(at(14)))
        assertFalse(b.activeAt(at(15)))
        assertFalse(b.activeAt(at(1)))
    }

    @Test fun offWhenDisabledOrEmpty() {
        assertFalse(BedtimeSettings(enabled = false).activeAt(at(1)))
        assertFalse(BedtimeSettings(enabled = true, startMinutes = at(1), endMinutes = at(1)).activeAt(at(1)))
    }
}
