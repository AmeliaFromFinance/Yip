package lol.sinist3r.yip.core.network

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lol.sinist3r.yip.core.network.twitch.EventSubCreate
import lol.sinist3r.yip.core.network.twitch.EventSubTransport
import org.junit.Assert.assertEquals
import org.junit.Test

/** The body Twitch receives when Yip subscribes to a channel's chat. */
class EventSubRequestTest {
    private val json = NetworkModule.snakeJson()

    @Test fun transportCarriesMethodAndSession() {
        val body = json.encodeToString(
            EventSubCreate.serializer(),
            EventSubCreate("channel.chat.message", "1", mapOf("broadcaster_user_id" to "1", "user_id" to "2"), EventSubTransport.webSocket("abc")),
        )
        val transport = json.parseToJsonElement(body).jsonObject["transport"]!!.jsonObject
        assertEquals("websocket", transport["method"]!!.jsonPrimitive.content)
        assertEquals("abc", transport["session_id"]!!.jsonPrimitive.content)
    }
}
