package lol.sinist3r.yip.core.playback

import android.media.audiofx.DynamicsProcessing
import timber.log.Timber

/**
 * Evens out loudness between streams: a gentle compressor lifts quiet audio and a limiter
 * stops loud parts from clipping. Runs on the player's audio session, so it only affects Yip.
 */
internal class VolumeLeveller {
    private var dp: DynamicsProcessing? = null
    private var session = 0

    fun apply(audioSessionId: Int, enabled: Boolean) {
        if (!enabled || audioSessionId <= 0) { release(); return }
        if (dp != null && session == audioSessionId) return
        release()
        dp = runCatching { build(audioSessionId) }.onFailure { Timber.w(it, "Volume levelling unavailable") }.getOrNull()
        session = audioSessionId
    }

    fun release() {
        runCatching { dp?.release() }
        dp = null
        session = 0
    }

    private fun build(sessionId: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, CHANNELS,
            false, 0, // pre-EQ
            true, 1, // one compressor band
            false, 0, // post-EQ
            true, // limiter
        ).build()
        return DynamicsProcessing(0, sessionId, config).apply {
            // Anything above -30 dB is squeezed 3:1, then everything is raised 9 dB.
            setMbcBandAllChannelsTo(0, DynamicsProcessing.MbcBand(true, 20_000f, 20f, 400f, 3f, -30f, 8f, -90f, 1f, 0f, 9f))
            setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 80f, 10f, -1f, 0f))
            enabled = true
        }
    }

    private companion object { const val CHANNELS = 2 }
}
