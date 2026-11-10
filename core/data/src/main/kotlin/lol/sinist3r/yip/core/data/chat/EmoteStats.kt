package lol.sinist3r.yip.core.data.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lol.sinist3r.yip.core.model.Emote

@Serializable
data class EmoteUse(val code: String, val count: Int, val lastUsed: Long)

/** How often you send each emote, keyed by [Emote.favouriteKey]. Kept on this device. */
@Singleton
class EmoteStats @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("emote_stats", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _uses = MutableStateFlow(load())
    val uses: StateFlow<Map<String, EmoteUse>> = _uses.asStateFlow()

    private fun load(): Map<String, EmoteUse> = runCatching {
        prefs.getString(KEY, null)?.let { json.decodeFromString<Map<String, EmoteUse>>(it) }
    }.getOrNull().orEmpty()

    /** Counts each emote once per message, so spam doesn't skew it. */
    fun record(emotes: Collection<Emote>) {
        if (emotes.isEmpty()) return
        val now = System.currentTimeMillis()
        val next = _uses.value.toMutableMap()
        emotes.distinctBy { it.favouriteKey }.forEach { e ->
            val prev = next[e.favouriteKey]
            next[e.favouriteKey] = EmoteUse(e.code, (prev?.count ?: 0) + 1, now)
        }
        // Keep it small.
        val trimmed = if (next.size > MAX) next.entries.sortedByDescending { it.value.count }.take(MAX).associate { it.key to it.value } else next
        _uses.value = trimmed
        prefs.edit().putString(KEY, json.encodeToString(trimmed)).apply()
    }

    fun reset() {
        _uses.value = emptyMap()
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "uses"
        private const val MAX = 500

        /** Most used first; ties go to the most recent. */
        fun top(uses: Map<String, EmoteUse>, n: Int): List<String> =
            uses.entries.sortedWith(compareByDescending<Map.Entry<String, EmoteUse>> { it.value.count }.thenByDescending { it.value.lastUsed }).take(n).map { it.key }
    }
}
