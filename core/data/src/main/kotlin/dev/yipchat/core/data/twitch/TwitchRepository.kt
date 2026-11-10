package dev.yipchat.core.data.twitch

import dev.yipchat.core.common.AppException
import dev.yipchat.core.common.attempt
import dev.yipchat.core.data.account.AccountRepository
import dev.yipchat.core.data.live.LiveSnapshot
import dev.yipchat.core.data.settings.SettingsRepository
import dev.yipchat.core.model.Category
import dev.yipchat.core.model.Channel
import dev.yipchat.core.model.ChannelEvents
import dev.yipchat.core.model.Clip
import dev.yipchat.core.model.FollowedChannel
import dev.yipchat.core.model.LiveStream
import dev.yipchat.core.model.MutedSegment
import dev.yipchat.core.model.Page
import dev.yipchat.core.model.PinnedMessage
import dev.yipchat.core.model.ScheduleSegment
import dev.yipchat.core.model.Storyboard
import dev.yipchat.core.model.User
import dev.yipchat.core.model.Video
import dev.yipchat.core.model.VideoChapter
import dev.yipchat.core.model.VideoExtras
import dev.yipchat.core.model.VideoType
import dev.yipchat.core.network.twitch.GqlApi
import dev.yipchat.core.network.twitch.HelixApi
import dev.yipchat.core.network.twitch.HelixClip
import dev.yipchat.core.network.twitch.HelixGame
import dev.yipchat.core.network.twitch.HelixStream
import dev.yipchat.core.network.twitch.HelixUser
import dev.yipchat.core.network.twitch.HelixVideo
import dev.yipchat.core.network.twitch.arr
import dev.yipchat.core.network.twitch.bool
import dev.yipchat.core.network.twitch.edges
import dev.yipchat.core.network.twitch.int
import dev.yipchat.core.network.twitch.long
import dev.yipchat.core.network.twitch.obj
import dev.yipchat.core.network.twitch.path
import dev.yipchat.core.network.twitch.str
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber

/**
 * Browse data. Signed in: Helix (official). Signed out: GQL raw queries (Helix refuses
 * tokenless calls, and an app token would need a secret in the APK).
 */
@Singleton
class TwitchRepository @Inject constructor(
    private val helix: HelixApi,
    private val gql: GqlApi,
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
    private val liveSnapshot: LiveSnapshot,
) {
    private suspend fun signedInUserId(): String? = accounts.userId()
    private suspend fun languages() = settings.current().general.streamLanguages

    suspend fun followedLive(): List<LiveStream> {
        val uid = signedInUserId() ?: return emptyList<LiveStream>().also { liveSnapshot.signedOut() }
        val out = mutableListOf<LiveStream>()
        var cursor: String? = null
        do {
            val page = helix.followedStreams(uid, cursor)
            out += page.data.map { it.toModel() }
            cursor = page.pagination?.cursor
        } while (cursor != null && out.size < 500)
        return withAvatars(out).also(liveSnapshot::save)
    }

    suspend fun followedChannels(): List<FollowedChannel> {
        val uid = signedInUserId() ?: return emptyList()
        val out = mutableListOf<FollowedChannel>()
        var cursor: String? = null
        do {
            val page = helix.followedChannels(uid, cursor)
            out += page.data.map { FollowedChannel(it.broadcasterId, it.broadcasterLogin, it.broadcasterName, it.followedAt?.let(Instant::parse)) }
            cursor = page.pagination?.cursor
        } while (cursor != null && out.size < 2000)
        val avatars = out.chunked(100).flatMap { chunk -> helix.users(ids = chunk.map { it.id }) }.associate { it.id to it.profileImageUrl }
        return out.map { it.copy(profileImageUrl = avatars[it.id]) }
    }

    suspend fun topStreams(cursor: String?): Page<LiveStream> {
        if (signedInUserId() != null) {
            val page = helix.streams(cursor = cursor, languages = languages())
            return Page(withAvatars(page.data.map { it.toModel() }), page.pagination?.cursor)
        }
        val data = gql.topStreams(languages(), 30, cursor)
        return gqlStreamPage(data.edges("streams"), data.path("streams", "pageInfo"))
    }

    suspend fun categoryStreams(categoryId: String, cursor: String?): Page<LiveStream> {
        if (signedInUserId() != null) {
            val page = helix.streams(cursor = cursor, gameId = categoryId, languages = languages())
            return Page(withAvatars(page.data.map { it.toModel() }), page.pagination?.cursor)
        }
        val data = gql.gameStreams(categoryId, languages(), 30, cursor)
        return gqlStreamPage(data.edges("game", "streams"), data.path("game", "streams", "pageInfo"))
    }

    suspend fun topCategories(cursor: String?): Page<Category> {
        if (signedInUserId() != null) {
            val page = helix.topGames(cursor)
            return Page(page.data.map { it.toModel() }, page.pagination?.cursor)
        }
        val data = gql.topGames(60, cursor)
        val edges = data.edges("games")
        return Page(edges.mapNotNull { it.obj("node")?.toCategory() }, nextCursor(edges, data.path("games", "pageInfo")))
    }

    suspend fun category(id: String): Category? =
        if (signedInUserId() != null) helix.games(ids = listOf(id)).firstOrNull()?.toModel() else null

    /**
     * Twitch ranks channel search by popularity, so an exact name can land far down the list.
     * The first page pins the exact login (looked up directly) and sorts by how well names match.
     */
    suspend fun searchChannels(query: String, cursor: String?): Page<Channel> = coroutineScope {
        val q = query.trim()
        val exact = if (cursor == null && LOGIN.matches(q)) async { attempt { channel(login = q.lowercase()) }.getOrNull() } else null
        val page = rawSearchChannels(q, cursor)
        if (cursor != null) return@coroutineScope page
        val pinned = exact?.await()?.let { ci ->
            Channel(ci.user.id, ci.user.login, ci.user.displayName, ci.stream?.gameName, ci.stream?.title, ci.stream != null, ci.user.profileImageUrl)
        }
        page.copy(items = rankChannels(q, page.items, pinned))
    }

    private suspend fun rawSearchChannels(query: String, cursor: String?): Page<Channel> {
        if (signedInUserId() != null) {
            val page = helix.searchChannels(query, cursor = cursor)
            return Page(
                page.data.map { Channel(it.id, it.broadcasterLogin, it.displayName, it.gameName, it.title, it.isLive, it.thumbnailUrl) },
                page.pagination?.cursor,
            )
        }
        val data = gql.searchChannels(query, 30, cursor)
        val edges = data.edges("searchUsers")
        return Page(
            edges.mapNotNull { e ->
                val n = e.obj("node") ?: return@mapNotNull null
                val stream = n.obj("stream")
                Channel(
                    id = n.str("id") ?: return@mapNotNull null,
                    login = n.str("login").orEmpty(),
                    displayName = n.str("displayName").orEmpty(),
                    gameName = stream?.obj("game")?.str("displayName"),
                    title = stream?.path("broadcaster", "broadcastSettings")?.str("title"),
                    isLive = stream != null,
                    profileImageUrl = n.str("profileImageURL"),
                )
            },
            nextCursor(edges, data.path("searchUsers", "pageInfo")),
        )
    }

    suspend fun searchCategories(query: String, cursor: String?): Page<Category> {
        if (signedInUserId() != null) {
            val page = helix.searchCategories(query, cursor)
            return Page(page.data.map { it.toModel() }, page.pagination?.cursor)
        }
        val data = gql.searchGames(query, 30, cursor)
        val edges = data.edges("searchCategories")
        return Page(edges.mapNotNull { it.obj("node")?.toCategory() }, nextCursor(edges, data.path("searchCategories", "pageInfo")))
    }

    data class ChannelInfo(val user: User, val stream: LiveStream?)

    suspend fun lastLive(ids: List<String>): Map<String, java.time.Instant?> = ids.chunked(100).fold(emptyMap()) { acc, c -> acc + (attempt { gql.lastBroadcasts(c) }.getOrNull().orEmpty()) }

    /** True/false once Twitch answers; null if it couldn't be checked (signed out, offline). */
    suspend fun vodExists(id: String): Boolean? {
        if (signedInUserId() == null) return null
        return try {
            helix.videos(ids = listOf(id)).data.isNotEmpty()
        } catch (e: AppException) {
            if (e.kind == AppException.Kind.NOT_FOUND) false else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun channelEvents(login: String): ChannelEvents {
        // Signed-in adds your own prediction; fall back to the public view if that fails.
        if (accounts.webToken() != null) attempt { gql.channelEvents(login, authed = true) }.getOrNull()?.let { return it }
        return gql.channelEvents(login)
    }

    suspend fun channel(login: String? = null, id: String? = null): ChannelInfo? {
        if (signedInUserId() != null) {
            val user = helix.users(ids = listOfNotNull(id), logins = listOfNotNull(login)).firstOrNull() ?: return null
            val stream = helix.streams(userIds = listOf(user.id), first = 1).data.firstOrNull()?.toModel()
            return ChannelInfo(user.toModel(), stream?.copy(profileImageUrl = user.profileImageUrl))
        }
        val data = gql.usersStream(ids = listOfNotNull(id), logins = listOfNotNull(login))
        val u = data.arr("users").firstOrNull() as? JsonObject ?: return null
        val user = User(
            id = u.str("id") ?: return null,
            login = u.str("login").orEmpty(),
            displayName = u.str("displayName").orEmpty(),
            profileImageUrl = u.str("profileImageURL"),
            description = u.str("description"),
            broadcasterType = when {
                u.obj("roles")?.bool("isPartner") == true -> "partner"
                u.obj("roles")?.bool("isAffiliate") == true -> "affiliate"
                else -> null
            },
            createdAt = u.str("createdAt")?.let(::parseInstant),
        )
        val stream = u.obj("stream")?.let { s ->
            gqlStream(JsonObject(s + ("broadcaster" to JsonObject(mapOf(
                "id" to (u["id"] ?: return@let null),
                "login" to (u["login"] ?: return@let null),
                "displayName" to (u["displayName"] ?: return@let null),
                "profileImageURL" to (u["profileImageURL"] ?: JsonNull),
                "broadcastSettings" to (s.obj("broadcaster")?.obj("broadcastSettings") ?: JsonObject(emptyMap())),
            )))))
        }
        return ChannelInfo(user, stream)
    }

    suspend fun videos(channelId: String, channelLogin: String, type: VideoType?, cursor: String?): Page<Video> {
        if (signedInUserId() != null) {
            val page = helix.videos(userId = channelId, type = type?.name?.lowercase() ?: "all", cursor = cursor)
            return Page(page.data.map { it.toModel() }, page.pagination?.cursor)
        }
        val types = type?.let { listOf(it.name) }
        val data = gql.userVideos(channelLogin, types, 30, cursor)
        val user = data.obj("user")
        val edges = data.edges("user", "videos")
        return Page(
            edges.mapNotNull { e ->
                val n = e.obj("node") ?: return@mapNotNull null
                Video(
                    id = n.str("id") ?: return@mapNotNull null,
                    userId = user?.str("id").orEmpty(),
                    userLogin = user?.str("login").orEmpty(),
                    userName = user?.str("displayName").orEmpty(),
                    title = n.str("title").orEmpty(),
                    createdAt = n.str("createdAt")?.let(::parseInstant) ?: Clock.System.now(),
                    durationSeconds = n.long("lengthSeconds") ?: 0,
                    viewCount = n.long("viewCount") ?: 0,
                    thumbnailTemplate = n.str("previewThumbnailURL").orEmpty(),
                    type = when (n.str("broadcastType")) { "HIGHLIGHT" -> VideoType.HIGHLIGHT; "UPLOAD" -> VideoType.UPLOAD; else -> VideoType.ARCHIVE },
                )
            },
            nextCursor(edges, data.path("user", "videos", "pageInfo")),
        )
    }

    suspend fun video(id: String): Video? {
        if (signedInUserId() != null) return helix.videos(ids = listOf(id)).data.firstOrNull()?.toModel()
        val v = gql.videoInfo(id).obj("video") ?: return null
        val owner = v.obj("owner")
        return Video(
            id = id,
            userId = owner?.str("id").orEmpty(),
            userLogin = owner?.str("login").orEmpty(),
            userName = owner?.str("displayName").orEmpty(),
            title = v.str("title").orEmpty(),
            createdAt = v.str("createdAt")?.let(::parseInstant) ?: Clock.System.now(),
            durationSeconds = v.long("lengthSeconds") ?: 0,
            viewCount = v.long("viewCount") ?: 0,
            thumbnailTemplate = v.str("previewThumbnailURL").orEmpty(),
            type = VideoType.ARCHIVE,
        )
    }

    enum class ClipPeriod(val helixDays: Long?, val gql: String) { DAY(1, "LAST_DAY"), WEEK(7, "LAST_WEEK"), MONTH(30, "LAST_MONTH"), ALL(null, "ALL_TIME") }

    suspend fun clips(channelId: String, channelLogin: String, period: ClipPeriod, cursor: String?): Page<Clip> {
        if (signedInUserId() != null) {
            val started = period.helixDays?.let { (Clock.System.now() - kotlin.time.Duration.parse("${it}d")).toString() }
            val page = helix.clips(broadcasterId = channelId, startedAt = started, cursor = cursor)
            return Page(page.data.map { it.toModel() }, page.pagination?.cursor)
        }
        val data = gql.userClips(channelLogin, period.gql, 30, cursor)
        val user = data.obj("user")
        val edges = data.edges("user", "clips")
        return Page(
            edges.mapNotNull { e ->
                val n = e.obj("node") ?: return@mapNotNull null
                Clip(
                    id = n.str("slug") ?: return@mapNotNull null,
                    url = n.str("url").orEmpty(),
                    broadcasterId = user?.str("id").orEmpty(),
                    broadcasterName = user?.str("displayName").orEmpty(),
                    creatorName = n.obj("curator")?.str("displayName").orEmpty(),
                    title = n.str("title").orEmpty(),
                    viewCount = n.long("viewCount") ?: 0,
                    createdAt = n.str("createdAt")?.let(::parseInstant) ?: Clock.System.now(),
                    thumbnailUrl = n.str("thumbnailURL").orEmpty(),
                    durationSeconds = n.str("durationSeconds")?.toDoubleOrNull() ?: 0.0,
                    videoId = n.obj("video")?.str("id"),
                    vodOffset = n.int("videoOffsetSeconds"),
                )
            },
            nextCursor(edges, data.path("user", "clips", "pageInfo")),
        )
    }

    /** Scrub thumbnails and muted parts for a VOD. */
    suspend fun videoExtras(videoId: String): VideoExtras {
        val v = attempt { gql.videoExtras(videoId).obj("video") }.getOrNull() ?: return VideoExtras()
        val muted = v.obj("muteInfo")?.obj("mutedSegmentConnection")?.arr("nodes").orEmpty().mapNotNull { n ->
            val o = n as? JsonObject ?: return@mapNotNull null
            MutedSegment(o.int("offset") ?: return@mapNotNull null, o.int("duration") ?: return@mapNotNull null)
        }.sortedBy { it.offsetSeconds }
        val storyboard = v.str("seekPreviewsURL")?.let { url -> storyboard(url) }
        val liveLogin = v.obj("owner")?.str("login")?.takeIf { v.str("status") == "RECORDING" }
        return VideoExtras(storyboard, muted, liveLogin)
    }

    private suspend fun storyboard(infoUrl: String): Storyboard? {
        val text = gql.getText(infoUrl) ?: return null
        val levels = runCatching { Json.parseToJsonElement(text) as JsonArray }.getOrNull() ?: return null
        // "low" is one small sprite; "high" is sharper but several images.
        val level = levels.mapNotNull { it as? JsonObject }.let { l -> l.firstOrNull { it.str("quality") == "low" } ?: l.firstOrNull() } ?: return null
        val base = infoUrl.substringBeforeLast('/') + "/"
        return Storyboard(
            images = level.arr("images").mapNotNull { (it as? JsonPrimitive)?.content?.let { name -> base + name } },
            count = level.int("count") ?: 0,
            intervalSeconds = level.int("interval") ?: 0,
            cols = level.int("cols") ?: 0,
            rows = level.int("rows") ?: 0,
            width = level.int("width") ?: 0,
            height = level.int("height") ?: 0,
        )
    }

    /** The VOD being recorded of a live stream, with when the stream started. Null if VODs are off. */
    suspend fun liveArchive(login: String): Pair<String, kotlin.time.Instant?>? {
        val st = gql.liveArchive(login).obj("user")?.obj("stream") ?: return null
        val vod = st.obj("archiveVideo")?.str("id") ?: return null
        return vod to st.str("createdAt")?.let(::parseInstant)
    }

    suspend fun pinnedMessage(channelLogin: String): PinnedMessage? {
        val n = gql.pinnedMessage(channelLogin).edges("channel", "pinnedChatMessages").firstOrNull()?.obj("node") ?: return null
        val m = n.obj("pinnedMessage") ?: return null
        val sender = m.obj("sender")
        return PinnedMessage(
            id = n.str("id") ?: return null,
            messageId = m.str("id").orEmpty(),
            text = m.obj("content")?.str("text").orEmpty(),
            senderLogin = sender?.str("login").orEmpty(),
            senderName = sender?.str("displayName") ?: sender?.str("login").orEmpty(),
            senderColor = sender?.str("chatColor"),
            pinnedBy = n.obj("pinnedBy")?.str("displayName"),
            endsAt = n.str("endsAt")?.let(::parseInstant),
        )
    }

    /** Pins for everyone; needs mod and the twitch.tv connection. [durationSeconds] null keeps it until unpinned. */
    suspend fun pinMessage(channelId: String, messageId: String, durationSeconds: Int?) {
        val r = withIntegrityRetry { gql.pinMessage(channelId, messageId, durationSeconds, it) }
        r.obj("pinChatMessage")?.obj("error")?.str("code")?.let { throw AppException(AppException.Kind.UNKNOWN, pinError(it)) }
    }

    suspend fun unpinMessage(pinId: String) {
        val r = withIntegrityRetry { gql.unpinMessage(pinId, it) }
        r.obj("unpinChatMessage")?.obj("error")?.str("code")?.let { throw AppException(AppException.Kind.UNKNOWN, pinError(it)) }
    }

    /** Some mutations want an integrity token; try without first since it's slow. */
    private suspend fun withIntegrityRetry(call: suspend (Boolean) -> JsonObject): JsonObject = try {
        call(false)
    } catch (e: AppException) {
        if (e.message?.contains("integrity", ignoreCase = true) == true) call(true) else throw e
    }

    private fun pinError(code: String) = when (code) {
        "FORBIDDEN", "NOT_AUTHORIZED", "UNAUTHORIZED" -> "Only mods can pin messages"
        "MESSAGE_NOT_FOUND", "NOT_FOUND" -> "That message can't be pinned"
        else -> "Couldn't pin (${code.lowercase()})"
    }

    /** [published] false: Twitch kept its own default clip; [note] says what happened. */
    data class VodClip(val slug: String, val published: Boolean, val note: String? = null)

    /**
     * Clips [startSeconds]..[endSeconds] of a VOD with a title. Twitch makes a draft of the moments
     * leading up to [endSeconds]; the last part of it, the length you picked, gets published.
     */
    suspend fun createVodClip(broadcasterId: String, videoId: String, startSeconds: Double, endSeconds: Double, title: String): VodClip {
        val r = withIntegrityRetry { gql.createClip(broadcasterId, endSeconds, videoId, it) }.obj("createClip")
        r?.obj("error")?.str("__typename")?.let { throw AppException(AppException.Kind.UNKNOWN, clipError(it)) }
        val slug = r?.obj("clip")?.str("slug") ?: throw AppException(AppException.Kind.UNKNOWN, "Twitch didn't return a clip")
        val length = (endSeconds - startSeconds).coerceIn(5.0, 60.0)
        var lastError: String? = null
        // The draft takes a few seconds before Twitch accepts edits.
        repeat(8) { attemptNo ->
            delay(if (attemptNo == 0) 2000 else 3000)
            val draft = attempt { gql.clipDraft(slug).obj("clip") }.getOrNull()
            val window = draft?.str("durationSeconds")?.toDoubleOrNull()?.takeIf { it >= length } ?: RAW_CLIP_SECONDS
            val result = attempt { publishClip(slug, title, (window - length).coerceAtLeast(0.0), length) }
            if (result.isSuccess) return VodClip(slug, true)
            lastError = result.exceptionOrNull()?.message
            Timber.w("Clip publish try ${attemptNo + 1} for $slug failed: $lastError (window $window)")
        }
        // Couldn't trim. At least set the title.
        val titled = attempt { withIntegrityRetry { gql.updateClipTitle(slug, title, it) } }.isSuccess
        return VodClip(
            slug, false,
            buildString {
                append("Twitch didn't accept the trim")
                lastError?.let { append(" ($it)") }
                append(if (titled) ". The title is set; the length is Twitch's default." else ". It kept Twitch's default length and title.")
            },
        )
    }

    suspend fun publishClip(slug: String, title: String, startSeconds: Double, lengthSeconds: Double): String {
        val r = withIntegrityRetry { gql.publishClip(slug, title, startSeconds, lengthSeconds, it) }.obj("publishClip")
            ?: throw AppException(AppException.Kind.UNKNOWN, "no answer from Twitch")
        r.obj("error")?.str("__typename")?.let { throw AppException(AppException.Kind.UNKNOWN, clipError(it)) }
        return r.obj("clip")?.str("slug") ?: throw AppException(AppException.Kind.UNKNOWN, "Twitch didn't confirm the clip")
    }

    private fun clipError(type: String) = when {
        "Forbidden" in type || "Unauthorized" in type -> "Clips are off for this channel"
        "Length" in type || "Duration" in type -> "Clips must be 5 to 60 seconds"
        "Title" in type -> "That title isn't allowed"
        else -> "Twitch said no (${type.removeSuffix("Error")})"
    }

    private companion object { const val RAW_CLIP_SECONDS = 90.0 }

    private var clipsCache: Pair<Long, List<Clip>>? = null

    /** Top clips of the last week from channels you follow, most viewed first. */
    suspend fun followsClips(force: Boolean = false): List<Clip> = coroutineScope {
        clipsCache?.takeIf { !force && System.currentTimeMillis() - it.first < 15 * 60_000 }?.let { return@coroutineScope it.second }
        val uid = signedInUserId() ?: return@coroutineScope emptyList()
        val ids = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = helix.followedChannels(uid, cursor)
            ids += page.data.map { it.broadcasterId }
            cursor = page.pagination?.cursor
        } while (cursor != null && ids.size < 300)
        val clips = ids.chunked(50).map { chunk ->
            async {
                attempt { gql.followsClips(chunk, 3) }.getOrNull()?.arr("users").orEmpty().flatMap { u ->
                    val user = u as? JsonObject ?: return@flatMap emptyList()
                    user.edges("clips").mapNotNull { e ->
                        val n = e.obj("node") ?: return@mapNotNull null
                        Clip(
                            id = n.str("slug") ?: return@mapNotNull null,
                            url = n.str("url").orEmpty(),
                            broadcasterId = user.str("id").orEmpty(),
                            broadcasterName = user.str("displayName").orEmpty(),
                            creatorName = n.obj("curator")?.str("displayName").orEmpty(),
                            title = n.str("title").orEmpty(),
                            viewCount = n.long("viewCount") ?: 0,
                            createdAt = n.str("createdAt")?.let(::parseInstant) ?: Clock.System.now(),
                            thumbnailUrl = n.str("thumbnailURL").orEmpty(),
                            durationSeconds = n.str("durationSeconds")?.toDoubleOrNull() ?: 0.0,
                            videoId = n.obj("video")?.str("id"),
                            vodOffset = n.int("videoOffsetSeconds"),
                        )
                    }
                }
            }
        }.flatMap { it.await() }.sortedByDescending { it.viewCount }.take(150)
        clipsCache = System.currentTimeMillis() to clips
        clips
    }

    suspend fun schedule(channelId: String): List<ScheduleSegment> {
        if (signedInUserId() == null) return emptyList()
        return helix.schedule(channelId).data?.segments.orEmpty().map {
            ScheduleSegment(it.id, it.title, Instant.parse(it.startTime), it.endTime?.let(Instant::parse), it.category?.name, it.isRecurring, it.canceledUntil != null)
        }
    }

    suspend fun chapters(videoId: String): List<VideoChapter> = runCatching {
        gql.chapters(videoId).obj("video")?.obj("moments")?.edges()?.mapNotNull { e ->
            val n = e.obj("node") ?: return@mapNotNull null
            VideoChapter(
                title = n.str("description").orEmpty(),
                startSeconds = (n.int("positionMilliseconds") ?: 0) / 1000,
                durationSeconds = (n.int("durationMilliseconds") ?: 0) / 1000,
                gameName = n.obj("details")?.obj("game")?.str("displayName"),
            )
        }.orEmpty()
    }.getOrDefault(emptyList())

    suspend fun isFollowing(channelId: String): Boolean {
        val uid = signedInUserId() ?: return false
        return runCatching { helix.isFollowing(uid, channelId) }.getOrDefault(false)
    }

    // region mapping

    private suspend fun withAvatars(all: List<LiveStream>): List<LiveStream> {
        val streams = if (settings.current().general.hideMatureStreams) all.filterNot { it.isMature } else all
        if (streams.isEmpty()) return streams
        val avatars = runCatching {
            streams.map { it.userId }.distinct().chunked(100).flatMap { helix.users(ids = it) }.associate { it.id to it.profileImageUrl }
        }.getOrDefault(emptyMap())
        return streams.map { it.copy(profileImageUrl = avatars[it.userId]) }
    }

    private fun gqlStreamPage(edges: List<JsonObject>, pageInfo: JsonObject?): Page<LiveStream> =
        Page(edges.mapNotNull { it.obj("node")?.let(::gqlStream) }, nextCursor(edges, pageInfo))

    private fun nextCursor(edges: List<JsonObject>, pageInfo: JsonObject?): String? =
        if (pageInfo?.bool("hasNextPage") == true) edges.lastOrNull()?.str("cursor") else null

    private fun gqlStream(n: JsonObject): LiveStream? {
        val b = n.obj("broadcaster") ?: return null
        val preview = n.str("previewImageURL").orEmpty()
        return LiveStream(
            id = n.str("id") ?: return null,
            userId = b.str("id").orEmpty(),
            userLogin = b.str("login").orEmpty(),
            userName = b.str("displayName").orEmpty(),
            gameId = n.obj("game")?.str("id"),
            gameName = n.obj("game")?.str("displayName"),
            title = b.obj("broadcastSettings")?.str("title").orEmpty(),
            viewerCount = n.int("viewersCount") ?: 0,
            startedAt = n.str("createdAt")?.let(::parseInstant) ?: Clock.System.now(),
            language = null,
            thumbnailTemplate = templatize(preview),
            tags = n.arr("freeformTags").mapNotNull { (it as? JsonObject)?.str("name") },
            profileImageUrl = b.str("profileImageURL"),
            streamType = n.str("type"),
        )
    }

    private fun JsonObject.toCategory(): Category? = Category(
        id = str("id") ?: return null,
        name = str("displayName").orEmpty(),
        boxArtTemplate = templatize(str("boxArtURL").orEmpty()),
        viewerCount = int("viewersCount"),
    )

    /** GQL image URLs come sized ("-320x180.jpg"); turn them back into templates. */
    private fun templatize(url: String): String =
        if ("{width}" in url) url else url.replace(Regex("""-\d+x\d+(\.\w+)$"""), "-{width}x{height}$1")

    private fun parseInstant(s: String): Instant? = runCatching { Instant.parse(s) }.getOrNull()

    private fun HelixStream.toModel() = LiveStream(
        id, userId, userLogin, userName, gameId, gameName, title, viewerCount,
        Instant.parse(startedAt), language, thumbnailUrl, tags.orEmpty(), isMature,
    )

    private fun HelixGame.toModel() = Category(id, name, boxArtUrl)

    private fun HelixUser.toModel() = User(id, login, displayName, profileImageUrl, description, broadcasterType, createdAt?.let(Instant::parse))

    private fun HelixVideo.toModel() = Video(
        id, userId, userLogin, userName, title, Instant.parse(createdAt), parseDuration(duration), viewCount, thumbnailUrl,
        when (type) { "highlight" -> VideoType.HIGHLIGHT; "upload" -> VideoType.UPLOAD; else -> VideoType.ARCHIVE },
    )

    private fun HelixClip.toModel() = Clip(
        id, url, broadcasterId, broadcasterName, creatorName, title, viewCount, Instant.parse(createdAt), thumbnailUrl, duration, videoId?.ifBlank { null }, vodOffset, gameId,
    )

    /** Helix durations look like "3h2m10s". */
    private fun parseDuration(d: String): Long {
        var total = 0L
        Regex("""(\d+)([hms])""").findAll(d).forEach {
            val n = it.groupValues[1].toLong()
            total += when (it.groupValues[2]) { "h" -> n * 3600; "m" -> n * 60; else -> n }
        }
        return total
    }

    // endregion
}

private val LOGIN = Regex("^[A-Za-z0-9_]{1,25}$")

/**
 * Exact match first, then names starting with the query, then names containing it, then the
 * rest. Twitch's order is kept within each group, so live channels still lead ties.
 */
internal fun rankChannels(query: String, items: List<Channel>, exact: Channel?): List<Channel> {
    val q = query.trim().lowercase()
    fun score(c: Channel): Int {
        val login = c.login.lowercase()
        val name = c.displayName.lowercase()
        return when {
            login == q || name == q -> 0
            login.startsWith(q) || name.startsWith(q) -> 1
            q in login || q in name -> 2
            else -> 3
        }
    }
    val all = listOfNotNull(exact) + items
    return all.distinctBy { it.id }.withIndex().sortedWith(compareBy({ score(it.value) }, { it.index })).map { it.value }
}
