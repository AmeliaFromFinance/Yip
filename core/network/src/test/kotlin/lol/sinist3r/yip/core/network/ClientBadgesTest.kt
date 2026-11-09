package lol.sinist3r.yip.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import lol.sinist3r.yip.core.model.BadgeSource
import lol.sinist3r.yip.core.network.cosmetics.ClientBadgesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trimmed copies of the live FFZ:AP, Homies and Moltorino responses. */
class ClientBadgesTest {
    private val fx: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource("client_badges.json")).readText(),
    ).jsonObject

    @Test fun ffzApTiers() {
        val b = ClientBadgesApi.parseFfzAp(fx["ffzap"] as JsonArray)
        fun badge(uid: String) = b.badges[b.users.getValue(uid).single()]!!
        assertEquals("#755000", badge("7864791").backgroundColor) // tier 1: house colour
        assertEquals("#D1008C", badge("41064621").backgroundColor) // tier 2: own colour
        assertEquals("#812FA8", badge("4867723").backgroundColor) // tier 3 tinted
        assertNull(badge("47965880").backgroundColor) // tier 3 full-colour image
        assertEquals("https://api.ffzap.com/v1/user/badge/7864791/2", badge("7864791").urls[1])
        assertEquals(BadgeSource.FFZAP, badge("7864791").source)
    }

    @Test fun homiesListsMergeWithoutDuplicates() {
        val b = ClientBadgesApi.parseHomiesLists((fx["homies"] as JsonArray).map { it as JsonObject })
        val dev = b.users.getValue("59842770")
        assertEquals(dev.size, dev.toSet().size)
        assertEquals("Homies Developer", b.badges[dev.first()]!!.title)
        assertTrue(b.users.keys.none { it.isEmpty() })
    }

    @Test fun homiesCustom() {
        val b = ClientBadgesApi.parseHomiesCustom(fx["homiesCustom"] as JsonObject)
        val badge = b.badges[b.users.getValue("95700563").single()]!!
        assertEquals("usVesper Badge", badge.title)
        assertEquals(BadgeSource.HOMIES_CUSTOM, badge.source)
        assertTrue(badge.urls.all { it.endsWith(".webp") })
    }

    @Test fun moltorinoPicksActiveThenPriorityAndRespectsHidden() {
        val b = ClientBadgesApi.parseMoltorino(fx["moltorino"] as JsonObject)
        assertEquals(listOf("moltorino:supporter_plus_plus"), b.users["25506506"]) // chosen badge
        assertEquals(listOf("moltorino:supporter_plus_alt"), b.users["39625532"]) // best ranked owned
        assertNull(b.users["91331457"]) // hid their Moltorino badge
        assertEquals(setOf("hc", "hs", "m"), b.hidden["91331457"])
        assertEquals(setOf("ff"), b.hidden["12682083"])
        assertEquals("Moltorino Supporter++", b.badges["moltorino:supporter_plus_plus"]!!.title)
    }
}
