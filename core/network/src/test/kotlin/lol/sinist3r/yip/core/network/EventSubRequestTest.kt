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

class AdScheduleTest {
    @Test fun acceptsDocumentedAndObservedShapes() {
        val docs = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"next_ad_at":"2023-08-01T23:08:18+00:00","last_ad_at":"","duration":"60","preroll_free_time":"90","snooze_count":"1","snooze_refresh_at":"2023-08-01T23:08:18+00:00"}""",
        ).jsonObject
        val a = lol.sinist3r.yip.core.network.twitch.HelixAdSchedule.parse(docs)!!
        assertEquals(1690931298L, a.nextAdAt)
        assertEquals(0L, a.lastAdAt)
        assertEquals(60, a.duration)
        val numbers = kotlinx.serialization.json.Json.parseToJsonElement("""{"next_ad_at":1690931298,"duration":60,"snooze_count":3}""").jsonObject
        val b = lol.sinist3r.yip.core.network.twitch.HelixAdSchedule.parse(numbers)!!
        assertEquals(1690931298L, b.nextAdAt)
        assertEquals(3, b.snoozeCount)
    }
}

class WhisperEventTest {
    @Test fun parsesTwitchExample() {
        // From Twitch's EventSub reference for user.whisper.message.
        val event = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"from_user_id":"423374343","from_user_login":"glowillig","from_user_name":"glowillig","to_user_id":"424596340","to_user_login":"quotrok","to_user_name":"quotrok","whisper_id":"some-whisper-id","whisper":{"text":"a secret"}}""",
        ).jsonObject
        val w = lol.sinist3r.yip.core.network.chat.EventSubChatClient.parseWhisper(event)!!
        assertEquals("glowillig", w.fromLogin)
        assertEquals("a secret", w.text)
        assertEquals("some-whisper-id", w.id)
    }
}
