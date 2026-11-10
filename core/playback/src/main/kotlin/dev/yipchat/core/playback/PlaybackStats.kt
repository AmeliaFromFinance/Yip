package dev.yipchat.core.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaLoadData

/** Counters the stats overlay reads once a second. Reset whenever a new source starts. */
@UnstableApi
class PlaybackStats : AnalyticsListener {
    @Volatile var droppedFrames = 0L; private set
    @Volatile var networkBps = 0L; private set
    @Volatile var decoder: String? = null; private set
    /** Bitrate of the HLS variant actually playing (Twitch formats often lack it). */
    @Volatile var variantBps = 0L; private set

    fun reset() {
        droppedFrames = 0
        networkBps = 0
        variantBps = 0
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        this.droppedFrames += droppedFrames
    }

    override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
        networkBps = bitrateEstimate
    }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        decoder = decoderName
    }

    override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
        mediaLoadData.trackFormat?.let { f -> if (f.height > 0 && f.bitrate > 0) variantBps = f.bitrate.toLong() }
    }
}
