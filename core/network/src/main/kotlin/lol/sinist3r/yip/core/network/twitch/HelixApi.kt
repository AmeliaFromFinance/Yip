package lol.sinist3r.yip.core.network.twitch

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lol.sinist3r.yip.core.common.AppException
import lol.sinist3r.yip.core.network.BuildConfig
import lol.sinist3r.yip.core.network.SnakeJson
import lol.sinist3r.yip.core.network.TokenProvider

/**
 * Twitch Helix REST. Every call goes through [call], which attaches the user's token,
 * refreshes once on 401 and maps errors to [AppException].
 */
@Singleton
class HelixApi @Inject constructor(
    private val http: HttpClient,
    @SnakeJson val json: Json,
    private val tokens: TokenProvider,
) {
    private val base = "https://api.twitch.tv/helix"

    suspend fun users(ids: List<String> = emptyList(), logins: List<String> = emptyList()): List<HelixUser> {
        if (ids.isEmpty() && logins.isEmpty()) return currentUser()?.let(::listOf).orEmpty()
        return (ids.map { "id" to it } + logins.map { "login" to it }).chunked(100).flatMap { chunk ->
            get<HelixPage<HelixUser>>("/users") { chunk.forEach { (k, v) -> parameter(k, v) } }.data
        }
    }

    suspend fun currentUser(): HelixUser? = get<HelixPage<HelixUser>>("/users").data.firstOrNull()

    suspend fun followedStreams(userId: String, cursor: String? = null): HelixPage<HelixStream> =
        get("/streams/followed") {
            parameter("user_id", userId)
            parameter("first", 100)
            cursor?.let { parameter("after", it) }
        }

    suspend fun streams(
        cursor: String? = null,
        gameId: String? = null,
        userIds: List<String> = emptyList(),
        userLogins: List<String> = emptyList(),
        languages: List<String> = emptyList(),
        first: Int = 40,
    ): HelixPage<HelixStream> = get("/streams") {
        parameter("first", first)
        cursor?.let { parameter("after", it) }
        gameId?.let { parameter("game_id", it) }
        userIds.forEach { parameter("user_id", it) }
        userLogins.forEach { parameter("user_login", it) }
        languages.forEach { parameter("language", it) }
    }

    suspend fun topGames(cursor: String? = null): HelixPage<HelixGame> = get("/games/top") {
        parameter("first", 60)
        cursor?.let { parameter("after", it) }
    }

    suspend fun games(ids: List<String> = emptyList(), names: List<String> = emptyList()): List<HelixGame> =
        get<HelixPage<HelixGame>>("/games") {
            ids.forEach { parameter("id", it) }
            names.forEach { parameter("name", it) }
        }.data

    suspend fun searchChannels(query: String, liveOnly: Boolean = false, cursor: String? = null): HelixPage<HelixSearchChannel> =
        get("/search/channels") {
            parameter("query", query)
            parameter("first", 40)
            parameter("live_only", liveOnly)
            cursor?.let { parameter("after", it) }
        }

    suspend fun searchCategories(query: String, cursor: String? = null): HelixPage<HelixGame> =
        get("/search/categories") {
            parameter("query", query)
            parameter("first", 40)
            cursor?.let { parameter("after", it) }
        }

    suspend fun followedChannels(userId: String, cursor: String? = null): HelixPage<HelixFollowed> =
        get("/channels/followed") {
            parameter("user_id", userId)
            parameter("first", 100)
            cursor?.let { parameter("after", it) }
        }

    suspend fun isFollowing(userId: String, broadcasterId: String): Boolean =
        get<HelixPage<HelixFollowed>>("/channels/followed") {
            parameter("user_id", userId)
            parameter("broadcaster_id", broadcasterId)
        }.data.isNotEmpty()

    suspend fun channelInfo(broadcasterIds: List<String>): List<HelixChannelInfo> =
        get<HelixPage<HelixChannelInfo>>("/channels") { broadcasterIds.forEach { parameter("broadcaster_id", it) } }.data

    suspend fun videos(userId: String? = null, ids: List<String> = emptyList(), type: String = "all", cursor: String? = null): HelixPage<HelixVideo> =
        get("/videos") {
            userId?.let { parameter("user_id", it) }
            ids.forEach { parameter("id", it) }
            if (ids.isEmpty()) {
                parameter("type", type)
                parameter("first", 40)
            }
            cursor?.let { parameter("after", it) }
        }

    suspend fun clips(
        broadcasterId: String? = null,
        gameId: String? = null,
        ids: List<String> = emptyList(),
        startedAt: String? = null,
        cursor: String? = null,
    ): HelixPage<HelixClip> = get("/clips") {
        broadcasterId?.let { parameter("broadcaster_id", it) }
        gameId?.let { parameter("game_id", it) }
        ids.forEach { parameter("id", it) }
        startedAt?.let { parameter("started_at", it) }
        if (ids.isEmpty()) parameter("first", 40)
        cursor?.let { parameter("after", it) }
    }

    suspend fun createClip(broadcasterId: String): String? =
        post<HelixPage<RawJson>>("/clips") { parameter("broadcaster_id", broadcasterId) }
            .data.firstOrNull()?.get("id")?.toString()?.trim('"')

    suspend fun schedule(broadcasterId: String): HelixSchedule = getOrNull("/schedule") {
        parameter("broadcaster_id", broadcasterId)
        parameter("first", 25)
    } ?: HelixSchedule()

    suspend fun globalBadges(): List<HelixBadgeSet> = get<HelixPage<HelixBadgeSet>>("/chat/badges/global").data

    suspend fun channelBadges(broadcasterId: String): List<HelixBadgeSet> =
        get<HelixPage<HelixBadgeSet>>("/chat/badges") { parameter("broadcaster_id", broadcasterId) }.data

    suspend fun globalEmotes(): HelixEmotePage = get("/chat/emotes/global")

    suspend fun channelEmotes(broadcasterId: String): HelixEmotePage =
        get("/chat/emotes") { parameter("broadcaster_id", broadcasterId) }

    suspend fun userEmotes(userId: String, broadcasterId: String?, cursor: String? = null): HelixEmotePage =
        get("/chat/emotes/user") {
            parameter("user_id", userId)
            broadcasterId?.let { parameter("broadcaster_id", it) }
            cursor?.let { parameter("after", it) }
        }

    suspend fun emoteSets(ids: List<String>): HelixEmotePage =
        get("/chat/emotes/set") { ids.take(25).forEach { parameter("emote_set_id", it) } }

    suspend fun chatSettings(broadcasterId: String): HelixChatSettings? =
        get<HelixPage<HelixChatSettings>>("/chat/settings") { parameter("broadcaster_id", broadcasterId) }.data.firstOrNull()

    suspend fun updateChatSettings(broadcasterId: String, moderatorId: String, patch: Map<String, Any?>) {
        call<RawJson>("PATCH", "/chat/settings", body = buildJsonObject {
            patch.forEach { (k, v) ->
                when (v) {
                    is Boolean -> put(k, v)
                    is Int -> put(k, v)
                    null -> put(k, null as String?)
                    else -> put(k, v.toString())
                }
            }
        }.toString()) {
            parameter("broadcaster_id", broadcasterId)
            parameter("moderator_id", moderatorId)
        }
    }

    suspend fun sendMessage(broadcasterId: String, senderId: String, message: String, replyTo: String? = null): HelixSendResult {
        val body = buildJsonObject {
            put("broadcaster_id", broadcasterId)
            put("sender_id", senderId)
            put("message", message)
            replyTo?.let { put("reply_parent_message_id", it) }
        }.toString()
        return call<HelixPage<HelixSendResult>>("POST", "/chat/messages", body = body).data.firstOrNull() ?: HelixSendResult()
    }

    suspend fun announce(broadcasterId: String, moderatorId: String, message: String, color: String = "primary") {
        callUnit("POST", "/chat/announcements", buildJsonObject {
            put("message", message)
            put("color", color)
        }.toString()) {
            parameter("broadcaster_id", broadcasterId)
            parameter("moderator_id", moderatorId)
        }
    }

    suspend fun ban(broadcasterId: String, moderatorId: String, userId: String, durationSeconds: Int?, reason: String?) {
        val body = json.encodeToString(HelixBanRequest.serializer(), HelixBanRequest(HelixBanBody(userId, durationSeconds, reason)))
        callUnit("POST", "/moderation/bans", body) {
            parameter("broadcaster_id", broadcasterId)
            parameter("moderator_id", moderatorId)
        }
    }

    suspend fun unban(broadcasterId: String, moderatorId: String, userId: String) = callUnit("DELETE", "/moderation/bans") {
        parameter("broadcaster_id", broadcasterId)
        parameter("moderator_id", moderatorId)
        parameter("user_id", userId)
    }

    suspend fun deleteMessage(broadcasterId: String, moderatorId: String, messageId: String?) = callUnit("DELETE", "/moderation/chat") {
        parameter("broadcaster_id", broadcasterId)
        parameter("moderator_id", moderatorId)
        messageId?.let { parameter("message_id", it) }
    }

    suspend fun shoutout(fromId: String, toId: String, moderatorId: String) = callUnit("POST", "/chat/shoutouts") {
        parameter("from_broadcaster_id", fromId)
        parameter("to_broadcaster_id", toId)
        parameter("moderator_id", moderatorId)
    }

    suspend fun chatters(broadcasterId: String, moderatorId: String, cursor: String? = null): HelixPage<HelixChatter> =
        get("/chat/chatters") {
            parameter("broadcaster_id", broadcasterId)
            parameter("moderator_id", moderatorId)
            parameter("first", 1000)
            cursor?.let { parameter("after", it) }
        }

    // region Stream manager (own channel)

    /** Only fields passed are changed. Tags replace the whole list. */
    suspend fun updateChannel(broadcasterId: String, title: String? = null, gameId: String? = null, tags: List<String>? = null, language: String? = null) {
        val body = buildJsonObject {
            title?.let { put("title", it) }
            gameId?.let { put("game_id", it) }
            language?.let { put("broadcaster_language", it) }
            tags?.let { t -> put("tags", kotlinx.serialization.json.JsonArray(t.map { kotlinx.serialization.json.JsonPrimitive(it) })) }
        }.toString()
        callUnit("PATCH", "/channels", body = body) { parameter("broadcaster_id", broadcasterId) }
    }

    suspend fun createMarker(userId: String, description: String?): HelixMarker? {
        val body = buildJsonObject {
            put("user_id", userId)
            description?.takeIf { it.isNotBlank() }?.let { put("description", it.take(140)) }
        }.toString()
        return call<HelixPage<HelixMarker>>("POST", "/streams/markers", body = body).data.firstOrNull()
    }

    suspend fun startCommercial(broadcasterId: String, lengthSeconds: Int): HelixCommercial? {
        val body = buildJsonObject {
            put("broadcaster_id", broadcasterId)
            put("length", lengthSeconds.coerceIn(30, 180))
        }.toString()
        return call<HelixPage<HelixCommercial>>("POST", "/channels/commercial", body = body).data.firstOrNull()
    }

    suspend fun adSchedule(broadcasterId: String): HelixAdSchedule? =
        HelixAdSchedule.parse(get<HelixPage<RawJson>>("/channels/ads") { parameter("broadcaster_id", broadcasterId) }.data.firstOrNull())

    suspend fun snoozeNextAd(broadcasterId: String): HelixAdSchedule? =
        HelixAdSchedule.parse(call<HelixPage<RawJson>>("POST", "/channels/ads/schedule/snooze") { parameter("broadcaster_id", broadcasterId) }.data.firstOrNull())

    /** [allow] true lets a held message through, false denies it. */
    suspend fun resolveAutoMod(moderatorId: String, messageId: String, allow: Boolean) {
        val body = buildJsonObject {
            put("user_id", moderatorId)
            put("msg_id", messageId)
            put("action", if (allow) "ALLOW" else "DENY")
        }.toString()
        callUnit("POST", "/moderation/automod/message", body = body)
    }

    suspend fun unbanRequests(broadcasterId: String, moderatorId: String): List<HelixUnbanRequest> =
        get<HelixPage<HelixUnbanRequest>>("/moderation/unban_requests") {
            parameter("broadcaster_id", broadcasterId)
            parameter("moderator_id", moderatorId)
            parameter("status", "pending")
            parameter("first", 100)
        }.data

    suspend fun resolveUnbanRequest(broadcasterId: String, moderatorId: String, requestId: String, approve: Boolean, note: String? = null) =
        callUnit("PATCH", "/moderation/unban_requests") {
            parameter("broadcaster_id", broadcasterId)
            parameter("moderator_id", moderatorId)
            parameter("unban_request_id", requestId)
            parameter("status", if (approve) "approved" else "denied")
            note?.takeIf { it.isNotBlank() }?.let { parameter("resolution_text", it.take(500)) }
        }

    // endregion

    /** Channels [userId] moderates (needs user:read:moderated_channels). Empty if the scope is missing. */
    suspend fun moderatedChannels(userId: String): List<String> {
        val out = mutableListOf<String>()
        var cursor: String? = null
        repeat(10) { // 1000 channels is plenty
            val page = getOrNull<HelixPage<RawJson>>("/moderation/channels") {
                parameter("user_id", userId)
                parameter("first", 100)
                cursor?.let { parameter("after", it) }
            } ?: return out
            page.data.mapNotNullTo(out) { it["broadcaster_id"]?.toString()?.trim('"') }
            cursor = page.pagination?.cursor ?: return out
        }
        return out
    }

    suspend fun userColor(userIds: List<String>): List<HelixUserColor> =
        get<HelixPage<HelixUserColor>>("/chat/color") { userIds.take(100).forEach { parameter("user_id", it) } }.data

    suspend fun setChatColor(userId: String, color: String) = callUnit("PUT", "/chat/color") {
        parameter("user_id", userId)
        parameter("color", color)
    }

    suspend fun blockedUsers(userId: String): List<String> =
        get<HelixPage<RawJson>>("/users/blocks") { parameter("broadcaster_id", userId); parameter("first", 100) }
            .data.mapNotNull { it["user_login"]?.toString()?.trim('"') }

    suspend fun block(targetUserId: String) = callUnit("PUT", "/users/blocks") { parameter("target_user_id", targetUserId) }
    suspend fun unblock(targetUserId: String) = callUnit("DELETE", "/users/blocks") { parameter("target_user_id", targetUserId) }

    suspend fun subscription(broadcasterId: String, userId: String): HelixSubscription? =
        getOrNull<HelixPage<HelixSubscription>>("/subscriptions/user") {
            parameter("broadcaster_id", broadcasterId)
            parameter("user_id", userId)
        }?.data?.firstOrNull()

    suspend fun createEventSub(request: EventSubCreate): EventSubCreated? =
        call<HelixPage<EventSubCreated>>("POST", "/eventsub/subscriptions", body = json.encodeToString(EventSubCreate.serializer(), request))
            .data.firstOrNull()

    suspend fun deleteEventSub(id: String) = callUnit("DELETE", "/eventsub/subscriptions") { parameter("id", id) }

    // region plumbing

    private suspend inline fun <reified T> get(path: String, noinline block: HttpRequestBuilder.() -> Unit = {}): T =
        call("GET", path, block = block)

    private suspend inline fun <reified T> post(path: String, noinline block: HttpRequestBuilder.() -> Unit = {}): T =
        call("POST", path, block = block)

    private suspend inline fun <reified T> getOrNull(path: String, noinline block: HttpRequestBuilder.() -> Unit = {}): T? =
        try {
            call<T>("GET", path, block = block)
        } catch (e: AppException) {
            if (e.kind == AppException.Kind.NOT_FOUND || e.kind == AppException.Kind.AUTH) null else throw e
        }

    private suspend fun callUnit(method: String, path: String, body: String? = null, block: HttpRequestBuilder.() -> Unit = {}) {
        raw(method, path, body, block)
    }

    private suspend inline fun <reified T> call(
        method: String,
        path: String,
        body: String? = null,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val text = raw(method, path, body, block)
        return json.decodeFromString(text.ifBlank { "{}" })
    }

    private suspend fun raw(method: String, path: String, body: String?, block: HttpRequestBuilder.() -> Unit): String {
        var token = tokens.accessToken()
        var res = send(method, path, body, token, block)
        if (res.status == HttpStatusCode.Unauthorized && token != null) {
            token = tokens.refresh()
            if (token != null) res = send(method, path, body, token, block)
        }
        val text = res.bodyAsText()
        if (!res.status.isSuccess()) {
            val kind = when (res.status) {
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> AppException.Kind.AUTH
                HttpStatusCode.NotFound -> AppException.Kind.NOT_FOUND
                HttpStatusCode.TooManyRequests -> AppException.Kind.RATE_LIMITED
                else -> AppException.Kind.NETWORK
            }
            throw AppException(kind, "Helix ${res.status.value} $path: ${text.take(200)}")
        }
        return text
    }

    private suspend fun send(method: String, path: String, body: String?, token: String?, block: HttpRequestBuilder.() -> Unit): HttpResponse {
        val url = base + path
        val setup: HttpRequestBuilder.() -> Unit = {
            expectSuccess = false
            header("Client-Id", BuildConfig.TWITCH_CLIENT_ID)
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            block()
        }
        return when (method) {
            "GET" -> http.get(url, setup)
            "POST" -> http.post(url, setup)
            "PUT" -> http.put(url, setup)
            "PATCH" -> http.patch(url, setup)
            "DELETE" -> http.delete(url, setup)
            else -> error("Unsupported method $method")
        }
    }

    // endregion
}
