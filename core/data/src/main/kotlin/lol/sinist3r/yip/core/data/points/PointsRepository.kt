package lol.sinist3r.yip.core.data.points

import javax.inject.Inject
import javax.inject.Singleton
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.network.twitch.GqlApi
import lol.sinist3r.yip.core.network.twitch.int
import lol.sinist3r.yip.core.network.twitch.obj
import lol.sinist3r.yip.core.network.twitch.path
import lol.sinist3r.yip.core.network.twitch.str
import timber.log.Timber

data class PointsStatus(val channelId: String, val balance: Int, val claimId: String?)

/** Experimental: needs the optional twitch.tv web connection. All failures are soft. */
@Singleton
class PointsRepository @Inject constructor(private val gql: GqlApi, private val accounts: lol.sinist3r.yip.core.data.account.AccountRepository) {

    suspend fun status(channelLogin: String): PointsStatus? = attempt {
        val ch = gql.channelPointsContext(channelLogin).path("community", "channel") ?: return@attempt null
        val points = ch.path("self", "communityPoints")
        PointsStatus(
            channelId = ch.str("id") ?: return@attempt null,
            balance = points?.int("balance") ?: 0,
            claimId = points?.obj("availableClaim")?.str("id"),
        )
    }.onFailure { Timber.d(it, "points status failed") }.getOrNull()

    /** Rewards list (works signed out) plus your balance when twitch.tv is connected. */
    suspend fun rewards(channelLogin: String): Result<lol.sinist3r.yip.core.model.ChannelRewards> = attempt {
        // Signed-in first for the balance; fall back to the public list if that fails.
        val authed = if (accounts.webToken() != null) attempt { gql.channelRewards(channelLogin, authed = true) }.getOrNull() else null
        authed ?: gql.channelRewards(channelLogin, authed = false) ?: error("Channel not found")
    }

    /** Null on success, otherwise why it failed. */
    suspend fun redeem(channelId: String, reward: lol.sinist3r.yip.core.model.CustomReward, text: String?): String? {
        if (accounts.webToken() == null) return "Connect twitch.tv under Settings → Accounts to redeem"
        val input = kotlinx.serialization.json.buildJsonObject {
            put("channelID", kotlinx.serialization.json.JsonPrimitive(channelId))
            put("cost", kotlinx.serialization.json.JsonPrimitive(reward.cost))
            put("prompt", reward.prompt?.let(::JsonPrimitiveOf) ?: kotlinx.serialization.json.JsonNull)
            put("rewardID", kotlinx.serialization.json.JsonPrimitive(reward.id))
            put("title", kotlinx.serialization.json.JsonPrimitive(reward.title))
            // Lets Twitch drop accidental double taps.
            put("transactionID", kotlinx.serialization.json.JsonPrimitive(java.util.UUID.randomUUID().toString().replace("-", "")))
            if (reward.needsInput) put("textInput", kotlinx.serialization.json.JsonPrimitive(text.orEmpty()))
        }
        return attempt { gql.redeemReward(input) }.fold(
            onSuccess = { data ->
                val result = data.obj("redeemCommunityPointsCustomReward")
                result?.obj("error")?.str("code")?.let(lol.sinist3r.yip.core.network.twitch.RewardsParser::errorText)
                    ?: if (result?.obj("redemption") != null) null else "Twitch didn't confirm the redemption"
            },
            onFailure = { it.message ?: "Couldn't redeem" },
        )
    }

    /** Returns the new balance if a bonus was claimed. */
    suspend fun claimIfAvailable(channelLogin: String): Int? {
        val s = status(channelLogin) ?: return null
        val claim = s.claimId ?: return null
        return attempt {
            gql.claimPoints(s.channelId, claim)
            status(channelLogin)?.balance
        }.onFailure { Timber.w(it, "claim failed") }.getOrNull()
    }
}

private fun JsonPrimitiveOf(s: String) = kotlinx.serialization.json.JsonPrimitive(s)
