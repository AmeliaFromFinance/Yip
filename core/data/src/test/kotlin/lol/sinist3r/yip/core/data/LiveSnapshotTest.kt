package lol.sinist3r.yip.core.data

import kotlin.time.Clock
import lol.sinist3r.yip.core.data.live.LiveSnapshot
import lol.sinist3r.yip.core.model.LiveStream
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSnapshotTest {
    private fun s(login: String, viewers: Int) = LiveStream(login, login, login, login, null, "Just Chatting", "t", viewers, Clock.System.now(), "en", "")

    @Test fun pinnedFirstThenViewers() {
        val out = LiveSnapshot.entries(listOf(s("big", 9000), s("eepyamelia", 12), s("mid", 400), s("pal", 3)), setOf("eepyamelia", "pal"))
        assertEquals(listOf("eepyamelia", "pal", "big", "mid"), out.map { it.login })
        assertEquals(listOf(true, true, false, false), out.map { it.pinned })
    }
}
