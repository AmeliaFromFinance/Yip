package dev.yipchat.core.network

import dev.yipchat.core.network.twitch.WebFeaturesParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
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
}
