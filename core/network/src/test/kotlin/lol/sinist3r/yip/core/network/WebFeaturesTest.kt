package lol.sinist3r.yip.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import lol.sinist3r.yip.core.network.twitch.WebFeaturesParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes match the field names checked against gql.twitch.tv. */
class WebFeaturesTest {
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun followedGames() {
        val list = WebFeaturesParser.followedGames(json("""{"currentUser":{"followedGames":{"nodes":[{"id":"509658","name":"just chatting","displayName":"Just Chatting","boxArtURL":"https://static-cdn.jtvnw.net/ttv-boxart/509658-{width}x{height}.jpg"}]}}}"""))
        assertEquals("Just Chatting", list.single().name)
        assertEquals("https://static-cdn.jtvnw.net/ttv-boxart/509658-144x192.jpg", list.single().boxArtUrl)
        assertTrue(WebFeaturesParser.followedGames(json("""{"currentUser":null}""")).isEmpty())
    }

    @Test fun drops() {
        val c = WebFeaturesParser.drops(json("""{"currentUser":{"inventory":{"dropCampaignsInProgress":[{"id":"c","name":"Winter Drops","endAt":"2026-12-01T00:00:00Z","game":{"id":"1","displayName":"Celeste","boxArtURL":"x"},
            "timeBasedDrops":[
              {"id":"d1","name":"Hat","requiredMinutesWatched":60,"benefitEdges":[{"benefit":{"name":"Cosy hat","imageAssetURL":"h"}}],"self":{"currentMinutesWatched":60,"isClaimed":false,"dropInstanceID":"inst1"}},
              {"id":"d2","name":"Scarf","requiredMinutesWatched":120,"benefitEdges":[],"self":{"currentMinutesWatched":30,"isClaimed":false,"dropInstanceID":null}}
            ]}]}}}""")).single()
        assertEquals("Celeste", c.gameName)
        assertTrue(c.drops[0].claimable)
        assertEquals("Cosy hat", c.drops[0].rewardName)
        assertFalse(c.drops[1].claimable)
        assertEquals(0.25f, c.drops[1].progress)
    }
}
