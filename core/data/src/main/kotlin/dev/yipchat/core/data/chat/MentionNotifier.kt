package dev.yipchat.core.data.chat

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yipchat.core.common.VisibleChats
import dev.yipchat.core.data.settings.SettingsRepository
import dev.yipchat.core.model.ChatItem
import dev.yipchat.core.model.HighlightKind
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Notifies about mentions, keywords and highlighted users when that chat isn't on screen. */
@Singleton
class MentionNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private class Thread(val lines: ArrayDeque<String> = ArrayDeque(), var lastAlertAt: Long = 0)
    private val threads = ConcurrentHashMap<String, Thread>()
    private val whisperCounts = ConcurrentHashMap<String, Int>()
    @Volatile private var channelReady = false

    fun onMessage(channelId: String, channelLogin: String, m: ChatItem.Message) {
        if (m.isHistory || m.isSelf || m.isDeleted) return
        val n = settings.settings.value.notifications
        val wanted = when (m.highlight) {
            HighlightKind.MENTION -> n.pingMentions
            HighlightKind.KEYWORD -> n.pingKeywords
            HighlightKind.USER -> n.pingHighlightUsers
            else -> false
        }
        if (!wanted || VisibleChats.isVisible(channelId) || inQuietHours(n.quietHoursEnabled, n.quietStartMinutes, n.quietEndMinutes)) return
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannel()

        val thread = threads.getOrPut(channelLogin) { Thread() }
        val now = System.currentTimeMillis()
        val silent: Boolean
        synchronized(thread) {
            thread.lines.addLast("${m.user.displayName}: ${m.raw.take(200)}")
            while (thread.lines.size > 6) thread.lines.removeFirst()
            silent = now - thread.lastAlertAt < ALERT_GAP_MS // a burst updates quietly
            if (!silent) thread.lastAlertAt = now
        }
        val title = when (m.highlight) {
            HighlightKind.MENTION -> "${m.user.displayName} mentioned you in #$channelLogin"
            HighlightKind.USER -> "${m.user.displayName} in #$channelLogin"
            else -> "Highlight in #$channelLogin"
        }
        val open = Intent(Intent.ACTION_VIEW, "https://www.twitch.tv/$channelLogin".toUri()).setPackage(context.packageName)
        val pi = PendingIntent.getActivity(context, channelLogin.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val lines = synchronized(thread) { thread.lines.toList() }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon())
            .setContentTitle(title)
            .setContentText(lines.last())
            .setStyle(NotificationCompat.InboxStyle().also { s -> lines.forEach(s::addLine) })
            .setNumber(lines.size)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setSilent(silent)
            .setGroup("mentions")
            .build()
        @SuppressLint("MissingPermission") // checked via areNotificationsEnabled above
        nm.notify(NOTIFICATION_TAG, channelLogin.hashCode(), notification)
    }

    fun onWhisper(login: String, name: String, text: String) {
        val n = settings.settings.value.notifications
        if (!n.pingWhispers || inQuietHours(n.quietHoursEnabled, n.quietStartMinutes, n.quietEndMinutes)) return
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannel()
        val open = Intent(Intent.ACTION_VIEW, "yip://whisper/$login".toUri()).setPackage(context.packageName)
        val pi = PendingIntent.getActivity(context, ("w" + login).hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Unread count shows on the launcher icon badge where the launcher supports numbers.
        val count = whisperCounts.merge(login.lowercase(), 1, Int::plus) ?: 1
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon())
            .setContentTitle(if (count > 1) "$count whispers from $name" else "Whisper from $name")
            .setContentText(text.take(200))
            .setNumber(count)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setGroup("whispers")
            .build()
        @SuppressLint("MissingPermission") // checked via areNotificationsEnabled above
        nm.notify(WHISPER_TAG, login.lowercase().hashCode(), notification)
    }

    fun clearWhisper(login: String) {
        whisperCounts.remove(login.lowercase())
        NotificationManagerCompat.from(context).cancel(WHISPER_TAG, login.lowercase().hashCode())
    }

    /** Opening the chat clears its pings. */
    fun clear(channelLogin: String) {
        threads.remove(channelLogin)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, channelLogin.hashCode())
    }

    private fun ensureChannel() {
        if (channelReady) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Chat pings", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Mentions, whispers, highlight keywords and highlighted users"
                setShowBadge(true)
            },
        )
        channelReady = true
    }

    private fun smallIcon(): Int =
        context.resources.getIdentifier("ic_notification", "drawable", context.packageName).takeIf { it != 0 } ?: context.applicationInfo.icon

    private fun inQuietHours(enabled: Boolean, start: Int, end: Int): Boolean {
        if (!enabled) return false
        val t = LocalTime.now().let { it.hour * 60 + it.minute }
        return if (start <= end) t in start until end else t >= start || t < end
    }

    private companion object {
        const val CHANNEL_ID = "chat_pings"
        const val NOTIFICATION_TAG = "ping"
        const val WHISPER_TAG = "whisper"
        const val ALERT_GAP_MS = 15_000L
    }
}
