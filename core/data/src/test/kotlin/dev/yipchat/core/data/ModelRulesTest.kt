package dev.yipchat.core.data

import dev.yipchat.core.data.chat.LinkPreviews
import dev.yipchat.core.model.BrowseFilter
import dev.yipchat.core.model.LiveStream
import dev.yipchat.core.model.ModMacro
import dev.yipchat.core.model.Storyboard
import dev.yipchat.core.model.UptimeRange
import dev.yipchat.core.model.ViewerRange
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRulesTest {
    @Test fun parsesModMacros() {
        assertEquals(ModMacro(600, "Spam"), ModMacro.parse("10m Spam"))
        assertEquals(ModMacro(null, "Bot account"), ModMacro.parse("ban Bot account"))
        assertEquals(ModMacro(86_400, null), ModMacro.parse("1d"))
        assertEquals("1h · Being rude", ModMacro.parse("1h Being rude")!!.label)
        assertNull(ModMacro.parse("Spam"))
        assertNull(ModMacro.parse("30d too long"))
    }

    @Test fun storyboardFrames() {
        val sb = Storyboard(listOf("a.jpg", "b.jpg"), count = 30, intervalSeconds = 10, cols = 5, rows = 4, width = 160, height = 90)
        assertEquals(Storyboard.Frame("a.jpg", 0, 0), sb.frameAt(0))
        assertEquals(Storyboard.Frame("a.jpg", 160, 90), sb.frameAt(65)) // frame 6: col 1, row 1
        assertEquals(Storyboard.Frame("b.jpg", 0, 0), sb.frameAt(200)) // frame 20 starts the second sprite
        assertEquals("b.jpg", sb.frameAt(10_000)!!.imageUrl) // clamped to the last frame
    }

    @Test fun browseFilter() {
        val now = Clock.System.now()
        fun s(viewers: Int, startedMinsAgo: Int, title: String = "hi") =
            LiveStream("1", "1", "a", "A", null, null, title, viewers, now - startedMinsAgo.minutes, null, "")
        val small = BrowseFilter(viewers = ViewerRange.UNDER_50)
        assertTrue(small.matches(s(10, 5), now.toEpochMilliseconds()))
        assertFalse(small.matches(s(500, 5), now.toEpochMilliseconds()))
        val fresh = BrowseFilter(uptime = UptimeRange.JUST_STARTED, hideReruns = true)
        assertTrue(fresh.matches(s(10, 20), now.toEpochMilliseconds()))
        assertFalse(fresh.matches(s(10, 300), now.toEpochMilliseconds()))
        assertFalse(fresh.matches(s(10, 20, "[Rerun] yesterday"), now.toEpochMilliseconds()))
    }

    @Test fun linkTargets() {
        assertEquals(LinkPreviews.Target.ClipLink("FunnyClipSlug-abc"), LinkPreviews.parse("https://clips.twitch.tv/FunnyClipSlug-abc"))
        assertEquals(LinkPreviews.Target.ClipLink("FunnyClipSlug-abc"), LinkPreviews.parse("https://www.twitch.tv/lirik/clip/FunnyClipSlug-abc?filter=clips"))
        assertEquals(LinkPreviews.Target.VideoLink("2923278094"), LinkPreviews.parse("twitch.tv/videos/2923278094"))
        assertEquals(LinkPreviews.Target.ChannelLink("eepyamelia"), LinkPreviews.parse("https://m.twitch.tv/eepyAmelia"))
        assertEquals(LinkPreviews.Target.YouTube, LinkPreviews.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(LinkPreviews.Target.YouTube, LinkPreviews.parse("https://youtu.be/dQw4w9WgXcQ"))
        assertNull(LinkPreviews.parse("https://twitch.tv/directory"))
        assertNull(LinkPreviews.parse("https://example.com/page"))
    }
}
