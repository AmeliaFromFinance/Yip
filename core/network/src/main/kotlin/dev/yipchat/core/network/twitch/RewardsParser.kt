package dev.yipchat.core.network.twitch

import dev.yipchat.core.model.ChannelRewards
import dev.yipchat.core.model.CustomReward
import java.time.Instant
import kotlinx.serialization.json.JsonObject

object RewardsParser {
    fun parse(data: JsonObject): ChannelRewards? {
        val ch = data.obj("channel") ?: return null
        val settings = ch.obj("communityPointsSettings")
        val rewards = settings?.arr("customRewards").orEmpty().mapNotNull { it as? JsonObject }.map { r ->
            CustomReward(
                id = r.str("id").orEmpty(),
                title = r.str("title").orEmpty(),
                cost = r.int("cost") ?: 0,
                prompt = r.str("prompt")?.takeIf { it.isNotBlank() },
                needsInput = r.bool("isUserInputRequired") == true,
                available = r.bool("isEnabled") != false && r.bool("isPaused") != true && r.bool("isInStock") != false,
                subOnly = r.bool("isSubOnly") == true,
                color = r.str("backgroundColor"),
                imageUrl = r.obj("image")?.str("url") ?: r.obj("defaultImage")?.str("url"),
                cooldownEndsAtMillis = r.str("cooldownExpiresAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            )
        }.filter { it.id.isNotBlank() }.sortedBy { it.cost }
        return ChannelRewards(
            channelId = ch.str("id") ?: return null,
            pointsName = settings?.str("name")?.takeIf { it.isNotBlank() } ?: "Channel points",
            enabled = settings?.bool("isEnabled") == true,
            balance = ch.path("self", "communityPoints")?.int("balance"),
            rewards = rewards,
        )
    }

    /** Twitch's error codes, in plain words. */
    fun errorText(code: String): String = when (code) {
        "NOT_ENOUGH_POINTS" -> "Not enough points"
        "COOLDOWN", "GLOBALLY_COOLDOWN", "GLOBAL_COOLDOWN" -> "On cooldown, try again soon"
        "MAX_PER_STREAM", "MAX_PER_STREAM_EXCEEDED", "MAX_PER_USER_PER_STREAM" -> "Limit reached for this stream"
        "DISABLED", "PAUSED", "NOT_IN_STOCK", "REWARD_DISABLED" -> "This reward isn't available right now"
        "PROPERTIES_MISMATCH" -> "The reward changed. Refresh and try again"
        "FORBIDDEN", "USER_BANNED" -> "You can't do that in this channel"
        "EVENT_NOT_ACTIVE", "EVENT_LOCKED", "PREDICTION_LOCKED" -> "Predictions are closed"
        "POLL_NOT_ACTIVE", "POLL_ENDED" -> "The poll has ended"
        "MAX_POINTS_PER_EVENT", "OVER_MAX_POINTS" -> "That's more than the limit for this prediction"
        "DUPLICATE_PREDICTION", "ALREADY_PREDICTED_DIFFERENT_OUTCOME" -> "You've already picked the other side"
        else -> "Twitch said: ${code.lowercase().replace('_', ' ')}"
    }
}
