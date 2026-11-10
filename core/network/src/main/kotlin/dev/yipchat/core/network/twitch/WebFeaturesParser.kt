package dev.yipchat.core.network.twitch

import dev.yipchat.core.model.FollowedCategory
import kotlinx.serialization.json.JsonObject

object WebFeaturesParser {
    fun followedGames(data: JsonObject): List<FollowedCategory> =
        data.path("currentUser", "followedGames")?.arr("nodes").orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { g ->
            FollowedCategory(g.str("id") ?: return@mapNotNull null, g.str("displayName") ?: g.str("name").orEmpty(), g.str("boxArtURL")?.boxArt())
        }

    /** Box art URLs come as templates or fixed sizes; ask for a small one. */
    private fun String.boxArt() = replace("{width}", "144").replace("{height}", "192")
}
