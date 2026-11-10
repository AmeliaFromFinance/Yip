package dev.yipchat.core.network.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesParseTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Shape taken from a live "community-points-channel-v1" message.
    private fun payload(inputRequired: Boolean, input: String? = null) = """
        {"type":"reward-redeemed","data":{"timestamp":"2026-11-10T16:58:12.27725543Z","redemption":{
          "id":"0560f7bb","user":{"id":"492462230","login":"mintfromtw","display_name":"MintFromTW"},
          "channel_id":"403594122","redeemed_at":"2026-11-10T16:58:12.27725543Z",
          "reward":{"id":"a88cbe2f","channel_id":"403594122","title":"Hydrate","cost":5000,"is_user_input_required":$inputRequired},
          ${if (input != null) "\"user_input\":\"$input\"," else ""}"status":"UNFULFILLED"}}}
    """.trimIndent()

    @Test fun readsRedemption() {
        val r = HermesClient.parseRedemption(json, payload(false))!!
        assertEquals("MintFromTW", r.userName)
        assertEquals("Hydrate", r.rewardTitle)
        assertEquals(5000, r.cost)
        assertFalse(r.needsInput)
    }

    @Test fun flagsRedemptionsWithAMessage() {
        assertTrue(HermesClient.parseRedemption(json, payload(true, "hi"))!!.needsInput)
    }

    @Test fun ignoresOtherTypes() {
        assertNull(HermesClient.parseRedemption(json, """{"type":"custom-reward-updated","data":{}}"""))
    }
}
