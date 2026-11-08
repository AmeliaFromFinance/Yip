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
    val userAgent: String? = null,
    val killSwitches: List<String> = emptyList(),
    val minAppVersion: Int = 0,
    val message: String? = null,
) {
    fun hash(op: String): String? = hashes[op] ?: DEFAULT_HASHES[op]

    companion object {
        const val DEFAULT_WEB_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
        val DEFAULT_HASHES = mapOf(
            "PlaybackAccessToken" to "ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9",
            "VideoAccessToken_Clip" to "993d9a5131f15a37bd16f32342c44ed1e0b1a9b968c6afdb662d2cddd595f6c5",
            "VideoCommentsByOffsetOrCursor" to "b70a3591ff0f4e0313d126c6a1502d79a1c02baebb288227c582044aa76adf6a",
            "ChannelPointsContext" to "33884dcfeab64eb44c2d61255145643cf69ea993e0c6c2ecb0fa46238586a111",
            "ClaimCommunityPoints" to "46aaeebe02c99afdf4fc97c7c0cba964124bf6b0af229395f1f6d1feed05b3d0",
            "VideoPlayer_ChapterSelectButtonVideo" to "71835d5ef425e154bf282453a926d99b328cdc5e32f36d3a209d0f4778b41203",
            "StreamMetadata" to "b57f9b910f8cd1a4659d894fe7550ccc81ec9052c01e438b290fd66a040b9b93",
            "FollowButton_FollowUser" to "800e7346bdf7e5278a3c1d3f21b2b56e2639928f86815677a7126b093b2fdd08",
            "FollowButton_UnfollowUser" to "f7dae976ebf41c755ae2d758546bfd176b4eeb856656098bb40e0a672ca0d880",
        )
    }
}

data class VideoChapter(val title: String, val startSeconds: Int, val durationSeconds: Int, val gameName: String?)
