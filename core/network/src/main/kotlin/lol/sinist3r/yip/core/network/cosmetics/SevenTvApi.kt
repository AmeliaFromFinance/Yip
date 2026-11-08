package lol.sinist3r.yip.core.network.cosmetics

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
import lol.sinist3r.yip.core.network.twitch.str

data class SevenTvChannel(val sevenTvUserId: String?, val emoteSetId: String?, val emotes: List<Emote>)

/** 7TV REST v3. Parsing lives here so the EventAPI client can reuse it. */
@Singleton
class SevenTvApi @Inject constructor(private val http: HttpClient, private val json: Json) {

    suspend fun channel(twitchId: String): SevenTvChannel? {
        val res = http.get("https://7tv.io/v3/users/twitch/$twitchId") { expectSuccess = false }
        if (res.status == HttpStatusCode.NotFound || !res.status.isSuccess()) return null
        val root = json.parseToJsonElement(res.bodyAsText()).jsonObject
        val set = root.obj("emote_set")
        return SevenTvChannel(
            sevenTvUserId = root.obj("user")?.str("id"),
            emoteSetId = set?.str("id"),
            emotes = set?.arr("emotes")?.mapNotNull { parseActiveEmote(it as? JsonObject, isChannel = true) }.orEmpty(),
        )
    }

    suspend fun globalEmotes(): List<Emote> = emoteSet("global")?.second.orEmpty()

    /** Returns (isPersonal, emotes). */
    suspend fun emoteSet(id: String, personal: Boolean = false): Pair<Boolean, List<Emote>>? {
        val res = http.get("https://7tv.io/v3/emote-sets/$id") { expectSuccess = false }
        if (!res.status.isSuccess()) return null
        val root = json.parseToJsonElement(res.bodyAsText()).jsonObject
        val isPersonal = personal || ((root.long("flags") ?: 0) and 4L) != 0L
        return isPersonal to root.arr("emotes").mapNotNull { parseActiveEmote(it as? JsonObject, isChannel = false, personal = isPersonal) }
    }

    companion object {
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
    }
}
