package dev.yipchat.core.data.points

import dev.yipchat.core.common.attempt
import dev.yipchat.core.data.account.AccountRepository
import dev.yipchat.core.model.ChannelPoll
import dev.yipchat.core.model.ChannelRewards
import dev.yipchat.core.model.CustomReward
import dev.yipchat.core.model.PollChoice
import dev.yipchat.core.model.Prediction
import dev.yipchat.core.model.PredictionOutcome
import dev.yipchat.core.network.twitch.GqlApi
import dev.yipchat.core.network.twitch.RewardsParser
import dev.yipchat.core.network.twitch.int
import dev.yipchat.core.network.twitch.obj
import dev.yipchat.core.network.twitch.path
import dev.yipchat.core.network.twitch.str
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber

data class PointsStatus(val channelId: String, val balance: Int, val claimId: String?)

/** Experimental: needs the optional twitch.tv web connection. All failures are soft. */
@Singleton
class PointsRepository @Inject constructor(private val gql: GqlApi, private val accounts: AccountRepository) {

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
    suspend fun rewards(channelLogin: String): Result<ChannelRewards> = attempt {
        // Signed-in first for the balance; fall back to the public list if that fails.
        val authed = if (accounts.webToken() != null) attempt { gql.channelRewards(channelLogin, authed = true) }.getOrNull() else null
        authed ?: gql.channelRewards(channelLogin, authed = false) ?: error("Channel not found")
    }

    /** Null on success, otherwise why it failed. */
    suspend fun redeem(channelId: String, reward: CustomReward, text: String?): String? {
        if (accounts.webToken() == null) return "Connect twitch.tv under Settings → Accounts to redeem"
        val input = kotlinx.serialization.json.buildJsonObject {
            put("channelID", JsonPrimitive(channelId))
            put("cost", JsonPrimitive(reward.cost))
            put("prompt", reward.prompt?.let(::JsonPrimitiveOf) ?: JsonNull)
            put("rewardID", JsonPrimitive(reward.id))
            put("title", JsonPrimitive(reward.title))
            // Lets Twitch drop accidental double taps.
            put("transactionID", JsonPrimitive(UUID.randomUUID().toString().replace("-", "")))
            if (reward.needsInput) put("textInput", JsonPrimitive(text.orEmpty()))
        }
        return attempt { gql.redeemReward(input) }.fold(
            onSuccess = { data ->
                val result = data.obj("redeemCommunityPointsCustomReward")
                result?.obj("error")?.str("code")?.let(RewardsParser::errorText)
                    ?: if (result?.obj("redemption") != null) null else "Twitch didn't confirm the redemption"
            },
            onFailure = { it.message ?: "Couldn't redeem" },
        )
    }

    /** Null on success, otherwise why it failed. [extraVotes] spends channel points for more votes. */
    suspend fun votePoll(poll: ChannelPoll, choice: PollChoice, extraVotes: Int = 0): String? {
        if (accounts.webToken() == null) return CONNECT
        val me = accounts.userId() ?: return CONNECT
        return attempt { gql.votePoll(poll.id, choice.id, me, extraVotes) }.fold(
            onSuccess = { it.obj("voteInPoll")?.obj("error")?.str("code")?.let(RewardsParser::errorText) },
            onFailure = { it.message ?: "Couldn't vote" },
        )
    }

    /** Null on success, otherwise why it failed. */
    suspend fun predict(prediction: Prediction, outcome: PredictionOutcome, points: Int): String? {
        if (accounts.webToken() == null) return CONNECT
        return attempt { gql.makePrediction(prediction.id, outcome.id, points) }.fold(
            onSuccess = { data ->
                val r = data.obj("makePrediction")
                r?.obj("error")?.str("code")?.let(RewardsParser::errorText)
                    ?: if (r?.obj("prediction") != null) null else "Twitch didn't confirm the prediction"
            },
            onFailure = { it.message ?: "Couldn't predict" },
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

private const val CONNECT = "Connect twitch.tv under Settings → Accounts first"

private fun JsonPrimitiveOf(s: String) = JsonPrimitive(s)
