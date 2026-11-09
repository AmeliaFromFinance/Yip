package lol.sinist3r.yip.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import lol.sinist3r.yip.MainActivity
import lol.sinist3r.yip.R
import lol.sinist3r.yip.YipApplication
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.data.account.AccountRepository
import lol.sinist3r.yip.core.data.notifications.LiveChecker
import lol.sinist3r.yip.core.data.settings.SettingsRepository

@HiltWorker
class LiveNotificationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checker: LiveChecker,
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
    private val history: lol.sinist3r.yip.core.data.history.HistoryRepository,
    private val twitch: lol.sinist3r.yip.core.data.twitch.TwitchRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (accounts.userId() == null) return Result.success()
        attempt { history.pruneDeletedVods(twitch::vodExists) }
        val alerts = attempt { checker.check() }.getOrElse { return Result.retry() }
        if (alerts.isEmpty()) return Result.success()
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val nm = NotificationManagerCompat.from(applicationContext)
        val group = settings.current().notifications.groupNotifications
        alerts.forEach { a ->
            val s = a.stream
            val intent = Intent(Intent.ACTION_VIEW, "https://www.twitch.tv/${s.userLogin}".toUri(), applicationContext, MainActivity::class.java)
            val pi = PendingIntent.getActivity(applicationContext, s.userId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val title = when {
                a.categoryAlert && a.categoryChanged -> "${s.userName} switched to ${s.gameName}"
                a.categoryAlert -> "${s.userName} is streaming ${s.gameName}"
                a.categoryChanged -> "${s.userName} switched to ${s.gameName}"
                else -> "${s.userName} is live"
            }
            val n = NotificationCompat.Builder(applicationContext, YipApplication.CHANNEL_LIVE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(listOfNotNull(s.gameName, s.title).joinToString(" · "))
                .setStyle(NotificationCompat.BigTextStyle().bigText(s.title))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .apply { if (group) setGroup("live") }
                .build()
            @Suppress("MissingPermission")
            nm.notify(s.userId.hashCode(), n)
        }
        if (group && alerts.size > 1) {
            @Suppress("MissingPermission")
            nm.notify(
                0,
                NotificationCompat.Builder(applicationContext, YipApplication.CHANNEL_LIVE)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("${alerts.size} channels went live")
                    .setGroup("live")
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .build(),
            )
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "live-notifications"

        fun schedule(context: Context, enabled: Boolean, intervalMinutes: Int) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<LiveNotificationWorker>(intervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
