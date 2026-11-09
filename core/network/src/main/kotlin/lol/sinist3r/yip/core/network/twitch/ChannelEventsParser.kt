package lol.sinist3r.yip.core.network.twitch

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import lol.sinist3r.yip.core.model.ChannelEvents
import lol.sinist3r.yip.core.model.ChannelPoll
import lol.sinist3r.yip.core.model.HypeTrain
import lol.sinist3r.yip.core.model.PollChoice
import lol.sinist3r.yip.core.model.Prediction
import lol.sinist3r.yip.core.model.PredictionOutcome
import lol.sinist3r.yip.core.model.PredictionStatus

/** Turns the [GqlQueries.CHANNEL_EVENTS] response into [ChannelEvents]. [now] is injectable for tests. */
object ChannelEventsParser {
    fun parse(data: JsonObject, now: Long = System.currentTimeMillis()): ChannelEvents {
        val user = data.obj("user") ?: return ChannelEvents()
        return ChannelEvents(poll(user.obj("viewablePoll"), now), prediction(user.path("channel")), hypeTrain(user.path("channel", "hypeTrain", "execution")))
    }

    private fun poll(o: JsonObject?, now: Long): ChannelPoll? {
        o ?: return null
        val status = o.str("status").orEmpty()
        // Twitch keeps showing a finished poll briefly; drop archived/terminated ones.
        if (status !in setOf("ACTIVE", "COMPLETED")) return null
        val choices = o.arr("choices").mapNotNull { it as? JsonObject }.map { PollChoice(it.str("title").orEmpty(), it.obj("votes")?.int("total") ?: 0) }
        val remaining = o.long("remainingDurationMilliseconds")
        return ChannelPoll(o.str("id") ?: return null, o.str("title").orEmpty(), choices, remaining?.takeIf { it > 0 }?.let { now + it }, active = status == "ACTIVE")
    }

    private fun prediction(channel: JsonObject?): Prediction? {
        val o = channel?.arr("activePredictionEvents")?.firstOrNull() as? JsonObject ?: return null
        val status = runCatching { PredictionStatus.valueOf(o.str("status").orEmpty()) }.getOrDefault(PredictionStatus.OTHER)
        val created = o.str("createdAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        val window = o.long("predictionWindowSeconds")
        val outcomes = o.arr("outcomes").mapNotNull { it as? JsonObject }.map {
            PredictionOutcome(it.str("title").orEmpty(), it.str("color").orEmpty(), it.long("totalPoints") ?: 0, it.int("totalUsers") ?: 0)
        }
        return Prediction(o.str("id") ?: return null, o.str("title").orEmpty(), status, if (created != null && window != null) created + window * 1000 else null, outcomes)
    }

    private fun hypeTrain(o: JsonObject?): HypeTrain? {
        o ?: return null
        if (o.bool("isActive") == false) return null
        val p = o.obj("progress") ?: return null
        val ends = o.str("expiresAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        return HypeTrain(o.str("id").orEmpty(), p.obj("level")?.int("value") ?: 1, p.int("progression") ?: p.int("total") ?: 0, p.int("goal") ?: 1, ends)
    }
}
