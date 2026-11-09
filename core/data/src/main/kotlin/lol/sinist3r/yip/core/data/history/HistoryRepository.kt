package lol.sinist3r.yip.core.data.history

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.database.HistoryDao
import lol.sinist3r.yip.core.database.HistoryEntity
import lol.sinist3r.yip.core.database.HistoryKind

data class HistoryEntry(
    val key: String,
    val kind: HistoryKind,
    val contentId: String,
    val channelId: String,
    val channelLogin: String,
    val channelName: String,
    val title: String,
    val thumbnailUrl: String?,
    val positionSeconds: Long,
    val durationSeconds: Long,
    val updatedAt: Long,
) {
    val progress: Float get() = if (durationSeconds > 0) (positionSeconds.toFloat() / durationSeconds).coerceIn(0f, 1f) else 0f
    /** Watched to the end (credits and outros count). */
    val finished: Boolean get() = durationSeconds > 0 && (progress >= 0.97f || durationSeconds - positionSeconds < 60)
}

@Singleton
class HistoryRepository @Inject constructor(private val dao: HistoryDao, private val settings: SettingsRepository) {

    val recent: Flow<List<HistoryEntry>> = dao.recent().map { list -> list.map { it.toEntry() } }
    /** Everything kept, for the Library screen. */
    val all: Flow<List<HistoryEntry>> = dao.recent(500).map { list -> list.map { it.toEntry() } }

    suspend fun record(
        kind: HistoryKind, contentId: String, channelId: String, channelLogin: String, channelName: String,
        title: String, thumbnailUrl: String?, positionSeconds: Long, durationSeconds: Long,
    ) {
        val s = settings.current()
        if (!s.data.keepWatchHistory || !s.general.continueWatching) return
        dao.upsert(HistoryEntity("${kind.name}:$contentId", kind, contentId, channelId, channelLogin, channelName, title, thumbnailUrl, positionSeconds, durationSeconds, System.currentTimeMillis()))
        dao.prune(System.currentTimeMillis() - s.data.historyDays * 86_400_000L)
    }

    /** VODs started but not finished, newest first. */
    val continueWatching: Flow<List<HistoryEntry>> = recent.map { list -> list.filter { it.kind == HistoryKind.VOD && it.positionSeconds > 30 && !it.finished } }

    @Volatile private var lastPrune = 0L

    /** Drops VODs Twitch no longer has. At most once an hour; unknown answers keep the entry. */
    suspend fun pruneDeletedVods(exists: suspend (String) -> Boolean?) {
        val now = System.currentTimeMillis()
        if (now - lastPrune < 60 * 60_000L) return
        lastPrune = now
        dao.recent(500).first().filter { it.kind == HistoryKind.VOD }.forEach { e ->
            if (exists(e.contentId) == false) dao.delete(e.key)
        }
    }

    suspend fun resumePosition(videoId: String): Long? = dao.get("VOD:$videoId")?.positionSeconds?.takeIf { it > 30 }
    suspend fun remove(key: String) = dao.delete(key)
    suspend fun clear() = dao.clear()

    private fun HistoryEntity.toEntry() = HistoryEntry(key, kind, contentId, channelId, channelLogin, channelName, title, thumbnailUrl, positionSeconds, durationSeconds, updatedAt)
}
