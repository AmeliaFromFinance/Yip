package dev.yipchat.core.data.follow

import dev.yipchat.core.common.attempt
import dev.yipchat.core.data.account.AccountRepository
import dev.yipchat.core.network.twitch.GqlApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

sealed interface FollowResult {
    data object Done : FollowResult
    /** No twitch.tv connection; the UI should open the channel on twitch.tv instead. */
    data object NeedsWeb : FollowResult
    data class Failed(val reason: String) : FollowResult
}

/** Helix has no follow endpoint any more, so this needs the optional twitch.tv connection. */
@Singleton
class FollowRepository @Inject constructor(private val gql: GqlApi, private val accounts: AccountRepository) {
    suspend fun setFollowing(channelId: String, follow: Boolean): FollowResult {
        if (accounts.webToken() == null) return FollowResult.NeedsWeb
        val op = if (follow) "FollowButton_FollowUser" else "FollowButton_UnfollowUser"
        return attempt {
            gql.persisted(
                op,
                buildJsonObject {
                    putJsonObject("input") {
                        put("targetID", channelId)
                        if (follow) put("disableNotifications", false)
                    }
                },
                authenticated = true,
                integrity = true,
            )
        }.fold({ FollowResult.Done }, { FollowResult.Failed(it.message ?: "Couldn't update follow") })
    }
}
