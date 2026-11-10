package dev.yipchat.core.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yipchat.core.data.playback.PlaylistRewriter
import dev.yipchat.core.model.BufferProfile
import dev.yipchat.core.model.PlayerSettings
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

@UnstableApi
@Singleton
class PlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
    private val rewriter: PlaylistRewriter,
) {
    private val httpFactory by lazy {
        OkHttpDataSource.Factory(okHttp).setUserAgent("Mozilla/5.0 (Linux; Android) Yip")
    }
    private val dataFactory by lazy { RewritingDataSource.Factory(httpFactory, okHttp, rewriter) }

    fun create(settings: PlayerSettings, forMultiView: Boolean = false): ExoPlayer {
        val (minMs, maxMs, playMs) = when (settings.bufferProfile) {
            BufferProfile.LOW_LATENCY -> Triple(1_500, 6_000, 500)
            BufferProfile.BALANCED -> Triple(3_000, 15_000, 1_000)
            BufferProfile.STABLE -> Triple(8_000, 40_000, 2_500)
        }
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, playMs, playMs * 2)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(context)
            .setLoadControl(load)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ !forMultiView && settings.pauseOnCall,
            )
            .setHandleAudioBecomingNoisy(settings.pauseOnHeadphonesUnplugged)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(settings.doubleTapSeekSeconds * 1000L)
            .setSeekForwardIncrementMs(settings.doubleTapSeekSeconds * 1000L)
            .build()
    }

    fun hlsSource(url: String, live: Boolean, profile: BufferProfile, meta: MediaMetadata): MediaSource {
        val target = when (profile) {
            BufferProfile.LOW_LATENCY -> 3_000L
            BufferProfile.BALANCED -> 6_000L
            BufferProfile.STABLE -> 12_000L
        }
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(meta)
            .apply {
                if (live) setLiveConfiguration(
                    // Speeds up a little to close small gaps; big gaps jump instead (see PlaybackController.catchUp).
                    MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(target).setMinPlaybackSpeed(0.97f)
                        .setMaxPlaybackSpeed(if (profile == BufferProfile.LOW_LATENCY) 1.08f else 1.04f).build(),
                )
            }
            .build()
        return HlsMediaSource.Factory(dataFactory)
            .setAllowChunklessPreparation(true)
            .createMediaSource(item)
    }

    fun progressiveSource(url: String, meta: MediaMetadata): MediaSource =
        ProgressiveMediaSource.Factory(httpFactory).createMediaSource(MediaItem.Builder().setUri(url).setMediaMetadata(meta).build())
}
