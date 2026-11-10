package dev.yipchat.core.data.live

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yipchat.core.data.settings.SettingsRepository
import dev.yipchat.core.model.LiveStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LiveEntry(val login: String, val name: String, val game: String?, val viewers: Int, val pinned: Boolean)

@Serializable
data class LiveSnapshotData(val entries: List<LiveEntry> = emptyList(), val updatedAt: Long = 0, val signedIn: Boolean = true)

/**
 * Last known "who's live" among follows, pinned favourites first. Saved on every follow-list fetch
 * so the home screen widget and Quick Settings tile can show it without a network call.
 */
@Singleton
class LiveSnapshot @Inject constructor(@ApplicationContext context: Context, private val settings: SettingsRepository) {
    private val prefs = context.getSharedPreferences("live_snapshot", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _data = MutableStateFlow(load())
    val data: StateFlow<LiveSnapshotData> = _data.asStateFlow()

    private fun load(): LiveSnapshotData =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(LiveSnapshotData.serializer(), it) }.getOrNull() } ?: LiveSnapshotData()

    fun save(live: List<LiveStream>) {
        val entries = entries(live, settings.settings.value.notifications.favourites.toSet())
        write(LiveSnapshotData(entries, System.currentTimeMillis(), signedIn = true))
    }

    fun signedOut() = write(LiveSnapshotData(signedIn = false, updatedAt = System.currentTimeMillis()))

    private fun write(d: LiveSnapshotData) {
        prefs.edit { putString(KEY, json.encodeToString(LiveSnapshotData.serializer(), d)) }
        _data.value = d
    }

    companion object {
        private const val KEY = "data"

        /** Pinned favourites first, then by viewers. */
        fun entries(live: List<LiveStream>, pins: Set<String>): List<LiveEntry> =
            live.map { LiveEntry(it.userLogin, it.userName, it.gameName, it.viewerCount, it.userLogin.lowercase() in pins) }
                .sortedWith(compareByDescending<LiveEntry> { it.pinned }.thenByDescending { it.viewers })
    }
}
