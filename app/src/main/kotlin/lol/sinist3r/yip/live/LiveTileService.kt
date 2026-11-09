package lol.sinist3r.yip.live

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import lol.sinist3r.yip.MainActivity

/** Quick Settings: shows the top live favourite; tap to watch. */
class LiveTileService : TileService() {
    override fun onTileAdded() {
        prefs(this).edit().putBoolean(ADDED, true).apply()
        LiveRefreshWorker.schedule(this)
        LiveRefreshWorker.runNow(this)
    }

    override fun onTileRemoved() {
        prefs(this).edit().putBoolean(ADDED, false).apply()
        LiveRefreshWorker.cancelIfUnused(this)
    }

    override fun onStartListening() {
        val tile = qsTile ?: return
        val top = snapshot(this).entries.firstOrNull()
        tile.label = top?.name ?: "Yip"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = if (top != null) listOfNotNull("Live", top.game).joinToString(" · ") else "No one live"
        tile.state = if (top != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.contentDescription = if (top != null) "${top.name} is live. Tap to watch." else "Open Yip"
        tile.updateTile()
    }

    // Android 13 and older only have the Intent version.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val top = snapshot(this).entries.firstOrNull()
        val intent = if (top != null) openStream(this, top.login) else android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val ADDED = "tile_added"
        private fun prefs(c: Context) = c.getSharedPreferences("live_snapshot", Context.MODE_PRIVATE)
        fun isAdded(c: Context) = prefs(c).getBoolean(ADDED, false)
        fun requestUpdate(c: Context) = requestListeningState(c, ComponentName(c, LiveTileService::class.java))
    }
}
