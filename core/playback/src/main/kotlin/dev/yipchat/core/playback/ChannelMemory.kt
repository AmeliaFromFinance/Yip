package dev.yipchat.core.playback

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Per-channel volume, mute and audio-only, kept when "Per-channel memory" is on. */
data class ChannelMemo(val volume: Float = 1f, val muted: Boolean = false, val audioOnly: Boolean = false)

@Singleton
class ChannelMemory @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("channel_memory", Context.MODE_PRIVATE)

    fun get(channelId: String): ChannelMemo? {
        if (!prefs.contains("$channelId.v")) return null
        return ChannelMemo(
            volume = prefs.getFloat("$channelId.v", 1f),
            muted = prefs.getBoolean("$channelId.m", false),
            audioOnly = prefs.getBoolean("$channelId.a", false),
        )
    }

    fun update(channelId: String, change: (ChannelMemo) -> ChannelMemo) {
        val next = change(get(channelId) ?: ChannelMemo())
        prefs.edit {
            putFloat("$channelId.v", next.volume.coerceIn(0f, 1f))
            putBoolean("$channelId.m", next.muted)
            putBoolean("$channelId.a", next.audioOnly)
        }
    }

    fun clear() = prefs.edit { clear() }
}
