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
class PointsRepository @Inject constructor(private val gql: GqlApi) {

    suspend fun status(channelLogin: String): PointsStatus? = attempt {
        val ch = gql.channelPointsContext(channelLogin).path("community", "channel") ?: return@attempt null
        val points = ch.path("self", "communityPoints")
        PointsStatus(
            channelId = ch.str("id") ?: return@attempt null,
            balance = points?.int("balance") ?: 0,
            claimId = points?.obj("availableClaim")?.str("id"),
        )
    }.onFailure { Timber.d(it, "points status failed") }.getOrNull()

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
