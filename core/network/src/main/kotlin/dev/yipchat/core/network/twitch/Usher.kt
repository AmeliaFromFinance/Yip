package dev.yipchat.core.network.twitch

import dev.yipchat.core.model.StreamQuality
import io.ktor.http.URLBuilder
import kotlin.math.round
import kotlin.random.Random

/** Builds usher manifest URLs and parses Twitch master playlists. */
object Usher {

    fun liveUrl(login: String, token: GqlApi.AccessToken, codecs: List<String>, lowLatency: Boolean): String =
        URLBuilder("https://usher.ttvnw.net/api/v2/channel/hls/${login.lowercase()}.m3u8").apply {
            common(codecs)
            parameters.append("sig", token.signature)
            parameters.append("token", token.value)
            parameters.append("fast_bread", lowLatency.toString())
        }.buildString()

    fun vodUrl(videoId: String, token: GqlApi.AccessToken, codecs: List<String>): String =
        URLBuilder("https://usher.ttvnw.net/vod/v2/$videoId.m3u8").apply {
            common(codecs)
            parameters.append("nauthsig", token.signature)
            parameters.append("nauth", token.value)
        }.buildString()

    private fun URLBuilder.common(codecs: List<String>) {
        parameters.append("platform", "web")
        parameters.append("p", Random.nextInt(0, 999_999).toString())
        parameters.append("allow_source", "true")
        parameters.append("allow_audio_only", "true")
        parameters.append("playlist_include_framerate", "true")
        parameters.append("multigroup_video", "true")
        parameters.append("supported_codecs", codecs.joinToString(","))
    }

    private val attrRegex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    /** Parses EXT-X-STREAM-INF variants into qualities, best first. */
    fun parseMaster(playlist: String): List<StreamQuality> {
        val lines = playlist.lines()
        val media = mutableMapOf<String, String>() // GROUP-ID -> NAME
        val out = mutableListOf<StreamQuality>()
        lines.forEachIndexed { i, line ->
            when {
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = attrs(line)
                    val group = a["GROUP-ID"]
                    val name = a["NAME"]
                    if (group != null && name != null) media[group] = name
                }
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val a = attrs(line)
                    val url = lines.getOrNull(i + 1)?.trim()?.takeIf { it.startsWith("http") } ?: return@forEachIndexed
                    // Current manifests use STABLE-VARIANT-ID / IVS-NAME; older ones VIDEO + EXT-X-MEDIA.
                    val id = a["STABLE-VARIANT-ID"] ?: a["VIDEO"] ?: a["IVS-NAME"] ?: url.hashCode().toString()
                    val name = a["IVS-NAME"] ?: media[a["VIDEO"]] ?: id
                    val res = a["RESOLUTION"]?.split('x')
                    val height = res?.getOrNull(1)?.toIntOrNull()
                    val fps = a["FRAME-RATE"]?.toDoubleOrNull()?.let { round(it).toInt() }
                    val codecs = a["CODECS"].orEmpty()
                    val audioOnly = id.startsWith("audio") || name.startsWith("audio") ||
                        (res == null && codecs.isNotEmpty() && listOf("avc", "hvc", "hev", "av01").none { it in codecs })
                    val isSource = a["IVS-VARIANT-SOURCE"] == "source" || id == "chunked" || name.contains("source", true)
                    out += StreamQuality(
                        id = id,
                        label = when {
                            audioOnly -> "Audio only"
                            height != null -> "${height}p" + (fps?.takeIf { it > 30 }?.toString() ?: "")
                            else -> name.replace("(source)", "").trim()
                        },
                        height = if (audioOnly) 0 else height,
                        fps = fps,
                        bandwidth = a["BANDWIDTH"]?.toLongOrNull(),
                        url = url,
                        isSource = isSource,
                        isAudioOnly = audioOnly,
                    )
                }
            }
        }
        return out.distinctBy { it.id }.sortedWith(compareByDescending<StreamQuality> { it.height ?: -1 }.thenByDescending { it.fps ?: 0 })
    }

    private fun attrs(line: String): Map<String, String> =
        attrRegex.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    /**
     * Twitch lists the next segments as #EXT-X-TWITCH-PREFETCH before they're finished. Treating
     * them as normal segments lets the player start on them early, which cuts a few seconds of delay.
     */
    fun usePrefetch(mediaPlaylist: String): String {
        if ("#EXT-X-TWITCH-PREFETCH:" !in mediaPlaylist) return mediaPlaylist
        val duration = mediaPlaylist.lineSequence().lastOrNull { it.startsWith("#EXTINF:") }?.substringAfter(':')?.substringBefore(',') ?: "2.000"
        return mediaPlaylist.lineSequence().flatMap { line ->
            if (line.startsWith("#EXT-X-TWITCH-PREFETCH:")) sequenceOf("#EXTINF:$duration,live", line.substringAfter(':')) else sequenceOf(line)
        }.joinToString("\n")
    }

    // region ad markers (used only when the user opts in to ad handling)

    /** True if a media playlist currently contains stitched ad segments. */
    fun hasAds(mediaPlaylist: String): Boolean =
        "stitched-ad" in mediaPlaylist || "twitch-stitched-ad" in mediaPlaylist ||
            mediaPlaylist.lineSequence().any { it.startsWith("#EXTINF") && "Amazon" in it }

    /**
     * Removes ad segments from a media playlist. Used only as a last resort; Twitch then
     * serves nothing for the ad duration, so playback pauses rather than shows an ad.
     */
    fun stripAds(mediaPlaylist: String): String {
        if (!hasAds(mediaPlaylist)) return mediaPlaylist
        val out = ArrayList<String>()
        var skipUri = false
        for (line in mediaPlaylist.lineSequence()) {
            when {
                line.startsWith("#EXT-X-DATERANGE") && "stitched-ad" in line -> Unit
                line.startsWith("#EXT-X-TWITCH-PREFETCH") -> Unit
                line.startsWith("#EXTINF") -> {
                    val isAd = !line.substringAfter(',').contains("live")
                    if (isAd) skipUri = true else out += line
                }
                !line.startsWith("#") && line.isNotBlank() -> if (skipUri) skipUri = false else out += line
                else -> out += line
            }
        }
        return out.joinToString("\n")
    }

    // endregion
}
