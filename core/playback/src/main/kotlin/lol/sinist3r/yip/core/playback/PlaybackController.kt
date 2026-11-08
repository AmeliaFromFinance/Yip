package lol.sinist3r.yip.core.playback

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lol.sinist3r.yip.core.common.AppException
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.data.history.HistoryRepository
import lol.sinist3r.yip.core.data.playback.PlaybackRepository
import lol.sinist3r.yip.core.data.playback.PlaylistRewriter
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.database.ChannelPrefsDao
import lol.sinist3r.yip.core.database.ChannelPrefsEntity
import lol.sinist3r.yip.core.database.HistoryKind
import lol.sinist3r.yip.core.model.QualityPreference
import lol.sinist3r.yip.core.model.StreamQuality
import timber.log.Timber

sealed interface PlaybackSource {
    val channelId: String
    val channelLogin: String
    val channelName: String
    val title: String
    val thumbnailUrl: String?

    data class Live(
        override val channelId: String, override val channelLogin: String, override val channelName: String,
        override val title: String, override val thumbnailUrl: String?, val category: String?,
    ) : PlaybackSource

    data class Vod(
        val videoId: String, override val channelId: String, override val channelLogin: String, override val channelName: String,
        override val title: String, override val thumbnailUrl: String?, val durationSeconds: Long, val startAtSeconds: Long? = null,
    ) : PlaybackSource

    data class ClipSrc(
        val slug: String, override val channelId: String, override val channelLogin: String, override val channelName: String,
        override val title: String, override val thumbnailUrl: String?, val vodId: String? = null, val vodOffset: Int? = null,
    ) : PlaybackSource
}

data class PlaybackUiState(
    val source: PlaybackSource? = null,
    val loading: Boolean = false,
    val playing: Boolean = false,
    val qualities: List<StreamQuality> = emptyList(),
    val selected: StreamQuality? = null,
    val auto: Boolean = true,
    val audioOnly: Boolean = false,
    val error: String? = null,
    val liveLatencyMs: Long? = null,
    val adBreak: Boolean = false,
    val viaProxy: Boolean = false,
    val sleepAtMillis: Long? = null,
    val speed: Float = 1f,
)

/**
 * Owns the single main player. The UI renders [player] directly; [PlaybackService] attaches a
 * MediaSession to it so playback survives backgrounding and shows system controls.
 */
@UnstableApi
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val factory: PlayerFactory,
    private val repo: PlaybackRepository,
    private val rewriter: PlaylistRewriter,
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    private val channelPrefs: ChannelPrefsDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var _player: ExoPlayer? = null
    private var masterUrl: String? = null
    private var sleepJob: Job? = null
    private var tickJob: Job? = null
    private var volumeBeforeAd = 1f

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    val player: ExoPlayer
        get() = _player ?: factory.create(settings.settings.value.player).also {
            _player = it
            it.addListener(listener)
        }

    val hasMedia: Boolean get() = _player?.currentMediaItem != null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = _state.update { it.copy(playing = isPlaying) }
        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update { it.copy(loading = playbackState == Player.STATE_BUFFERING) }
        }
        override fun onPlayerError(error: PlaybackException) {
            Timber.w(error, "Player error")
            val behind = error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
            if (behind) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            _state.update { it.copy(error = humanError(error), loading = false) }
        }
    }

    fun play(source: PlaybackSource) {
        val current = _state.value.source
        if (current == source && hasMedia && _state.value.error == null) {
            player.play()
            return
        }
        recordProgress()
        _state.value = PlaybackUiState(source = source, loading = true)
        scope.launch {
            val result = attempt {
                when (source) {
                    is PlaybackSource.Live -> {
                        val r = repo.resolveLive(source.channelLogin)
                        masterUrl = r.masterPlaylistUrl
                        _state.update { it.copy(qualities = r.qualities, viaProxy = r.viaProxy) }
                        startWith(pickInitial(source.channelId, r.qualities), live = true)
                    }
                    is PlaybackSource.Vod -> {
                        val r = repo.resolveVod(source.videoId)
                        masterUrl = r.masterPlaylistUrl
                        _state.update { it.copy(qualities = r.qualities) }
                        startWith(pickInitial(source.channelId, r.qualities), live = false)
                        val resume = source.startAtSeconds ?: if (settings.current().player.resumeVods) history.resumePosition(source.videoId) else null
                        resume?.let { player.seekTo(it * 1000) }
                    }
                    is PlaybackSource.ClipSrc -> {
                        val qualities = repo.resolveClip(source.slug)
                        if (qualities.isEmpty()) throw AppException(AppException.Kind.NOT_FOUND, "Clip unavailable")
                        _state.update { it.copy(qualities = qualities, auto = false) }
                        val q = pickInitial(source.channelId, qualities) ?: qualities.first()
                        player.setMediaSource(factory.progressiveSource(q.url, metadata(source)))
                        player.prepare()
                        player.play()
                        _state.update { it.copy(selected = q) }
                    }
                }
            }
            result.onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Couldn't start playback") } }
            result.onSuccess { startService(); startTicker() }
        }
    }

    private suspend fun pickInitial(channelId: String, qualities: List<StreamQuality>): StreamQuality? {
        val s = settings.current().player
        if (s.rememberQualityPerChannel) {
            channelPrefs.get(channelId)?.qualityId?.let { id -> qualities.firstOrNull { it.id == id }?.let { return it } }
        }
        val wifi = isOnWifi()
        val base = if (wifi) s.qualityWifi else s.qualityMobile
        // Data saver caps mobile data at 480p.
        val pref = if (!wifi && settings.current().data.dataSaver && (base == QualityPreference.AUTO || base == QualityPreference.SOURCE || (base.height ?: 0) > 480)) QualityPreference.P480 else base
        return when (pref) {
            QualityPreference.AUTO -> null
            QualityPreference.AUDIO_ONLY -> qualities.firstOrNull { it.isAudioOnly }
            QualityPreference.SOURCE -> qualities.firstOrNull { !it.isAudioOnly }
            else -> qualities.filterNot { it.isAudioOnly }.firstOrNull { (it.height ?: 0) <= (pref.height ?: 0) } ?: qualities.lastOrNull { !it.isAudioOnly }
        }
    }

    private fun startWith(quality: StreamQuality?, live: Boolean) {
        val src = _state.value.source ?: return
        val url = quality?.url ?: masterUrl ?: return
        val profile = settings.settings.value.player.bufferProfile
        player.setMediaSource(factory.hlsSource(url, live, profile, metadata(src)))
        player.prepare()
        if (settings.settings.value.player.startMuted) player.volume = 0f
        player.play()
        _state.update { it.copy(selected = quality, auto = quality == null, audioOnly = quality?.isAudioOnly == true, error = null) }
    }

    fun selectQuality(quality: StreamQuality?) {
        val src = _state.value.source ?: return
        val pos = player.currentPosition
        if (src is PlaybackSource.ClipSrc) {
            quality ?: return
            player.setMediaSource(factory.progressiveSource(quality.url, metadata(src)))
            player.prepare()
            player.seekTo(pos)
            player.play()
            _state.update { it.copy(selected = quality) }
            return
        }
        startWith(quality, live = src is PlaybackSource.Live)
        if (src is PlaybackSource.Vod) player.seekTo(pos)
        scope.launch {
            if (settings.current().player.rememberQualityPerChannel) {
                val prev = channelPrefs.get(src.channelId)
                channelPrefs.upsert((prev ?: ChannelPrefsEntity(src.channelId, null)).copy(qualityId = quality?.id))
            }
        }
    }

    fun toggleAudioOnly() {
        val s = _state.value
        if (s.audioOnly) selectQuality(null) else s.qualities.firstOrNull { it.isAudioOnly }?.let(::selectQuality)
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    fun retry() {
        val src = _state.value.source ?: return
        _state.value = PlaybackUiState()
        play(src)
    }

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null || minutes <= 0) {
            _state.update { it.copy(sleepAtMillis = null) }
            return
        }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepAtMillis = at) }
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            player.pause()
            _state.update { it.copy(sleepAtMillis = null) }
        }
    }

    fun stop() {
        recordProgress()
        tickJob?.cancel()
        sleepJob?.cancel()
        _player?.stop()
        _player?.clearMediaItems()
        _state.value = PlaybackUiState()
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    /** Rebuild the player after buffer/audio settings change. */
    fun recreate() {
        val src = _state.value.source
        _player?.removeListener(listener)
        _player?.release()
        _player = null
        src?.let { _state.value = PlaybackUiState(); play(it) }
    }

    private fun startService() {
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            var n = 0
            while (isActive) {
                delay(1000)
                val p = _player ?: continue
                val src = _state.value.source
                val url = p.currentMediaItem?.localConfiguration?.uri?.toString()
                val ad = url != null && rewriter.isAdBreak(url)
                val wasAd = _state.value.adBreak
                _state.update {
                    it.copy(
                        liveLatencyMs = if (src is PlaybackSource.Live) p.currentLiveOffset.takeIf { o -> o > 0 } else null,
                        adBreak = ad,
                    )
                }
                if (settings.settings.value.adBlock.muteDuringAds && ad != wasAd) {
                    if (ad) { volumeBeforeAd = p.volume; p.volume = 0f } else p.volume = volumeBeforeAd
                }
                if (++n % 15 == 0) recordProgress()
            }
        }
    }

    private fun recordProgress() {
        val src = _state.value.source ?: return
        val p = _player ?: return
        val pos = p.currentPosition / 1000
        scope.launch {
            when (src) {
                is PlaybackSource.Live -> history.record(HistoryKind.LIVE, src.channelId, src.channelId, src.channelLogin, src.channelName, src.title, src.thumbnailUrl, 0, 0)
                is PlaybackSource.Vod -> history.record(HistoryKind.VOD, src.videoId, src.channelId, src.channelLogin, src.channelName, src.title, src.thumbnailUrl, pos, src.durationSeconds)
                is PlaybackSource.ClipSrc -> Unit
            }
        }
    }

    private fun metadata(src: PlaybackSource) = MediaMetadata.Builder()
        .setTitle(src.title.ifBlank { src.channelName })
        .setArtist(src.channelName)
        .setArtworkUri(src.thumbnailUrl?.toUri())
        .setIsPlayable(true)
        .build()

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun humanError(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Network problem. Check your connection."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Stream ended or isn't available."
        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This device can't decode the stream. Try a lower quality or H.264 only."
        else -> "Playback error (${e.errorCodeName})"
    }
}
