package dev.yipchat.core.data.history

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yipchat.core.model.Clip
import dev.yipchat.core.model.Video
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A VOD or clip saved for later. [kind] is VOD or CLIP. */
@Serializable
data class WatchLaterItem(
    val kind: String,
    val id: String,
    val channelLogin: String,
    val channelName: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val addedAt: Long,
    val vodId: String? = null,
    val vodOffset: Int? = null,
) {
    val key get() = "$kind:$id"

    companion object {
        fun of(v: Video) = WatchLaterItem("VOD", v.id, v.userLogin, v.userName, v.title, v.thumbnail(320, 180), v.durationSeconds, System.currentTimeMillis())
        fun of(c: Clip) = WatchLaterItem("CLIP", c.id, "", c.broadcasterName, c.title, c.thumbnailUrl, c.durationSeconds.toLong(), System.currentTimeMillis(), c.videoId, c.vodOffset)
    }
}

/** Watch later queue, oldest first. Kept on the device. */
@Singleton
class WatchLaterRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("watch_later", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<WatchLaterItem>> = _items.asStateFlow()

    fun contains(kind: String, id: String) = _items.value.any { it.kind == kind && it.id == id }

    fun add(item: WatchLaterItem) = save(_items.value.filterNot { it.key == item.key } + item)

    fun remove(kind: String, id: String) = save(_items.value.filterNot { it.kind == kind && it.id == id })

    fun toggle(item: WatchLaterItem) = if (contains(item.kind, item.id)) remove(item.kind, item.id) else add(item)

    /** The item after [kind]:[id] in the queue, or null when it isn't queued or is last. */
    fun after(kind: String, id: String): WatchLaterItem? {
        val list = _items.value
        val i = list.indexOfFirst { it.kind == kind && it.id == id }
        return if (i >= 0) list.getOrNull(i + 1) else null
    }

    fun clear() = save(emptyList())

    private fun load(): List<WatchLaterItem> =
        runCatching { json.decodeFromString<List<WatchLaterItem>>(prefs.getString(KEY, null) ?: "[]") }.getOrDefault(emptyList())

    private fun save(list: List<WatchLaterItem>) {
        _items.value = list
        prefs.edit { putString(KEY, json.encodeToString(list)) }
    }

    private companion object { const val KEY = "items" }
}
