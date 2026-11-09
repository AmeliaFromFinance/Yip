package lol.sinist3r.yip.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import lol.sinist3r.yip.core.network.twitch.RewardsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** rewards_xqc.json: a real signed-out response from gql.twitch.tv (Nov 2026), trimmed to 4 rewards. */
class RewardsTest {
    private val data = Json.parseToJsonElement(javaClass.classLoader!!.getResource("rewards_xqc.json")!!.readText()).jsonObject

    @Test fun parsesRealResponse() {
        val r = RewardsParser.parse(data)!!
        assertEquals("71092938", r.channelId)
        assertNull("no balance when signed out", r.balance)
        assertEquals("Channel points", r.pointsName)
        assertEquals(4, r.rewards.size)
        assertEquals(r.rewards.sortedBy { it.cost }, r.rewards)
        val song = r.rewards.first { it.title == "QUEUE SONG" }
        assertTrue(song.available)
        assertTrue(song.needsInput)
        assertFalse(r.rewards.first { it.title == "RUN AN AD" }.available)
        assertTrue(r.rewards.all { it.imageUrl != null })
    }

    @Test fun errorCodesInPlainWords() {
        assertEquals("Not enough points", RewardsParser.errorText("NOT_ENOUGH_POINTS"))
        assertEquals("Twitch said: something new", RewardsParser.errorText("SOMETHING_NEW"))
    }
}
