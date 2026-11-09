package lol.sinist3r.yip.core.network

import kotlinx.serialization.json.Json
import lol.sinist3r.yip.core.network.twitch.HelixBadgeSet
import lol.sinist3r.yip.core.network.twitch.HelixPage
import org.junit.Assert.assertEquals
import org.junit.Test

class HelixBadgesTest {
    private val json: Json = NetworkModule.snakeJson()

    @Test fun decodesBadgeSets() {
        val body = """{"data":[{"set_id":"moderator","versions":[{"id":"1",
            "image_url_1x":"https://static-cdn.jtvnw.net/badges/v1/x/1","image_url_2x":"https://static-cdn.jtvnw.net/badges/v1/x/2",
            "image_url_4x":"https://static-cdn.jtvnw.net/badges/v1/x/3","title":"Moderator","description":"Moderator",
            "click_action":null,"click_url":null}]}]}"""
        val sets = json.decodeFromString<HelixPage<HelixBadgeSet>>(body).data
        assertEquals("moderator", sets.single().setId)
        assertEquals("https://static-cdn.jtvnw.net/badges/v1/x/2", sets.single().versions.single().imageUrl2x)
    }
}
