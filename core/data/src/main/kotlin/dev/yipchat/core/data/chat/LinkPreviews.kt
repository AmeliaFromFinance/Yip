package dev.yipchat.core.data.chat

import dev.yipchat.core.common.attempt
import dev.yipchat.core.common.compactCount
import dev.yipchat.core.common.secondsClock
import dev.yipchat.core.model.LinkPreview
import dev.yipchat.core.network.twitch.GqlApi
import dev.yipchat.core.network.twitch.long
import dev.yipchat.core.network.twitch.obj
import dev.yipchat.core.network.twitch.str
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Cards for Twitch clips, VODs, channels and YouTube links. Other sites aren't fetched. */
@Singleton
class LinkPreviews @Inject constructor(private val gql: GqlApi) {
    private object Miss
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<String, Any>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>) = size > 200
    })

    fun cached(url: String): LinkPreview? = cache[url] as? LinkPreview

    suspend fun load(url: String): LinkPreview? {
        cache[url]?.let { return it as? LinkPreview }
        val target = parse(url)
        val result = target?.let { attempt { fetch(url, it) }.getOrNull() }
        cache[url] = result ?: Miss
        return result
    }

    sealed interface Target {
        data class ClipLink(val slug: String) : Target
        data class VideoLink(val id: String) : Target
        data class ChannelLink(val login: String) : Target
        data object YouTube : Target
    }

    private suspend fun fetch(url: String, t: Target): LinkPreview? = when (t) {
        is Target.ClipLink -> gql.clipPreview(t.slug).obj("clip")?.let { c ->
            LinkPreview(url, c.str("title").orEmpty(), listOfNotNull(c.obj("broadcaster")?.str("displayName"), "Clip", c.long("viewCount")?.let { "${it.compactCount()} views" }).joinToString(" · "), c.str("thumbnailURL"))
        }
        is Target.VideoLink -> gql.videoPreview(t.id).obj("video")?.let { v ->
            LinkPreview(url, v.str("title").orEmpty(), listOfNotNull(v.obj("owner")?.str("displayName"), v.long("lengthSeconds")?.secondsClock()).joinToString(" · "), v.str("previewThumbnailURL"))
        }
        is Target.ChannelLink -> gql.userPreview(t.login).obj("user")?.let { u ->
            val live = u.obj("stream")
            val sub = if (live != null) listOfNotNull("Live", live.obj("game")?.str("displayName"), live.long("viewersCount")?.let { "${it.compactCount()} watching" }).joinToString(" · ")
                else u.str("description")?.take(90)
            LinkPreview(url, u.str("displayName") ?: t.login, sub, u.str("profileImageURL"))
        }
        Target.YouTube -> gql.getText("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8"))?.let { body ->
            val o = Json.parseToJsonElement(body) as? JsonObject ?: return null
            LinkPreview(url, o.str("title").orEmpty(), listOfNotNull(o.str("author_name"), "YouTube").joinToString(" · "), o.str("thumbnail_url"))
        }
    }?.takeIf { it.title.isNotBlank() }

    companion object {
        private val SLUG = Regex("^[A-Za-z0-9_-]{4,}$")
        private val LOGIN = Regex("^[A-Za-z0-9_]{3,25}$")
        private val RESERVED = setOf("videos", "directory", "settings", "downloads", "p", "search", "subscriptions", "inventory", "wallet", "drops", "turbo", "jobs", "store")

        fun parse(url: String): Target? {
            val u = runCatching { URI(if ("://" in url) url else "https://$url") }.getOrNull() ?: return null
            val host = u.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
            val seg = u.path.orEmpty().split('/').filter { it.isNotEmpty() }
            val query = u.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            return when (host) {
                "clips.twitch.tv" -> seg.firstOrNull()?.takeIf { SLUG.matches(it) }?.let(Target::ClipLink)
                "twitch.tv" -> when {
                    seg.size >= 3 && seg[1] == "clip" -> seg[2].takeIf { SLUG.matches(it) }?.let(Target::ClipLink)
                    seg.size >= 2 && seg[0] == "videos" -> seg[1].takeIf { it.all(Char::isDigit) }?.let(Target::VideoLink)
                    seg.size == 1 && LOGIN.matches(seg[0]) && seg[0].lowercase() !in RESERVED -> Target.ChannelLink(seg[0].lowercase())
                    else -> null
                }
                "youtube.com", "music.youtube.com" -> if ((seg.firstOrNull() == "watch" && !query["v"].isNullOrEmpty()) || seg.firstOrNull() == "shorts" || seg.firstOrNull() == "live") Target.YouTube else null
                "youtu.be" -> if (seg.isNotEmpty()) Target.YouTube else null
                else -> null
            }
        }
    }
}
