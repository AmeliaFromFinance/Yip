package lol.sinist3r.yip.core.playback

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DataUsageTest {
    @Test fun sumsTheRequestedDays() {
        val today = LocalDate.now()
        val days = mapOf(
            today.toString() to DayUsage(mobile = 100, wifi = 1_000, channels = mapOf("eepyamelia" to 800)),
            today.minusDays(3).toString() to DayUsage(mobile = 50, wifi = 0, channels = mapOf("eepyamelia" to 50, "xqc" to 10)),
            today.minusDays(20).toString() to DayUsage(mobile = 7, wifi = 7),
        )
        val todayOnly = DataUsage.sum(days, 1)
        assertEquals(1_100L, todayOnly.total)
        val week = DataUsage.sum(days, 7)
        assertEquals(150L, week.mobile)
        assertEquals(850L, week.channels["eepyamelia"])
        assertEquals(1_164L, DataUsage.sum(days, 30).total)
    }
}
