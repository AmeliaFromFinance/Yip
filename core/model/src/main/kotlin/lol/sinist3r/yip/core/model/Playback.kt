package lol.sinist3r.yip.core.model

import kotlinx.serialization.Serializable

@Serializable
data class StreamQuality(
    val id: String,
    val label: String,
    val height: Int?,
    val fps: Int?,
    val bandwidth: Long?,
    val url: String,
    val isSource: Boolean = false,
    val isAudioOnly: Boolean = false,
)

data class ResolvedPlayback(
    val masterPlaylistUrl: String,
    val qualities: List<StreamQuality>,
    val viaProxy: Boolean = false,
)

/** Signed config published by the server watcher. */
@Serializable
data class RemoteConfig(
    val version: Int = 1,
    val generatedAt: Long = 0,
    val clientId: String = DEFAULT_WEB_CLIENT_ID,
    val hashes: Map<String, String> = emptyMap(),
    /** Raw GraphQL documents the web player sends without a persisted hash. */
    val queries: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val killSwitches: List<String> = emptyList(),
    val minAppVersion: Int = 0,
    val message: String? = null,
) {
    fun hash(op: String): String? = hashes[op] ?: DEFAULT_HASHES[op]
    fun query(op: String): String? = queries[op] ?: DEFAULT_QUERIES[op]

    companion object {
        const val DEFAULT_WEB_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"

        /** As sent by the twitch.tv player (Nov 2026). The server watcher keeps this current. */
        val DEFAULT_QUERIES = mapOf(
            "PlaybackAccessToken_Template" to
                "query PlaybackAccessToken_Template(\$login: String!, \$isLive: Boolean!, \$vodID: ID!, \$isVod: Boolean!, \$playerType: String!, \$platform: String!) {" +
                "  streamPlaybackAccessToken(channelName: \$login, params: {platform: \$platform, playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isLive) {" +
                "    value    signature   authorization { isForbidden forbiddenReasonCode }   __typename  }" +
                "  videoPlaybackAccessToken(id: \$vodID, params: {platform: \$platform, playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isVod) {" +
                "    value    signature   __typename  }}",
        )
        val DEFAULT_HASHES = mapOf(
            "PlaybackAccessToken" to "ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9",
            "VideoAccessToken_Clip" to "c5ca7c4143e42f257b91b97b37fbb19206460f42f564a02095bb504fd0a31af8",
            "VideoCommentsByOffsetOrCursor" to "b70a3591ff0f4e0313d126c6a1502d79a1c02baebb288227c582044aa76adf6a",
            "ChannelPointsContext" to "33884dcfeab64eb44c2d61255145643cf69ea993e0c6c2ecb0fa46238586a111",
            "ClaimCommunityPoints" to "46aaeebe02c99afdf4fc97c7c0cba964124bf6b0af229395f1f6d1feed05b3d0",
            "VideoPlayer_ChapterSelectButtonVideo" to "71835d5ef425e154bf282453a926d99b328cdc5e32f36d3a209d0f4778b41203",
            "StreamMetadata" to "8f3c9ee3a77ff7e648268ac5daad6b36f2add690c628ec819e2e26aebc5cb767",
            "FollowButton_FollowUser" to "800e7346bdf7e5278a3c1d3f21b2b56e2639928f86815677a7126b093b2fdd08",
            "FollowButton_UnfollowUser" to "f7dae976ebf41c755ae2d758546bfd176b4eeb856656098bb40e0a672ca0d880",
        )
    }
}

data class VideoChapter(val title: String, val startSeconds: Int, val durationSeconds: Int, val gameName: String?)

/** VOD scrub thumbnails: [count] frames, one every [intervalSeconds], laid out [cols] x [rows] per sprite image. */
data class Storyboard(
    val images: List<String>,
    val count: Int,
    val intervalSeconds: Int,
    val cols: Int,
    val rows: Int,
    val width: Int,
    val height: Int,
) {
    data class Frame(val imageUrl: String, val x: Int, val y: Int)

    fun frameAt(seconds: Long): Frame? {
        if (images.isEmpty() || count <= 0 || intervalSeconds <= 0 || cols <= 0 || rows <= 0) return null
        val index = (seconds / intervalSeconds).toInt().coerceIn(0, count - 1)
        val perImage = cols * rows
        val image = images.getOrNull(index / perImage) ?: return null
        val cell = index % perImage
        return Frame(image, (cell % cols) * width, (cell / cols) * height)
    }
}

/** Part of a VOD Twitch muted for copyrighted audio. */
data class MutedSegment(val offsetSeconds: Int, val durationSeconds: Int) {
    val endSeconds: Int get() = offsetSeconds + durationSeconds
}

/** [liveLogin]: the VOD of a stream that's still live, so "Go live" can jump back. */
data class VideoExtras(val storyboard: Storyboard? = null, val muted: List<MutedSegment> = emptyList(), val liveLogin: String? = null)
