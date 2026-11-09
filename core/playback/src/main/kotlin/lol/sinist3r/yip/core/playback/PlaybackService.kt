package lol.sinist3r.yip.core.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the main player alive in the background, with play/pause and mute in the media
 * notification and on the lock screen.
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var controller: PlaybackController
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var unwatch: (() -> Unit)? = null
    private val muteCommand = SessionCommand(ACTION_TOGGLE_MUTE, Bundle.EMPTY)

    private fun muteButton(muted: Boolean) = CommandButton.Builder(if (muted) CommandButton.ICON_VOLUME_OFF else CommandButton.ICON_VOLUME_UP)
        .setDisplayName(if (muted) "Unmute" else "Mute")
        .setSessionCommand(muteCommand)
        .setSlots(CommandButton.SLOT_FORWARD)
        .build()

    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(muteCommand).build())
                .setMediaButtonPreferences(listOf(muteButton(this@PlaybackService.controller.state.value.muted)))
                .build()

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_TOGGLE_MUTE) {
                this@PlaybackService.controller.toggleMute()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val s = MediaSession.Builder(this, controller.player)
            .setCallback(callback)
            .setMediaButtonPreferences(listOf(muteButton(controller.state.value.muted)))
            .apply { launch?.let { setSessionActivity(PendingIntent.getActivity(this@PlaybackService, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)) } }
            .build()
        session = s
        // Without this the notification only appears once some other app connects to the session.
        addSession(s)
        unwatch = controller.watchPlayer { p -> session?.player = p }
        scope.launch {
            controller.state.map { it.muted }.distinctUntilChanged().collect { muted ->
                session?.setMediaButtonPreferences(listOf(muteButton(muted)))
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        unwatch?.invoke()
        session?.release()
        session = null
        super.onDestroy()
    }

    private companion object {
        const val ACTION_TOGGLE_MUTE = "lol.sinist3r.yip.TOGGLE_MUTE"
    }
}
