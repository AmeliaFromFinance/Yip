package lol.sinist3r.yip.core.network.twitch

import kotlinx.serialization.json.JsonObject
import lol.sinist3r.yip.core.model.FollowedCategory

object WebFeaturesParser {
    fun followedGames(data: JsonObject): List<FollowedCategory> =
        data.path("currentUser", "followedGames")?.arr("nodes").orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { g ->
            FollowedCategory(g.str("id") ?: return@mapNotNull null, g.str("displayName") ?: g.str("name").orEmpty(), g.str("boxArtURL")?.boxArt())
        }

    /** Box art URLs come as templates or fixed sizes; ask for a small one. */
    private fun String.boxArt() = replace("{width}", "144").replace("{height}", "192")
}
