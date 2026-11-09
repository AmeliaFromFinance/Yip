package lol.sinist3r.yip.core.network.twitch

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import lol.sinist3r.yip.core.model.DropCampaign
import lol.sinist3r.yip.core.model.FollowedCategory
import lol.sinist3r.yip.core.model.TimedDrop

object WebFeaturesParser {
    fun followedGames(data: JsonObject): List<FollowedCategory> =
        data.path("currentUser", "followedGames")?.arr("nodes").orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { g ->
            FollowedCategory(g.str("id") ?: return@mapNotNull null, g.str("displayName") ?: g.str("name").orEmpty(), g.str("boxArtURL")?.boxArt())
        }

    fun drops(data: JsonObject): List<DropCampaign> =
        data.path("currentUser", "inventory")?.arr("dropCampaignsInProgress").orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { c ->
            val drops = c.arr("timeBasedDrops").mapNotNull { it as? JsonObject }.map { d ->
                val self = d.obj("self")
                val benefit = (d.arr("benefitEdges").firstOrNull() as? JsonObject)?.obj("benefit")
                TimedDrop(
                    id = d.str("id").orEmpty(),
                    name = d.str("name").orEmpty(),
                    requiredMinutes = d.int("requiredMinutesWatched") ?: 0,
                    watchedMinutes = self?.int("currentMinutesWatched") ?: 0,
                    claimed = self?.bool("isClaimed") == true,
                    claimId = self?.str("dropInstanceID"),
                    rewardName = benefit?.str("name"),
                    imageUrl = benefit?.str("imageAssetURL"),
                )
            }
            DropCampaign(
                id = c.str("id") ?: return@mapNotNull null,
                name = c.str("name").orEmpty(),
                gameName = c.obj("game")?.str("displayName"),
                boxArtUrl = c.obj("game")?.str("boxArtURL")?.boxArt(),
                endsAtMillis = c.str("endAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                drops = drops,
            )
        }

    /** Box art URLs come as templates or fixed sizes; ask for a small one. */
    private fun String.boxArt() = replace("{width}", "144").replace("{height}", "192")
}
