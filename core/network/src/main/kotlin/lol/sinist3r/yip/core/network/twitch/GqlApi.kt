package lol.sinist3r.yip.core.network.twitch

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import lol.sinist3r.yip.core.common.AppException
import lol.sinist3r.yip.core.model.RemoteConfig
import lol.sinist3r.yip.core.network.WebTokenProvider
import lol.sinist3r.yip.core.network.config.RemoteConfigSource

/**
 * Twitch's private GraphQL API, called the way the web player calls it.
 * Raw queries for browse data; persisted operations (hashes from [RemoteConfigSource]) for the rest.
 */
@Singleton
class GqlApi @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
    private val config: RemoteConfigSource,
    private val webTokens: WebTokenProvider,
) {
    /** Stable per-install device id, as the web player sends. */
    val deviceId: String by lazy {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val rnd = SecureRandom()
        (1..32).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    suspend fun query(document: String, variables: JsonObject = JsonObject(emptyMap())): JsonObject {
        val body = buildJsonObject {
            put("query", document)
            put("variables", variables)
        }
        return send(body, authenticated = false)
    }

    suspend fun persisted(
        operation: String,
        variables: JsonObject,
        authenticated: Boolean = false,
        integrity: Boolean = false,
    ): JsonObject {
        val cfg = config.current()
        val hash = cfg.hash(operation) ?: throw AppException(AppException.Kind.CONFIG, "No hash for $operation")
        val body = buildJsonObject {
            put("operationName", operation)
            put("variables", variables)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", hash)
                }
            }
        }
        return send(body, authenticated, integrity, cfg)
    }

    private suspend fun send(
        body: JsonObject,
        authenticated: Boolean,
        integrity: Boolean = false,
        cfg: RemoteConfig? = null,
    ): JsonObject {
        val conf = cfg ?: config.current()
        val webToken = if (authenticated) webTokens.webToken() ?: throw AppException(AppException.Kind.AUTH, "twitch.tv not connected") else null
        val integrityToken = if (integrity) webTokens.integrityToken() else null
        val res = http.post("https://gql.twitch.tv/gql") {
            expectSuccess = false
            header("Client-Id", conf.clientId)
            header("Device-Id", integrityToken?.deviceId ?: deviceId)
            conf.userAgent?.let { header(HttpHeaders.UserAgent, it) }
            webToken?.let { header(HttpHeaders.Authorization, "OAuth $it") }
            integrityToken?.let { header("Client-Integrity", it.token) }
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = res.bodyAsText()
        if (!res.status.isSuccess()) throw AppException(AppException.Kind.NETWORK, "GQL ${res.status.value}: ${text.take(160)}")
        val root = json.parseToJsonElement(text).jsonObject
        root["errors"]?.let { errs ->
            val msg = (errs as? JsonArray)?.firstOrNull()?.jsonObject?.get("message")?.str() ?: errs.toString()
            if (root["data"] == null || root["data"] is JsonNull) {
                val kind = when {
                    "PersistedQueryNotFound" in msg -> AppException.Kind.CONFIG
                    "integrity" in msg.lowercase() -> AppException.Kind.PLAYBACK_BLOCKED
                    "unauthorized" in msg.lowercase() -> AppException.Kind.AUTH
                    else -> AppException.Kind.UNKNOWN
                }
                throw AppException(kind, "GQL error: $msg")
            }
        }
        return root["data"]?.let { it as? JsonObject } ?: JsonObject(emptyMap())
    }

    // region typed helpers

    data class AccessToken(val value: String, val signature: String)

    /** Uses the raw template query (no hash to rotate) and falls back to the persisted op. */
    private suspend fun playbackToken(login: String, videoId: String, live: Boolean, playerType: String, platform: String): JsonObject {
        val vars = buildJsonObject {
            put("isLive", live)
            put("login", if (live) login.lowercase() else "")
            put("isVod", !live)
            put("vodID", if (live) "" else videoId)
            put("playerType", playerType)
            put("platform", platform)
        }
        val template = config.current().query("PlaybackAccessToken_Template")
        if (template != null) {
            val body = buildJsonObject {
                put("operationName", "PlaybackAccessToken_Template")
                put("query", template)
                put("variables", vars)
            }
            val result = runCatching { send(body, authenticated = false) }
            result.getOrNull()?.let { return it }
        }
        return persisted("PlaybackAccessToken", vars)
    }

    suspend fun streamAccessToken(login: String, playerType: String, platform: String = "web"): AccessToken? =
        playbackToken(login, "", live = true, playerType, platform).obj("streamPlaybackAccessToken")?.toToken()

    suspend fun videoAccessToken(videoId: String, playerType: String): AccessToken? =
        playbackToken("", videoId, live = false, playerType, "web").obj("videoPlaybackAccessToken")?.toToken()

    data class ClipSource(val quality: String, val frameRate: Int?, val url: String)

    suspend fun clipSources(slug: String): List<ClipSource> {
        val clip = persisted(
            "VideoAccessToken_Clip",
            buildJsonObject {
                put("slug", slug)
                put("platform", "web")
            },
        ).obj("clip") ?: return emptyList()
        val token = clip.obj("playbackAccessToken")?.toToken() ?: return emptyList()
        val sig = java.net.URLEncoder.encode(token.signature, "UTF-8")
        val tok = java.net.URLEncoder.encode(token.value, "UTF-8")
        return clip.arr("videoQualities").mapNotNull { q ->
            val o = q.jsonObject
            val url = o.str("sourceURL") ?: return@mapNotNull null
            ClipSource(o.str("quality") ?: "?", o["frameRate"]?.str()?.toDoubleOrNull()?.toInt(), "$url?sig=$sig&token=$tok")
        }
    }

    suspend fun videoComments(videoId: String, offsetSeconds: Int?, cursor: String?): JsonObject =
        persisted(
            "VideoCommentsByOffsetOrCursor",
            buildJsonObject {
                put("videoID", videoId)
                if (cursor != null) put("cursor", cursor) else put("contentOffsetSeconds", offsetSeconds ?: 0)
            },
        )

    suspend fun channelPointsContext(channelLogin: String): JsonObject =
        persisted(
            "ChannelPointsContext",
            buildJsonObject {
                put("channelLogin", channelLogin)
                put("includeGoalTypes", JsonArray(listOf(JsonPrimitive("CREATOR"), JsonPrimitive("BOOST"))))
            },
            authenticated = true,
        )

    suspend fun claimPoints(channelId: String, claimId: String): JsonObject =
        persisted(
            "ClaimCommunityPoints",
            buildJsonObject {
                putJsonObject("input") {
                    put("channelID", channelId)
                    put("claimID", claimId)
                }
            },
            authenticated = true,
            integrity = true,
        )

    suspend fun chapters(videoId: String): JsonObject =
        persisted("VideoPlayer_ChapterSelectButtonVideo", buildJsonObject { put("videoID", videoId); put("includePrivate", false) })

    // Raw browse queries
    suspend fun topStreams(languages: List<String>, first: Int, after: String?) = query(GqlQueries.TOP_STREAMS, vars(first, after) {
        if (languages.isNotEmpty()) put("languages", JsonArray(languages.map { JsonPrimitive(it.uppercase()) }))
    })

    suspend fun gameStreams(gameId: String, languages: List<String>, first: Int, after: String?) = query(GqlQueries.GAME_STREAMS, vars(first, after) {
        put("id", gameId)
        if (languages.isNotEmpty()) put("languages", JsonArray(languages.map { JsonPrimitive(it.uppercase()) }))
    })

    suspend fun topGames(first: Int, after: String?) = query(GqlQueries.TOP_GAMES, vars(first, after))
    suspend fun searchChannels(q: String, first: Int, after: String?) = query(GqlQueries.SEARCH_CHANNELS, vars(first, after) { put("query", q) })
    suspend fun searchGames(q: String, first: Int, after: String?) = query(GqlQueries.SEARCH_GAMES, vars(first, after) { put("query", q) })
    suspend fun usersStream(ids: List<String> = emptyList(), logins: List<String> = emptyList()) = query(
        GqlQueries.USERS_STREAM,
        buildJsonObject {
            if (ids.isNotEmpty()) put("ids", JsonArray(ids.map(::JsonPrimitive)))
            if (logins.isNotEmpty()) put("logins", JsonArray(logins.map(::JsonPrimitive)))
        },
    )

    suspend fun userVideos(login: String, types: List<String>?, first: Int, after: String?) = query(GqlQueries.USER_VIDEOS, vars(first, after) {
        put("login", login)
        types?.let { put("types", JsonArray(it.map(::JsonPrimitive))) }
    })

    suspend fun userClips(login: String, period: String, first: Int, after: String?) = query(GqlQueries.USER_CLIPS, vars(first, after) {
        put("login", login)
        put("period", period)
    })

    suspend fun globalBadges() = query(GqlQueries.GLOBAL_BADGES)
    suspend fun channelEvents(login: String): lol.sinist3r.yip.core.model.ChannelEvents =
        ChannelEventsParser.parse(query(GqlQueries.CHANNEL_EVENTS, buildJsonObject { put("login", login.lowercase()) }))

    suspend fun channelBadges(channelId: String) = query(GqlQueries.CHANNEL_BADGES, buildJsonObject { put("id", channelId) })

    suspend fun videoInfo(id: String) = query(GqlQueries.VIDEO_INFO, buildJsonObject { put("id", id) })

    private inline fun vars(first: Int, after: String?, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("first", first)
        after?.let { put("after", it) }
        extra()
    }

    private fun JsonObject.toToken(): AccessToken? {
        val v = str("value") ?: return null
        val s = str("signature") ?: return null
        return AccessToken(v, s)
    }

    // endregion
}

// Small JSON navigation helpers shared by the data layer.
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
fun JsonObject.str(key: String): String? = this[key].str()
fun JsonObject.int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()
fun JsonObject.long(key: String): Long? = str(key)?.toDoubleOrNull()?.toLong()
fun JsonObject.bool(key: String): Boolean? = str(key)?.toBooleanStrictOrNull()
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: emptyList()
fun JsonObject.path(vararg keys: String): JsonObject? = keys.fold(this as JsonObject?) { acc, k -> acc?.obj(k) }
fun JsonObject.edges(vararg path: String): List<JsonObject> = (path(*path)?.arr("edges") ?: emptyList()).mapNotNull { it as? JsonObject }
