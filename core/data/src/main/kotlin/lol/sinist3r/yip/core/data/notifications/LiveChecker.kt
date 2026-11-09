package lol.sinist3r.yip.core.data.notifications

import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.data.twitch.TwitchRepository
import lol.sinist3r.yip.core.database.LiveNotifiedEntity
import lol.sinist3r.yip.core.database.NotifiedDao
import lol.sinist3r.yip.core.model.LiveStream

/** [categoryAlert]: fired because of a category alert, not a channel follow setting. */
data class LiveAlert(val stream: LiveStream, val categoryChanged: Boolean, val categoryAlert: Boolean = false)

/** Finds followed channels that went live since the last check. No server involved. */
@Singleton
class LiveChecker @Inject constructor(
    private val twitch: TwitchRepository,
    private val dao: NotifiedDao,
    private val settings: SettingsRepository,
) {
    suspend fun check(): List<LiveAlert> {
        val n = settings.current().notifications
        if (!n.enabled) return emptyList()
        val live = twitch.followedLive()
        val known = dao.all().associateBy { it.channelId }
        dao.keepOnly(live.map { it.userId }.ifEmpty { listOf("") })
        val firstRun = known.isEmpty()
        val alerts = live.mapNotNull { s ->
            val prev = known[s.userId]
            val login = s.userLogin.lowercase()
            val allowed = login !in n.muted && (!n.onlyFavourites || login in n.favourites)
            val result = when {
                prev == null -> LiveAlert(s, categoryChanged = false)
                prev.streamId == s.id && n.notifyCategoryChange && prev.categoryName != s.gameName -> LiveAlert(s, categoryChanged = true)
                prev.streamId != s.id -> LiveAlert(s, categoryChanged = false)
                else -> null
            }
            dao.upsert(LiveNotifiedEntity(s.userId, s.id, s.gameName, System.currentTimeMillis()))
            // Category alerts: went live in, or moved into, a watched category. Muted channels still stay quiet.
            val enteredCategory = s.gameName?.lowercase() in n.categoryAlerts && (prev == null || prev.streamId != s.id || prev.categoryName != s.gameName)
            when {
                result != null && allowed -> result
                enteredCategory && login !in n.muted -> LiveAlert(s, categoryChanged = prev?.streamId == s.id, categoryAlert = true)
                else -> null
            }
        }
        // The very first check only records state; otherwise everyone already live would ping at once.
        if (firstRun || inQuietHours(n.quietHoursEnabled, n.quietStartMinutes, n.quietEndMinutes)) return emptyList()
        return alerts
    }

    private fun inQuietHours(enabled: Boolean, start: Int, end: Int): Boolean {
        if (!enabled) return false
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        return if (start <= end) now in start until end else now >= start || now < end
    }
}
