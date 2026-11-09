package lol.sinist3r.yip.feature.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTest {
    @Test fun sizes() {
        assertEquals("1.5 GB", sizeText(1_610_612_736))
        assertEquals("700 MB", sizeText(700L shl 20))
        assertEquals("12 KB", sizeText(12_288))
    }

    @Test fun ago() {
        val now = 10_000_000_000L
        assertEquals("just now", ago(now - 10_000, now))
        assertEquals("5m ago", ago(now - 5 * 60_000, now))
        assertEquals("3h ago", ago(now - 3 * 3_600_000, now))
        assertEquals("2d ago", ago(now - 2 * 86_400_000, now))
    }

    @Test fun eventPercentsRound() {
        assertEquals(22, pct(1_295_708, 5_796_153))
        assertEquals(78, pct(4_500_445, 5_796_153))
        assertEquals(0, pct(5, 0))
    }
}
