package lol.sinist3r.yip.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import lol.sinist3r.yip.core.model.PredictionStatus
import lol.sinist3r.yip.core.network.twitch.ChannelEventsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Responses captured from gql.twitch.tv (Nov 2026), trimmed to one channel each. */
class ChannelEventsTest {
    private fun parse(s: String, now: Long = 0) = ChannelEventsParser.parse(Json.parseToJsonElement(s).jsonObject, now)

    @Test fun prediction() {
        val e = parse("""{"user":{"viewablePoll":null,"channel":{"activePredictionEvents":[{"id":"a7f7","title":"Holt Rohat den Win?","status":"ACTIVE","createdAt":"2026-11-09T20:04:44.991628065Z","predictionWindowSeconds":120,"outcomes":[{"title":"Ja","color":"BLUE","totalPoints":1295708,"totalUsers":60},{"title":"Ne","color":"PINK","totalPoints":4500445,"totalUsers":111}]}],"hypeTrain":{"execution":null}}}}""")
        val p = e.prediction!!
        assertEquals(PredictionStatus.ACTIVE, p.status)
        assertEquals(listOf("Ja", "Ne"), p.outcomes.map { it.title })
        assertEquals(5_796_153L, p.totalPoints)
        assertEquals(java.time.Instant.parse("2026-11-09T20:06:44.991Z").toEpochMilli(), p.locksAtMillis)
        assertNull(e.hypeTrain)
    }

    @Test fun hypeTrainUsesProgressWithinLevel() {
        val e = parse("""{"user":{"viewablePoll":null,"channel":{"activePredictionEvents":[],"hypeTrain":{"execution":{"id":"3531","isActive":true,"expiresAt":"2026-11-09T20:09:41.822080405Z","progress":{"goal":7200,"total":27550,"progression":2450,"level":{"value":9}}}}}}}""")
        val h = e.hypeTrain!!
        assertEquals(9, h.level)
        assertEquals(2450, h.progress)
        assertEquals(7200, h.goal)
        assertTrue(h.endsAtMillis!! > 0)
    }

    @Test fun poll() {
        val e = parse("""{"user":{"viewablePoll":{"id":"p1","title":"Next game?","status":"ACTIVE","remainingDurationMilliseconds":30000,"choices":[{"title":"A","votes":{"total":3}},{"title":"B","votes":{"total":1}}]},"channel":{"activePredictionEvents":[],"hypeTrain":{"execution":null}}}}""", now = 1000)
        assertEquals(4, e.poll!!.totalVotes)
        assertEquals(31_000L, e.poll!!.endsAtMillis)
    }

    @Test fun nothingRunning() {
        assertTrue(parse("""{"user":{"viewablePoll":null,"channel":{"activePredictionEvents":[],"hypeTrain":{"execution":null}}}}""").isEmpty)
        assertTrue(parse("""{"user":null}""").isEmpty)
    }
}

class ChannelEventsVotingTest {
    @Test fun readsIdsSettingsAndYourPick() {
        val e = ChannelEventsParser.parse(Json.parseToJsonElement("""{"user":{"viewablePoll":{"id":"p","title":"T","status":"ACTIVE","remainingDurationMilliseconds":1000,"settings":{"communityPointsVotes":{"isEnabled":true,"cost":250}},"choices":[{"id":"c1","title":"A","votes":{"total":1}}]},
            "channel":{"activePredictionEvents":[{"id":"e","title":"Win?","status":"ACTIVE","createdAt":"2026-11-09T20:00:00Z","predictionWindowSeconds":60,"outcomes":[{"id":"o1","title":"Yes","color":"BLUE","totalPoints":10,"totalUsers":1}],"self":{"prediction":{"points":500,"outcome":{"id":"o1"}}}}],"hypeTrain":{"execution":null}}}}""").jsonObject)
        assertEquals("c1", e.poll!!.choices.single().id)
        assertEquals(250, e.poll!!.pointsVoteCost)
        assertEquals("o1", e.prediction!!.myOutcomeId)
        assertEquals(500L, e.prediction!!.myPoints)
    }
}
