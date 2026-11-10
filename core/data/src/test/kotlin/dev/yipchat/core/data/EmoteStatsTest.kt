package dev.yipchat.core.data

import dev.yipchat.core.data.chat.EmoteStats
import dev.yipchat.core.data.chat.EmoteUse
import org.junit.Assert.assertEquals
import org.junit.Test

class EmoteStatsTest {
    @Test fun mostUsedFirstThenMostRecent() {
        val uses = mapOf(
            "TWITCH:1" to EmoteUse("Kappa", 3, 100),
            "SEVENTV:a" to EmoteUse("catJAM", 9, 50),
            "BTTV:b" to EmoteUse("monkaS", 3, 200),
        )
        assertEquals(listOf("SEVENTV:a", "BTTV:b", "TWITCH:1"), EmoteStats.top(uses, 10))
        assertEquals(1, EmoteStats.top(uses, 1).size)
    }
}
