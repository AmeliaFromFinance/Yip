package lol.sinist3r.yip.core.data.playback

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import lol.sinist3r.yip.core.common.AppException
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.model.AdBlockSettings
import lol.sinist3r.yip.core.model.AdMethod
import lol.sinist3r.yip.core.model.ResolvedPlayback
import lol.sinist3r.yip.core.model.StreamQuality
import lol.sinist3r.yip.core.model.VideoCodec
import lol.sinist3r.yip.core.network.config.RemoteConfigSource
import lol.sinist3r.yip.core.network.twitch.GqlApi
import lol.sinist3r.yip.core.network.twitch.Usher
import timber.log.Timber

/**
 * Turns a channel / VOD / clip into playable URLs. Ad handling is strictly opt-in
 * ([AdBlockSettings.enabled] defaults to false) and every path falls back to normal playback.
 */
@Singleton
class PlaybackRepository @Inject constructor(
    private val gql: GqlApi,
    private val http: HttpClient,
    private val settings: SettingsRepository,
    private val config: RemoteConfigSource,
) : PlaylistRewriter {

    /** Backup media playlists per channel+quality, used to swap out ad segments. */
    private val backups = ConcurrentHashMap<String, String>()
    private val liveContext = ConcurrentHashMap<String, String>() // media playlist host path -> channel login
    private val adState = ConcurrentHashMap<String, Boolean>()

    suspend fun resolveLive(login: String): ResolvedPlayback {
        config.refresh()
        val s = settings.current()
        val ad = s.adBlock
        val codecs = s.player.codecs.toWire()
        val lowLatency = true

        if (ad.enabled && ad.acknowledgedRisks && ad.method != AdMethod.BACKUP_PLAYER_TYPE && ad.proxyTemplates.isNotEmpty()) {
            for (template in ad.proxyTemplates) {
                val proxied = attempt { resolveViaProxy(template, login) }.getOrNull()
                if (proxied != null) return proxied
            }
            Timber.w("All proxies failed, falling back to direct playback")
        }

        val token = gql.streamAccessToken(login, s.player.playerType.wire)
            ?: throw AppException(AppException.Kind.PLAYBACK_BLOCKED, "Twitch didn't return a playback token")
        val url = Usher.liveUrl(login, token, codecs, lowLatency)
        val master = fetch(url) ?: throw AppException(AppException.Kind.NOT_FOUND, "$login is offline or unavailable")
        val qualities = Usher.parseMaster(master)
        qualities.forEach { liveContext[it.url.substringBefore('?')] = login.lowercase() }
        return ResolvedPlayback(url, qualities)
    }

    suspend fun resolveVod(videoId: String): ResolvedPlayback {
        config.refresh()
        val s = settings.current()
        val token = gql.videoAccessToken(videoId, s.player.playerType.wire)
            ?: throw AppException(AppException.Kind.PLAYBACK_BLOCKED, "This video is unavailable (sub-only or deleted)")
        val url = Usher.vodUrl(videoId, token, s.player.codecs.toWire())
        val master = fetch(url) ?: throw AppException(AppException.Kind.NOT_FOUND, "Video unavailable")
        return ResolvedPlayback(url, Usher.parseMaster(master))
    }

    suspend fun resolveClip(slug: String): List<StreamQuality> {
        config.refresh()
        return gql.clipSources(slug).map {
            val h = it.quality.toIntOrNull()
            StreamQuality(it.quality, "${it.quality}p${it.frameRate?.takeIf { f -> f > 30 } ?: ""}", h, it.frameRate, null, it.url)
        }.sortedByDescending { it.height }
    }

    private suspend fun resolveViaProxy(template: String, login: String): ResolvedPlayback? {
        // Template examples users can add: https://proxy.example/live/{channel}.m3u8
        if (!template.startsWith("https://")) return null
        val url = template.replace("{channel}", URLEncoder.encode(login.lowercase(), "UTF-8"))
        val master = fetch(url) ?: return null
        val qualities = Usher.parseMaster(master)
        if (qualities.isEmpty()) return null
        return ResolvedPlayback(url, qualities, viaProxy = true)
    }

    private suspend fun fetch(url: String): String? {
        val res = http.get(url) {
            expectSuccess = false
            header("Client-Id", config.current().clientId)
        }
        return if (res.status.isSuccess()) res.bodyAsText() else null
    }

    private fun List<VideoCodec>.toWire(): List<String> = map {
        when (it) { VideoCodec.H264 -> "h264"; VideoCodec.H265 -> "h265"; VideoCodec.AV1 -> "av1" }
    }.ifEmpty { listOf("h264") }

    // region PlaylistRewriter: called by the player's data source for every media playlist

    override fun isAdBreak(playlistUrl: String): Boolean = adState[playlistUrl.substringBefore('?')] == true

    override suspend fun rewrite(playlistUrl: String, body: String): String {
        val key = playlistUrl.substringBefore('?')
        val hasAds = Usher.hasAds(body)
        adState[key] = hasAds
        if (!hasAds) {
            backups.remove(key)
            return if (settings.settings.value.player.bufferProfile != lol.sinist3r.yip.core.model.BufferProfile.STABLE) Usher.usePrefetch(body) else body
        }
        val ad = settings.settings.value.adBlock
        if (!ad.enabled || !ad.acknowledgedRisks) return body

        val login = liveContext[key]
        if (login != null && ad.method != AdMethod.PROXY) {
            backupPlaylist(key, login, playlistUrl, ad)?.let { return it }
        }
        return if (ad.stripAdSegmentsAsLastResort) Usher.stripAds(body) else body
    }

    private suspend fun backupPlaylist(key: String, login: String, originalUrl: String, ad: AdBlockSettings): String? {
        val wantedHeight = Regex("""/(\d{3,4})p""").find(originalUrl)?.groupValues?.get(1)?.toIntOrNull()
        backups[key]?.let { cachedUrl ->
            val text = fetch(cachedUrl)
            if (text != null && !Usher.hasAds(text)) return text
            backups.remove(key)
        }
        for (type in ad.backupPlayerTypes) {
            val token = attempt { gql.streamAccessToken(login, type.wire, platform = "web") }.getOrNull() ?: continue
            val master = fetch(Usher.liveUrl(login, token, listOf("h264"), lowLatency = true)) ?: continue
            val variants = Usher.parseMaster(master).filterNot { it.isAudioOnly }
            val pick = variants.firstOrNull { it.height == wantedHeight }
                ?: (if (ad.backupLowerQualityOk) variants.firstOrNull() else null)
                ?: continue
            val media = fetch(pick.url) ?: continue
            if (!Usher.hasAds(media)) {
                backups[key] = pick.url
                Timber.d("Ad break: using %s playlist", type.wire)
                return media
            }
        }
        return null
    }

    // endregion
}

/** Hook used by the player to inspect/replace HLS media playlists. */
interface PlaylistRewriter {
    fun isAdBreak(playlistUrl: String): Boolean
    suspend fun rewrite(playlistUrl: String, body: String): String
}
