package lol.sinist3r.yip.core.data.twitch

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import lol.sinist3r.yip.core.common.attempt
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import lol.sinist3r.yip.core.common.AppException
import lol.sinist3r.yip.core.data.account.AccountRepository
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.model.Category
import lol.sinist3r.yip.core.model.Channel
import lol.sinist3r.yip.core.model.Clip
import lol.sinist3r.yip.core.model.FollowedChannel
import lol.sinist3r.yip.core.model.LiveStream
import lol.sinist3r.yip.core.model.Page
import lol.sinist3r.yip.core.model.ScheduleSegment
import lol.sinist3r.yip.core.model.User
import lol.sinist3r.yip.core.model.Video
import lol.sinist3r.yip.core.model.VideoChapter
import lol.sinist3r.yip.core.model.VideoType
import lol.sinist3r.yip.core.network.twitch.GqlApi
import lol.sinist3r.yip.core.network.twitch.HelixApi
import lol.sinist3r.yip.core.network.twitch.HelixClip
import lol.sinist3r.yip.core.network.twitch.HelixGame
import lol.sinist3r.yip.core.network.twitch.HelixStream
import lol.sinist3r.yip.core.network.twitch.HelixUser
import lol.sinist3r.yip.core.network.twitch.HelixVideo
import lol.sinist3r.yip.core.network.twitch.arr
import lol.sinist3r.yip.core.network.twitch.bool
import lol.sinist3r.yip.core.network.twitch.edges
import lol.sinist3r.yip.core.network.twitch.int
import lol.sinist3r.yip.core.network.twitch.long
import lol.sinist3r.yip.core.network.twitch.obj
import lol.sinist3r.yip.core.network.twitch.path
import lol.sinist3r.yip.core.network.twitch.str

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
    private val liveSnapshot: lol.sinist3r.yip.core.data.live.LiveSnapshot,
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

    suspend fun channelEvents(login: String): lol.sinist3r.yip.core.model.ChannelEvents = gql.channelEvents(login)

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
                "profileImageURL" to (u["profileImageURL"] ?: kotlinx.serialization.json.JsonNull),
                "broadcastSettings" to (s.obj("broadcaster")?.obj("broadcastSettings") ?: JsonObject(emptyMap())),
            )))))
        }
        return ChannelInfo(user, stream)
    }

    /** Batch live status for notifications and lists. */
    suspend fun liveByIds(ids: List<String>): List<LiveStream> =
        if (signedInUserId() != null) {
            ids.chunked(100).flatMap { helix.streams(userIds = it, first = 100).data.map { s -> s.toModel() } }
        } else {
            ids.chunked(50).flatMap { chunk ->
                gql.usersStream(ids = chunk).arr("users").mapNotNull { (it as? JsonObject)?.let { u ->
                    val s = u.obj("stream") ?: return@let null
                    gqlStream(JsonObject(s + ("broadcaster" to JsonObject(mapOf(
                        "id" to u["id"]!!, "login" to u["login"]!!, "displayName" to u["displayName"]!!,
                        "profileImageURL" to (u["profileImageURL"] ?: kotlinx.serialization.json.JsonNull),
                        "broadcastSettings" to (s.obj("broadcaster")?.obj("broadcastSettings") ?: JsonObject(emptyMap())),
                    )))))
                } }
            }
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

    suspend fun requireSignedIn(): String = signedInUserId() ?: throw AppException(AppException.Kind.AUTH, "Sign in required")

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
