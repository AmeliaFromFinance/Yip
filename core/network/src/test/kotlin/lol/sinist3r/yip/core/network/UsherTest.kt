package lol.sinist3r.yip.core.network

import lol.sinist3r.yip.core.network.twitch.Usher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsherTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun `parses current master playlist`() {
        val q = Usher.parseMaster(res("master_live.m3u8"))
        assertEquals(6, q.size)
        assertEquals("1080p60", q.first().label)
        assertTrue(q.first().isSource)
        assertEquals(60, q.first().fps)
        val audio = q.single { it.isAudioOnly }
        assertEquals("Audio only", audio.label)
        assertEquals(0, audio.height)
        assertEquals(listOf("1080p60", "720p60", "480p", "360p", "160p"), q.filterNot { it.isAudioOnly }.map { it.label })
    }

    @Test
    fun `parses legacy master with EXT-X-MEDIA`() {
        val legacy = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60 (source)",AUTOSELECT=YES,DEFAULT=YES
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="chunked",FRAME-RATE=60.000
            https://example.com/chunked.m3u8
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="audio_only",NAME="audio_only",AUTOSELECT=NO,DEFAULT=NO
            #EXT-X-STREAM-INF:BANDWIDTH=160000,CODECS="mp4a.40.2",VIDEO="audio_only"
            https://example.com/audio.m3u8
        """.trimIndent()
        val q = Usher.parseMaster(legacy)
        assertTrue(q.first().isSource)
        assertTrue(q.last().isAudioOnly)
    }

    @Test
    fun `detects and strips stitched ads`() {
        val clean = res("media_live.m3u8")
        val ads = res("media_ads.m3u8")
        assertFalse(Usher.hasAds(clean))
        assertTrue(Usher.hasAds(ads))
        val stripped = Usher.stripAds(ads)
        assertFalse("AD1.ts" in stripped)
        assertFalse("AD2.ts" in stripped)
        assertFalse("stitched-ad" in stripped)
        val liveSegments = clean.lines().count { it.startsWith("#EXTINF") }
        assertEquals(liveSegments, stripped.lines().count { it.startsWith("#EXTINF") })
    }

    @Test
    fun `clean playlist is untouched`() {
        val clean = res("media_live.m3u8")
        assertEquals(clean, Usher.stripAds(clean))
    }
}
