package lol.sinist3r.yip.live

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.action.ActionParameters
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import lol.sinist3r.yip.MainActivity
import lol.sinist3r.yip.core.data.live.LiveSnapshot
import lol.sinist3r.yip.core.data.live.LiveSnapshotData

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LiveEntryPoint {
    fun liveSnapshot(): LiveSnapshot
}

internal fun snapshot(context: Context): LiveSnapshotData =
    EntryPointAccessors.fromApplication(context.applicationContext, LiveEntryPoint::class.java).liveSnapshot().data.value

internal fun openStream(context: Context, login: String) =
    Intent(Intent.ACTION_VIEW, "https://www.twitch.tv/$login".toUri(), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Home screen widget: who's live, pinned favourites first. */
class LiveWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val flow = EntryPointAccessors.fromApplication(context.applicationContext, LiveEntryPoint::class.java).liveSnapshot().data
        // Collected inside the content so later updates show; reading it once left the widget stuck on its first state.
        provideContent {
            val data by flow.collectAsState()
            GlanceTheme { Content(context, data) }
        }
    }

    @Composable
    private fun Content(context: Context, data: LiveSnapshotData) {
        Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(12.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Live now", style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp),
                    modifier = GlanceModifier.defaultWeight().clickable(androidx.glance.action.actionStartActivity<MainActivity>()),
                )
                Text(
                    "Refresh", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp),
                    modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()).padding(4.dp),
                )
            }
            Spacer(GlanceModifier.width(4.dp))
            when {
                !data.signedIn -> Hint("Sign in to Yip to see who's live")
                data.entries.isEmpty() -> Hint(if (data.updatedAt == 0L) "Open Yip once to load your follows" else "No one you follow is live")
                else -> LazyColumn {
                    items(data.entries.take(12), itemId = { it.login.hashCode().toLong() }) { e ->
                        Column(
                            GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(actionStartActivity(openStream(context, e.login)))
                                .semantics { contentDescription = "${e.name}, live${e.game?.let { g -> ", $g" }.orEmpty()}" },
                        ) {
                            Text((if (e.pinned) "★ " else "") + e.name, style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp), maxLines = 1)
                            Text(listOfNotNull(e.game, "${e.viewers} watching").joinToString(" · "), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Hint(text: String) = Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp), modifier = GlanceModifier.padding(top = 8.dp))
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = LiveRefreshWorker.runNow(context)
}

class LiveWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LiveWidget()
    override fun onEnabled(context: Context) { super.onEnabled(context); LiveRefreshWorker.schedule(context); LiveRefreshWorker.runNow(context) }
    override fun onDisabled(context: Context) { super.onDisabled(context); LiveRefreshWorker.cancelIfUnused(context) }
}

/** Redraws the widget and tile from the latest snapshot. */
suspend fun refreshLiveSurfaces(context: Context) {
    if (GlanceAppWidgetManager(context).getGlanceIds(LiveWidget::class.java).isNotEmpty()) LiveWidget().updateAll(context)
    LiveTileService.requestUpdate(context)
}

internal object LiveRefreshWorker {
    private const val PERIODIC = "live_snapshot"
    private const val NOW = "live_snapshot_now"

    fun schedule(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        PERIODIC, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<LiveSnapshotWorker>(30, TimeUnit.MINUTES).build(),
    )

    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<LiveSnapshotWorker>().build())
    }

    fun cancelIfUnused(context: Context) {
        if (!LiveTileService.isAdded(context)) WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}
