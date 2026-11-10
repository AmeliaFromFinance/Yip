package dev.yipchat.core.network.cosmetics

import dev.yipchat.core.model.Badge
import dev.yipchat.core.model.BadgeSource
import dev.yipchat.core.network.twitch.arr
import dev.yipchat.core.network.twitch.int
import dev.yipchat.core.network.twitch.obj
import dev.yipchat.core.network.twitch.str
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Badges from FFZ:AP and the Chatterino forks (Homies, Moltorino). All keyed by Twitch user id. */
@Singleton
class ClientBadgesApi @Inject constructor(private val http: HttpClient, private val json: Json) {

    private suspend fun getJson(url: String): JsonElement? {
        val res = http.get(url) { expectSuccess = false }
        if (!res.status.isSuccess()) return null
        return json.parseToJsonElement(res.bodyAsText())
    }

    suspend fun ffzApBadges(): UserBadges? = (getJson("https://api.ffzap.com/v1/supporters") as? JsonArray)?.let(::parseFfzAp)

    /** Developer / supporter / founder lists. Both lists are what the Homies client shows. */
    suspend fun homiesBadges(): UserBadges? {
        val lists = listOf("https://itzalex.github.io/badges", "https://itzalex.github.io/badges2").mapNotNull { getJson(it) as? JsonObject }
        if (lists.isEmpty()) return null
        return parseHomiesLists(lists)
    }

    /** Badges Homies users upload for themselves, one per user. ~4 MB, so callers cache the raw text. */
    suspend fun homiesCustomRaw(): String? {
        val res = http.get("https://chatterinohomies.com/api/badges/list") { expectSuccess = false }
        return if (res.status.isSuccess()) res.bodyAsText() else null
    }

    fun parseHomiesCustom(text: String): UserBadges? = (json.parseToJsonElement(text) as? JsonObject)?.let(::parseHomiesCustom)

    suspend fun moltorinoBadges(): UserBadges? = (getJson("https://api.moltorino.com/v2/badges") as? JsonObject)?.let(::parseMoltorino)

    companion object {
        private fun JsonElement.idString(): String? = (this as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

        fun parseFfzAp(arr: JsonArray): UserBadges {
            val badges = HashMap<String, Badge>()
            val users = HashMap<String, List<String>>()
            arr.forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                val uid = o["id"]?.idString() ?: return@forEach
                val tier = o.int("tier") ?: 0
                if (tier < 1) return@forEach
                // Tier 1: house colour. Tier 2+: their colour. Tier 3 with badge_is_colored: full-colour image, no background.
                val colour = when {
                    tier >= 3 && o.int("badge_is_colored") == 1 -> null
                    tier >= 2 -> o.str("badge_color") ?: FFZAP_COLOUR
                    else -> FFZAP_COLOUR
                }
                val id = "ffzap:$uid"
                val title = if (uid == FFZAP_DEVELOPER) "FFZ:AP Developer" else "FFZ:AP Supporter"
                badges[id] = Badge(id, title, (1..3).map { "https://api.ffzap.com/v1/user/badge/$uid/$it" }, BadgeSource.FFZAP, colour)
                users[uid] = listOf(id)
            }
            return UserBadges(badges, users)
        }

        fun parseHomiesLists(lists: List<JsonObject>): UserBadges {
            val badges = LinkedHashMap<String, Badge>()
            val users = HashMap<String, MutableList<String>>()
            lists.forEach { root ->
                root.arr("badges").forEach { e ->
                    val o = e as? JsonObject ?: return@forEach
                    val tooltip = o.str("tooltip") ?: "Chatterino Homies"
                    val urls = listOfNotNull(o.str("image1"), o.str("image2"), o.str("image3")).filter { it.startsWith("https://") }
                    if (urls.isEmpty()) return@forEach
                    // Same badge appears in both lists; key by art so a user never gets it twice.
                    val id = "homies:" + urls.first().hashCode().toUInt().toString(16)
                    badges.getOrPut(id) { Badge(id, tooltip, urls, BadgeSource.HOMIES) }
                    o.arr("users").forEach { u ->
                        val uid = u.idString() ?: return@forEach
                        val list = users.getOrPut(uid) { mutableListOf() }
                        if (id !in list) list += id
                    }
                }
            }
            return UserBadges(badges, users)
        }

        fun parseHomiesCustom(root: JsonObject): UserBadges {
            val badges = HashMap<String, Badge>()
            val users = HashMap<String, List<String>>()
            root.arr("badges").forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                val uid = o["userId"]?.idString() ?: return@forEach
                val urls = listOfNotNull(o.str("image1"), o.str("image2"), o.str("image3")).filter { it.startsWith("https://") }
                if (urls.isEmpty()) return@forEach
                val id = "homiesc:" + (o.str("badgeId") ?: uid)
                badges[id] = Badge(id, o.str("tooltip") ?: "Homies badge", urls, BadgeSource.HOMIES_CUSTOM)
                users[uid] = listOf(id)
            }
            return UserBadges(badges, users)
        }

        /**
         * Moltorino v2. Each user shows one Moltorino badge: the one they picked (`activeBadge`,
         * empty means none), otherwise their highest ranked (lowest `priority`). `hidden` lists
         * badge slots they turned off, which we respect for every provider we show.
         */
        fun parseMoltorino(root: JsonObject): UserBadges {
            val badges = HashMap<String, Badge>()
            val priority = HashMap<String, Int>()
            root.arr("badges").forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                val key = o.str("id")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: return@forEach
                val images = o.obj("images")
                val urls = listOf("1x", "2x", "3x").mapNotNull { images?.str(it) ?: o.str("image${it.first()}") }.filter { it.startsWith("https://") }
                if (urls.isEmpty()) return@forEach
                val id = "moltorino:$key"
                badges[id] = Badge(id, o.str("tooltip") ?: o.str("name") ?: "Moltorino", urls, BadgeSource.MOLTORINO)
                priority[id] = o.int("priority") ?: Int.MAX_VALUE
            }
            val users = HashMap<String, List<String>>()
            val hidden = HashMap<String, Set<String>>()
            root.obj("users")?.forEach { (uid, v) ->
                if (uid.isEmpty() || uid.length > 32 || !uid.all(Char::isDigit)) return@forEach
                val u = v as? JsonObject ?: return@forEach
                val hide = u.arr("hidden").mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.lowercase() }.toSet()
                if (hide.isNotEmpty()) hidden[uid] = hide
                if ("m" in hide) return@forEach
                val owned = u.arr("badges").mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.lowercase()?.let { k -> "moltorino:$k" } }.filter { it in badges }
                val pick = if (u.containsKey("activeBadge")) {
                    u.str("activeBadge")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "moltorino:$it" }?.takeIf { it in badges }
                } else {
                    owned.minByOrNull { priority[it] ?: Int.MAX_VALUE }
                }
                if (pick != null) users[uid] = listOf(pick)
            }
            return UserBadges(badges, users, hidden)
        }

        private const val FFZAP_COLOUR = "#755000"
        private const val FFZAP_DEVELOPER = "26964566"
    }
}
