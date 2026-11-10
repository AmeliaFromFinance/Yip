package lol.sinist3r.yip.core.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.data.twitch.TwitchRepository
import lol.sinist3r.yip.core.model.LiveStream
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
 * notification and on the lock screen. Also serves the Android Auto browse tree:
 * favourites and followed channels that are live, played audio-only.
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var controller: PlaybackController
    @Inject lateinit var twitch: TwitchRepository
    @Inject lateinit var settings: SettingsRepository
    private var session: MediaLibrarySession? = null
    /** Last live list served to a browser, by login. */
    private val known = java.util.concurrent.ConcurrentHashMap<String, LiveStream>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var unwatch: (() -> Unit)? = null
    private val muteCommand = SessionCommand(ACTION_TOGGLE_MUTE, Bundle.EMPTY)

    private fun muteButton(muted: Boolean) = CommandButton.Builder(if (muted) CommandButton.ICON_VOLUME_OFF else CommandButton.ICON_VOLUME_UP)
        .setDisplayName(if (muted) "Unmute" else "Mute")
        .setSessionCommand(muteCommand)
        .setSlots(CommandButton.SLOT_FORWARD)
        .build()

    private val callback = object : MediaLibrarySession.Callback {
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

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Yip"), params))

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String) =
            Futures.immediateFuture(
                when (mediaId) {
                    ROOT -> LibraryResult.ofItem(folder(ROOT, "Yip"), null)
                    FAVOURITES -> LibraryResult.ofItem(folder(FAVOURITES, "Favourites"), null)
                    LIVE -> LibraryResult.ofItem(folder(LIVE, "Live"), null)
                    else -> known[mediaId.removePrefix(LIVE_PREFIX)]?.let { LibraryResult.ofItem(item(it), null) }
                        ?: LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE)
                },
            )

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = when (parentId) {
            ROOT -> Futures.immediateFuture(LibraryResult.ofItemList(listOf(folder(FAVOURITES, "Favourites"), folder(LIVE, "Live")), params))
            FAVOURITES, LIVE -> scope.future {
                val live = liveNow()
                val pins = settings.settings.value.notifications.favourites.map { it.lowercase() }.toSet()
                val list = if (parentId == FAVOURITES) live.filter { it.userLogin.lowercase() in pins } else live
                LibraryResult.ofItemList(list.drop(page * pageSize).take(pageSize).map(::item), params)
            }
            else -> Futures.immediateFuture(LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE))
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?) =
            scope.future {
                session.notifySearchResultChanged(browser, query, matches(query).size, params)
                LibraryResult.ofVoid()
            }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?,
        ) = scope.future { LibraryResult.ofItemList(matches(query).drop(page * pageSize).take(pageSize).map(::item), params) }

        // Items from the tree, or a voice search ("play xqc on Yip"). The player wrapper does the actual start.
        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>) =
            scope.future {
                mediaItems.mapNotNull { m ->
                    val login = m.mediaId.takeIf { it.startsWith(LIVE_PREFIX) }?.removePrefix(LIVE_PREFIX)
                        ?: m.requestMetadata.searchQuery?.let { q -> matches(q).firstOrNull()?.userLogin }
                        ?: return@mapNotNull null
                    m.buildUpon().setMediaId(LIVE_PREFIX + login).setUri("$AUTO_SCHEME://live/$login".toUri()).build()
                }.toMutableList()
            }
    }

    /** Runs [block] on the service scope as a ListenableFuture for the session callbacks. */
    private fun <T> CoroutineScope.future(block: suspend () -> T): ListenableFuture<T> {
        val f = com.google.common.util.concurrent.SettableFuture.create<T>()
        launch { runCatching { block() }.onSuccess { f.set(it) }.onFailure { f.setException(it) } }
        return f
    }

    private suspend fun liveNow(): List<LiveStream> {
        val live = attempt { twitch.followedLive() }.getOrNull().orEmpty()
        live.forEach { known[it.userLogin.lowercase()] = it }
        return live
    }

    private suspend fun matches(query: String): List<LiveStream> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return liveNow().filter { it.userLogin.lowercase().contains(q) || it.userName.lowercase().contains(q) || it.gameName?.lowercase()?.contains(q) == true }
            .sortedByDescending { it.userLogin.lowercase() == q || it.userName.lowercase() == q }
    }

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build())
        .build()

    private fun item(s: LiveStream) = MediaItem.Builder()
        .setMediaId(LIVE_PREFIX + s.userLogin.lowercase())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(s.userName)
                .setArtist(s.gameName ?: s.title)
                .setSubtitle(s.title)
                .setArtworkUri((s.profileImageUrl ?: s.thumbnail(320, 180)).toUri())
                .setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                .build(),
        )
        .build()

    /** Starts streams picked in Android Auto through the controller instead of loading the placeholder URI. */
    private fun wrap(p: Player): Player = object : ForwardingPlayer(p) {
        override fun setMediaItems(mediaItems: MutableList<MediaItem>) = route(mediaItems) { super.setMediaItems(mediaItems) }
        override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) = route(mediaItems) { super.setMediaItems(mediaItems, resetPosition) }
        override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) = route(mediaItems) { super.setMediaItems(mediaItems, startIndex, startPositionMs) }
        override fun setMediaItem(mediaItem: MediaItem) = route(listOf(mediaItem)) { super.setMediaItem(mediaItem) }
        override fun addMediaItems(mediaItems: MutableList<MediaItem>) = route(mediaItems) { super.addMediaItems(mediaItems) }
        // Ignore prepare/play from the session while the controller is resolving; it starts playback itself.
    }

    private inline fun route(items: List<MediaItem>, fallback: () -> Unit) {
        val login = items.firstOrNull()?.localConfiguration?.uri?.takeIf { it.scheme == AUTO_SCHEME }?.lastPathSegment
        if (login == null) { fallback(); return }
        val s = known[login.lowercase()]
        controller.play(
            PlaybackSource.Live(
                channelId = s?.userId ?: login, channelLogin = login, channelName = s?.userName ?: login,
                title = s?.title.orEmpty(), thumbnailUrl = s?.thumbnail(), category = s?.gameName,
            ),
            audioOnly = true,
        )
    }

    override fun onCreate() {
        super.onCreate()
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val s = MediaLibrarySession.Builder(this, wrap(controller.player), callback)
            .setMediaButtonPreferences(listOf(muteButton(controller.state.value.muted)))
            .apply { launch?.let { setSessionActivity(PendingIntent.getActivity(this@PlaybackService, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)) } }
            .build()
        session = s
        // Without this the notification only appears once some other app connects to the session.
        addSession(s)
        unwatch = controller.watchPlayer { p -> session?.player = wrap(p) }
        scope.launch {
            controller.state.map { it.muted }.distinctUntilChanged().collect { muted ->
                session?.setMediaButtonPreferences(listOf(muteButton(muted)))
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** Swiping Yip away from recents closes it fully: playback stops and the notification goes. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        controller.stop()
        session?.player?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        const val ROOT = "root"
        const val FAVOURITES = "favourites"
        const val LIVE = "live"
        const val LIVE_PREFIX = "live:"
        const val AUTO_SCHEME = "yip-auto"
    }
}
