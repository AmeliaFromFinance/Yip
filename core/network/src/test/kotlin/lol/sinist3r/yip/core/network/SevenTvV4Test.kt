package lol.sinist3r.yip.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import lol.sinist3r.yip.core.model.BadgeSource
import lol.sinist3r.yip.core.model.PaintFunction
import lol.sinist3r.yip.core.network.cosmetics.SevenTvApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real v4 responses captured from 7tv.io. */
class SevenTvV4Test {
    private val fx: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource("seventv_v4.json")).readText(),
    ).jsonObject

    private fun o(key: String) = fx[key] as JsonObject

    @Test fun hexIsRgba() {
        assertEquals(0xFFCA804EL, SevenTvApi.hexToArgb("#CA804EFF"))
        assertEquals(0x80FFFFFFL, SevenTvApi.hexToArgb("#FFFFFFFF", opacity = 0.5f))
    }

    @Test fun linearPaint() {
        val p = checkNotNull(SevenTvApi.parsePaintV4(o("linear")))
        assertEquals(PaintFunction.LINEAR_GRADIENT, p.function)
        assertEquals(66f, p.angle)
        assertTrue(p.repeat)
        assertEquals(6, p.stops.size)
        assertEquals(0xFFCA804EL, p.stops.first().argb)
        assertTrue(p.shadows.isNotEmpty())
    }

    @Test fun radialPaint() {
        val p = checkNotNull(SevenTvApi.parsePaintV4(o("radial")))
        assertEquals(PaintFunction.RADIAL_GRADIENT, p.function)
        assertTrue(p.stops.size >= 2)
    }

    @Test fun imagePaintUsesStaticWebp() {
        val p = checkNotNull(SevenTvApi.parsePaintV4(o("image")))
        assertEquals(PaintFunction.URL, p.function)
        val url = checkNotNull(p.imageUrl)
        assertTrue(url, url.endsWith("_static.webp"))
    }

    @Test fun badge() {
        val b = checkNotNull(SevenTvApi.parseBadgeV4(o("badge")))
        assertEquals(BadgeSource.SEVENTV, b.source)
        assertTrue(b.id.startsWith("7tv:"))
        assertTrue(b.urls.all { it.endsWith(".webp") })
        assertFalse(b.urls.isEmpty())
    }

    @Test fun emotes() {
        val a = checkNotNull(SevenTvApi.parseSetEmote(o("emoteAnimated"), isChannel = false))
        assertTrue(a.animated)
        assertTrue(a.zeroWidth)
        assertTrue(a.urls.none { "_static" in it })
        assertTrue(a.url(1).endsWith("1x.webp"))
        val s = checkNotNull(SevenTvApi.parseSetEmote(o("emoteStatic"), isChannel = true))
        assertFalse(s.animated)
        assertNotNull(s.width)
        assertEquals("ppL", s.code)
    }
}
