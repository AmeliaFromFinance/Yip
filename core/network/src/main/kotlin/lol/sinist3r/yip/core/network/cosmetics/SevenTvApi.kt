package lol.sinist3r.yip.core.network.cosmetics

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import lol.sinist3r.yip.core.common.AppException
import lol.sinist3r.yip.core.model.Badge
import lol.sinist3r.yip.core.model.BadgeSource
import lol.sinist3r.yip.core.model.Emote
import lol.sinist3r.yip.core.model.EmoteProvider
import lol.sinist3r.yip.core.model.Paint
import lol.sinist3r.yip.core.model.PaintFunction
import lol.sinist3r.yip.core.model.PaintShadow
import lol.sinist3r.yip.core.model.PaintStop
import lol.sinist3r.yip.core.network.twitch.arr
import lol.sinist3r.yip.core.network.twitch.bool
import lol.sinist3r.yip.core.network.twitch.int
import lol.sinist3r.yip.core.network.twitch.long
import lol.sinist3r.yip.core.network.twitch.obj
import lol.sinist3r.yip.core.network.twitch.path
import lol.sinist3r.yip.core.network.twitch.str

data class SevenTvChannel(val sevenTvUserId: String?, val emoteSetId: String?, val emotes: List<Emote>)

/** A chatter's active 7TV cosmetics, by id. Definitions come from [SevenTvApi.paints] / [SevenTvApi.badges]. */
data class SevenTvStyle(val paintId: String?, val badgeId: String?, val personalSetId: String?)

/**
 * 7TV API v4 (GraphQL). The EventAPI still speaks v3, so the v3 payload parsers live in the
 * companion for [SevenTvEventClient].
 */
@Singleton
class SevenTvApi @Inject constructor(private val http: HttpClient, private val json: Json) {

    private suspend fun query(query: String, variables: Map<String, String> = emptyMap()): JsonObject {
        val res = http.post(ENDPOINT) {
            expectSuccess = false
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("query", query)
                    putJsonObject("variables") { variables.forEach { (k, v) -> put(k, v) } }
                }.toString(),
            )
        }
        if (!res.status.isSuccess()) throw AppException(AppException.Kind.NETWORK, "7TV returned ${res.status.value}")
        val root = json.parseToJsonElement(res.bodyAsText()).jsonObject
        root.arr("errors").firstOrNull()?.let { if (root.obj("data") == null) throw AppException(AppException.Kind.UNKNOWN, "7TV: ${(it as? JsonObject)?.str("message")}") }
        return root.obj("data") ?: throw AppException(AppException.Kind.UNKNOWN, "7TV returned no data")
    }

    suspend fun channel(twitchId: String): SevenTvChannel? {
        val user = query(
            "$EMOTE_FRAGMENT query(\$id: String!) { users { userByConnection(platform: TWITCH, platformId: \$id) { id style { activeEmoteSet { id emotes { items { ...E } } } } } } }",
            mapOf("id" to twitchId),
        ).path("users")?.obj("userByConnection") ?: return null
        val set = user.obj("style")?.obj("activeEmoteSet")
        return SevenTvChannel(
            sevenTvUserId = user.str("id"),
            emoteSetId = set?.str("id"),
            emotes = set?.obj("emotes")?.arr("items")?.mapNotNull { parseSetEmote(it as? JsonObject, isChannel = true) }.orEmpty(),
        )
    }

    suspend fun globalEmotes(): List<Emote> =
        query("$EMOTE_FRAGMENT query { emoteSets { global { emotes { items { ...E } } } } }")
            .path("emoteSets", "global", "emotes")?.arr("items")?.mapNotNull { parseSetEmote(it as? JsonObject, isChannel = false) }.orEmpty()

    /** Returns (isPersonal, emotes). */
    suspend fun emoteSet(id: String, personal: Boolean = false): Pair<Boolean, List<Emote>>? {
        val set = query("$EMOTE_FRAGMENT query(\$id: Id!) { emoteSets { emoteSet(id: \$id) { kind emotes { items { ...E } } } } }", mapOf("id" to id))
            .path("emoteSets")?.obj("emoteSet") ?: return null
        val isPersonal = personal || set.str("kind") == "PERSONAL"
        return isPersonal to set.obj("emotes")?.arr("items")?.mapNotNull { parseSetEmote(it as? JsonObject, isChannel = false, personal = isPersonal) }.orEmpty()
    }

    /** Every paint 7TV has (about 1k, ~1 MB). Callers should cache. */
    suspend fun paints(): List<Paint> =
        query("query { paints { paints { $PAINT_FIELDS } } }").path("paints")?.arr("paints")?.mapNotNull { parsePaintV4(it as? JsonObject) }.orEmpty()

    suspend fun paint(id: String): Paint? =
        parsePaintV4(query("query(\$id: Id!) { paints { paint(id: \$id) { $PAINT_FIELDS } } }", mapOf("id" to id)).path("paints")?.obj("paint"))

    suspend fun badges(): List<Badge> =
        query("query { badges { badges { $BADGE_FIELDS } } }").path("badges")?.arr("badges")?.mapNotNull { parseBadgeV4(it as? JsonObject) }.orEmpty()

    suspend fun badge(id: String): Badge? =
        parseBadgeV4(query("query(\$id: Id!) { badges { badge(id: \$id) { $BADGE_FIELDS } } }", mapOf("id" to id)).path("badges")?.obj("badge"))

    /**
     * Active paint/badge ids for many Twitch users in one request. Keys missing from the result
     * have no 7TV account. Max [MAX_STYLE_BATCH] per call (server complexity limit).
     */
    suspend fun styles(twitchIds: Collection<String>): Map<String, SevenTvStyle> {
        val ids = twitchIds.filter { it.isNotEmpty() && it.all(Char::isDigit) }.distinct().take(MAX_STYLE_BATCH)
        if (ids.isEmpty()) return emptyMap()
        val vars = ids.withIndex().associate { (i, id) -> "a$i" to id }
        val q = buildString {
            append("query(")
            append(vars.keys.joinToString(", ") { "\$$it: String!" })
            append(") { users { ")
            vars.keys.forEach { append("$it: userByConnection(platform: TWITCH, platformId: \$$it) { style { activePaintId activeBadgeId } personalEmoteSet { id } } ") }
            append("} }")
        }
        val users = query(q, vars).obj("users") ?: return emptyMap()
        return vars.mapNotNull { (alias, twitchId) ->
            val u = users.obj(alias) ?: return@mapNotNull null
            val style = u.obj("style")
            twitchId to SevenTvStyle(style?.str("activePaintId"), style?.str("activeBadgeId"), u.obj("personalEmoteSet")?.str("id"))
        }.toMap()
    }

    companion object {
        private const val ENDPOINT = "https://7tv.io/v4/gql"
        const val MAX_STYLE_BATCH = 50

        private const val EMOTE_FRAGMENT =
            "fragment E on EmoteSetEmote { id alias flags { zeroWidth } emote { id defaultName flags { animated } " +
                "owner { mainConnection { platformDisplayName } } images { url mime scale width height frameCount } } }"
        private const val PAINT_FIELDS =
            "id name data { layers { opacity ty { __typename " +
                "... on PaintLayerTypeSingleColor { color { hex } } " +
                "... on PaintLayerTypeLinearGradient { angle repeating stops { at color { hex } } } " +
                "... on PaintLayerTypeRadialGradient { repeating stops { at color { hex } } } " +
                "... on PaintLayerTypeImage { images { url mime scale frameCount } } } } " +
                "shadows { color { hex } offsetX offsetY blur } }"
        private const val BADGE_FIELDS = "id name description images { url mime scale }"

        /** "#RRGGBBAA" -> ARGB, with an extra opacity multiplier. */
        fun hexToArgb(hex: String?, opacity: Float = 1f): Long? {
            val h = hex?.removePrefix("#") ?: return null
            val v = h.toLongOrNull(16) ?: return null
            val rgba = when (h.length) { 8 -> v; 6 -> (v shl 8) or 0xFF; else -> return null }
            val argb = rgbaToArgb(rgba)
            val a = Math.round(((argb ushr 24) and 0xFF) * opacity.coerceIn(0f, 1f)).toLong()
            return (a shl 24) or (argb and 0xFFFFFF)
        }

        /** Picks WEBP images sorted by scale; animated ones unless only static frames exist. */
        private fun webpUrls(images: List<JsonObject>, animated: Boolean): List<JsonObject> {
            val webp = images.filter { it.str("mime") == "image/webp" }.ifEmpty { images }
            val (stat, anim) = webp.partition { it.str("url")?.contains("_static.") == true }
            val pick = if (animated) anim.ifEmpty { stat } else stat.ifEmpty { anim }
            return pick.sortedBy { it.int("scale") ?: 1 }
        }

        fun parseSetEmote(o: JsonObject?, isChannel: Boolean, personal: Boolean = false): Emote? {
            o ?: return null
            val e = o.obj("emote") ?: return null
            val id = e.str("id") ?: o.str("id") ?: return null
            val animated = e.obj("flags")?.bool("animated") ?: false
            val imgs = webpUrls(e.arr("images").mapNotNull { it as? JsonObject }, animated)
            val urls = imgs.mapNotNull { it.str("url") }.ifEmpty { (1..4).map { "https://cdn.7tv.app/emote/$id/${it}x.webp" } }
            val first = imgs.firstOrNull()
            return Emote(
                id = id,
                code = o.str("alias") ?: e.str("defaultName") ?: return null,
                provider = EmoteProvider.SEVENTV,
                urls = urls,
                animated = animated,
                zeroWidth = o.obj("flags")?.bool("zeroWidth") ?: false,
                ownerName = e.obj("owner")?.obj("mainConnection")?.str("platformDisplayName"),
                isChannel = isChannel,
                isPersonal = personal,
                width = first?.int("width"),
                height = first?.int("height"),
            )
        }

        /** 7TV paints are layered; every paint in use today has one layer, so the first drawable one wins. */
        fun parsePaintV4(o: JsonObject?): Paint? {
            o ?: return null
            val id = o.str("id") ?: return null
            val data = o.obj("data") ?: return null
            val layer = data.arr("layers").mapNotNull { it as? JsonObject }.firstOrNull()
            val opacity = layer?.str("opacity")?.toFloatOrNull() ?: 1f
            val ty = layer?.obj("ty")
            val stops = ty?.arr("stops").orEmpty().mapNotNull { s ->
                val so = s as? JsonObject ?: return@mapNotNull null
                PaintStop(so.str("at")?.toFloatOrNull() ?: 0f, hexToArgb(so.obj("color")?.str("hex"), opacity) ?: return@mapNotNull null)
            }
            val single = hexToArgb(ty?.obj("color")?.str("hex"), opacity)
            val image = if (ty?.str("__typename") == "PaintLayerTypeImage") {
                // Static frame at 3x: sharp at chat sizes, and Compose shaders can't animate.
                webpUrls(ty.arr("images").mapNotNull { it as? JsonObject }, animated = false).let { l -> l.getOrNull(2) ?: l.lastOrNull() }?.str("url")
            } else null
            return Paint(
                id = id,
                name = o.str("name").orEmpty(),
                function = when (ty?.str("__typename")) {
                    "PaintLayerTypeRadialGradient" -> PaintFunction.RADIAL_GRADIENT
                    "PaintLayerTypeImage" -> PaintFunction.URL
                    else -> PaintFunction.LINEAR_GRADIENT
                },
                angle = ty?.str("angle")?.toFloatOrNull() ?: 90f,
                repeat = ty?.bool("repeating") ?: false,
                stops = stops,
                imageUrl = image,
                shadows = data.arr("shadows").mapNotNull { s ->
                    val so = s as? JsonObject ?: return@mapNotNull null
                    PaintShadow(
                        x = so.str("offsetX")?.toFloatOrNull() ?: 0f,
                        y = so.str("offsetY")?.toFloatOrNull() ?: 0f,
                        radius = so.str("blur")?.toFloatOrNull() ?: 0f,
                        argb = hexToArgb(so.obj("color")?.str("hex")) ?: return@mapNotNull null,
                    )
                },
                fallbackArgb = single ?: stops.firstOrNull()?.argb,
            )
        }

        fun parseBadgeV4(o: JsonObject?): Badge? {
            o ?: return null
            val id = o.str("id") ?: return null
            val urls = webpUrls(o.arr("images").mapNotNull { it as? JsonObject }, animated = true).mapNotNull { it.str("url") }
                .ifEmpty { (1..3).map { "https://cdn.7tv.app/badge/$id/${it}x.webp" } }
            return Badge(id = "7tv:$id", title = o.str("description")?.ifBlank { null } ?: o.str("name") ?: "7TV", urls = urls, source = BadgeSource.SEVENTV)
        }

        // region EventAPI v3 payloads

        private const val ACTIVE_ZERO_WIDTH = 1L
        private const val EMOTE_ZERO_WIDTH = 1L shl 8

        fun parseActiveEmote(o: JsonObject?, isChannel: Boolean, personal: Boolean = false): Emote? {
            o ?: return null
            val data = o.obj("data") ?: return null
            val id = o.str("id") ?: data.str("id") ?: return null
            val name = o.str("name") ?: data.str("name") ?: return null
            val host = data.obj("host") ?: return null
            val base = "https:" + (host.str("url") ?: return null)
            val files = host.arr("files").mapNotNull { it as? JsonObject }
            val webp = files.filter { it.str("format") == "WEBP" }.ifEmpty { files }
            val urls = webp.map { "$base/${it.str("name")}" }.ifEmpty { (1..4).map { "$base/${it}x.webp" } }
            val first = webp.firstOrNull()
            val activeFlags = o.long("flags") ?: 0
            val emoteFlags = data.long("flags") ?: 0
            return Emote(
                id = id,
                code = name,
                provider = EmoteProvider.SEVENTV,
                urls = urls,
                animated = data.bool("animated") ?: false,
                zeroWidth = (activeFlags and ACTIVE_ZERO_WIDTH) != 0L || (emoteFlags and EMOTE_ZERO_WIDTH) != 0L,
                ownerName = data.obj("owner")?.str("display_name"),
                isChannel = isChannel,
                isPersonal = personal,
                width = first?.int("width"),
                height = first?.int("height"),
            )
        }

        /** 7TV colours are signed 32-bit RGBA; Compose wants ARGB. */
        fun rgbaToArgb(rgba: Long): Long {
            val v = rgba and 0xFFFFFFFFL
            val r = (v shr 24) and 0xFF
            val g = (v shr 16) and 0xFF
            val b = (v shr 8) and 0xFF
            val a = v and 0xFF
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        fun parsePaint(id: String, data: JsonObject): Paint {
            val fn = when (data.str("function")) {
                "RADIAL_GRADIENT" -> PaintFunction.RADIAL_GRADIENT
                "URL" -> PaintFunction.URL
                else -> PaintFunction.LINEAR_GRADIENT
            }
            // v3 puts gradient info at the top level; newer payloads nest it in "gradients".
            val g = data.arr("gradients").firstOrNull() as? JsonObject ?: data
            val stops = g.arr("stops").mapNotNull { s ->
                val so = s as? JsonObject ?: return@mapNotNull null
                PaintStop(so.str("at")?.toFloatOrNull() ?: 0f, rgbaToArgb(so.long("color") ?: 0))
            }
            return Paint(
                id = id,
                name = data.str("name") ?: "",
                function = fn,
                angle = (g.str("angle") ?: data.str("angle"))?.toFloatOrNull() ?: 90f,
                repeat = g.bool("repeat") ?: data.bool("repeat") ?: false,
                stops = stops,
                imageUrl = g.str("image_url") ?: data.str("image_url"),
                shadows = data.arr("shadows").mapNotNull { s ->
                    val so = s as? JsonObject ?: return@mapNotNull null
                    PaintShadow(
                        x = so.str("x_offset")?.toFloatOrNull() ?: 0f,
                        y = so.str("y_offset")?.toFloatOrNull() ?: 0f,
                        radius = so.str("radius")?.toFloatOrNull() ?: 0f,
                        argb = rgbaToArgb(so.long("color") ?: 0),
                    )
                },
                fallbackArgb = data.long("color")?.let(::rgbaToArgb),
            )
        }

        fun parseBadge(id: String, data: JsonObject): Badge? {
            val host = data.obj("host")
            val urls = if (host != null) {
                val base = "https:" + (host.str("url") ?: return null)
                host.arr("files").mapNotNull { (it as? JsonObject)?.str("name") }.filter { it.endsWith(".webp") }
                    .ifEmpty { listOf("1x.webp", "2x.webp", "3x.webp") }.map { "$base/$it" }
            } else {
                (1..3).map { "https://cdn.7tv.app/badge/$id/${it}x.webp" }
            }
            return Badge(id = "7tv:$id", title = data.str("tooltip") ?: data.str("name") ?: "7TV", urls = urls, source = BadgeSource.SEVENTV)
        }

        // endregion
    }
}
