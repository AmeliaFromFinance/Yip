package lol.sinist3r.yip.core.network.cosmetics

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import lol.sinist3r.yip.core.model.Badge
import lol.sinist3r.yip.core.model.BadgeSource
import lol.sinist3r.yip.core.model.Emote
import lol.sinist3r.yip.core.model.EmoteProvider
import lol.sinist3r.yip.core.network.twitch.arr
import lol.sinist3r.yip.core.network.twitch.bool
import lol.sinist3r.yip.core.network.twitch.int
import lol.sinist3r.yip.core.network.twitch.obj
import lol.sinist3r.yip.core.network.twitch.str

/** Badge art plus which user ids wear it. */
data class UserBadges(val badges: Map<String, Badge>, val users: Map<String, List<String>>)

data class FfzRoom(val emotes: List<Emote>, val modBadgeUrls: List<String>?, val vipBadgeUrls: List<String>?)

@Singleton
class BttvFfzApi @Inject constructor(private val http: HttpClient, private val json: Json) {

    private suspend fun getJson(url: String): JsonElement? {
        val res = http.get(url) { expectSuccess = false }
        if (!res.status.isSuccess()) return null
        return json.parseToJsonElement(res.bodyAsText())
    }

    // region BTTV

    suspend fun bttvGlobal(): List<Emote> =
        (getJson("https://api.betterttv.net/3/cached/emotes/global") as? JsonArray)
            ?.mapNotNull { bttvEmote(it as? JsonObject, isChannel = false) }.orEmpty()

    suspend fun bttvChannel(twitchId: String): List<Emote> {
        val root = getJson("https://api.betterttv.net/3/cached/users/twitch/$twitchId") as? JsonObject ?: return emptyList()
        return (root.arr("channelEmotes") + root.arr("sharedEmotes")).mapNotNull { bttvEmote(it as? JsonObject, isChannel = true) }
    }

    suspend fun bttvBadges(): UserBadges {
        val arr = getJson("https://api.betterttv.net/3/cached/badges/twitch") as? JsonArray ?: return UserBadges(emptyMap(), emptyMap())
        val badges = mutableMapOf<String, Badge>()
        val users = mutableMapOf<String, MutableList<String>>()
        arr.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            val user = o.str("providerId") ?: return@forEach
            val b = o.obj("badge") ?: return@forEach
            val svg = b.str("svg") ?: return@forEach
            val id = "bttv:" + svg.hashCode()
            badges.getOrPut(id) { Badge(id, b.str("description") ?: "BTTV", listOf(svg), BadgeSource.BTTV) }
            users.getOrPut(user) { mutableListOf() } += id
        }
        return UserBadges(badges, users)
    }

    private fun bttvEmote(o: JsonObject?, isChannel: Boolean): Emote? {
        o ?: return null
        val id = o.str("id") ?: return null
        val code = o.str("code") ?: return null
        return Emote(
            id = id,
            code = code,
            provider = EmoteProvider.BTTV,
            urls = (1..3).map { "https://cdn.betterttv.net/emote/$id/${it}x.webp" },
            animated = o.bool("animated") ?: (o.str("imageType") == "gif"),
            zeroWidth = code in BTTV_ZERO_WIDTH,
            ownerName = o.obj("user")?.str("displayName"),
            isChannel = isChannel,
        )
    }

    // endregion

    // region FFZ

    suspend fun ffzGlobal(): List<Emote> {
        val root = getJson("https://api.frankerfacez.com/v1/set/global") as? JsonObject ?: return emptyList()
        val defaults = root.arr("default_sets").mapNotNull { it.str() }.toSet()
        return root.obj("sets")?.entries?.filter { it.key in defaults }
            ?.flatMap { (_, set) -> (set as? JsonObject)?.arr("emoticons").orEmpty() }
            ?.mapNotNull { ffzEmote(it as? JsonObject, isChannel = false) }.orEmpty()
    }

    suspend fun ffzRoom(twitchId: String): FfzRoom? {
        val root = getJson("https://api.frankerfacez.com/v1/room/id/$twitchId") as? JsonObject ?: return null
        val room = root.obj("room")
        val emotes = root.obj("sets")?.values?.flatMap { (it as? JsonObject)?.arr("emoticons").orEmpty() }
            ?.mapNotNull { ffzEmote(it as? JsonObject, isChannel = true) }.orEmpty()
        return FfzRoom(emotes, room?.obj("mod_urls")?.scaledUrls(), room?.obj("vip_badge")?.scaledUrls())
    }

    suspend fun ffzBadges(): UserBadges {
        val root = getJson("https://api.frankerfacez.com/v1/badges/ids") as? JsonObject ?: return UserBadges(emptyMap(), emptyMap())
        val badges = root.arr("badges").mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            "ffz:$id" to Badge("ffz:$id", o.str("title") ?: "FFZ", o.obj("urls")?.scaledUrls().orEmpty(), BadgeSource.FFZ, o.str("color"))
        }.toMap()
        val users = mutableMapOf<String, MutableList<String>>()
        root.obj("users")?.forEach { (badgeId, ids) ->
            (ids as? JsonArray)?.forEach { users.getOrPut(it.toString().trim('"')) { mutableListOf() } += "ffz:$badgeId" }
        }
        return UserBadges(badges, users)
    }

    private fun JsonObject.scaledUrls(): List<String> =
        listOf("1", "2", "4").mapNotNull { str(it)?.let { u -> if (u.startsWith("//")) "https:$u" else u } }

    private fun ffzEmote(o: JsonObject?, isChannel: Boolean): Emote? {
        o ?: return null
        val id = o.str("id") ?: return null
        val name = o.str("name") ?: return null
        val animated = o.obj("animated")
        val urls = (animated ?: o.obj("urls"))?.scaledUrls().orEmpty().map { if (animated != null) "$it.webp" else it }
        if (urls.isEmpty()) return null
        return Emote(
            id = id,
            code = name,
            provider = EmoteProvider.FFZ,
            urls = urls,
            animated = animated != null,
            zeroWidth = o.bool("modifier") == true && ((o.int("modifier_flags") ?: 0) and 1) == 0,
            ownerName = o.obj("owner")?.str("display_name"),
            isChannel = isChannel,
            width = o.int("width"),
            height = o.int("height"),
        )
    }

    // endregion

    suspend fun chatterinoBadges(): UserBadges {
        val root = getJson("https://api.chatterino.com/badges") as? JsonObject ?: return UserBadges(emptyMap(), emptyMap())
        val badges = mutableMapOf<String, Badge>()
        val users = mutableMapOf<String, MutableList<String>>()
        root.arr("badges").forEachIndexed { i, e ->
            val o = e as? JsonObject ?: return@forEachIndexed
            val id = "chatterino:$i"
            badges[id] = Badge(id, o.str("tooltip") ?: "Chatterino", listOfNotNull(o.str("image1"), o.str("image2"), o.str("image3")), BadgeSource.CHATTERINO)
            o.arr("users").forEach { users.getOrPut(it.toString().trim('"')) { mutableListOf() } += id }
        }
        return UserBadges(badges, users)
    }

    /** Raw IRC lines of recent chat, from the community recent-messages service. */
    suspend fun recentMessages(login: String, limit: Int = 100): List<String> {
        val root = getJson(
            "https://recent-messages.robotty.de/api/v2/recent-messages/${URLEncoder.encode(login.lowercase(), "UTF-8")}?limit=$limit",
        ) as? JsonObject ?: return emptyList()
        return root.arr("messages").mapNotNull { it.str() }
    }

    companion object {
        val BTTV_ZERO_WIDTH = setOf("SoSnowy", "IceCold", "SantaHat", "TopHat", "ReinDeer", "CandyCane", "cvMask", "cvHazmat")
    }
}
