package lol.sinist3r.yip.feature.watch

import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import kotlinx.coroutines.delay
import lol.sinist3r.yip.core.common.secondsClock
import lol.sinist3r.yip.core.designsystem.component.LivePill
import lol.sinist3r.yip.core.designsystem.component.Pill
import lol.sinist3r.yip.core.model.PlayerSettings
import lol.sinist3r.yip.core.model.VideoChapter
import lol.sinist3r.yip.core.playback.PlaybackUiState

/** Activity-level hooks the watch screen needs. Provided by the app. */
interface WatchHost {
    fun enterPip()
    fun setFullscreen(on: Boolean)
    fun isFullscreen(): Boolean
    fun setBrightness(value: Float)
    fun currentBrightness(): Float
    fun setWatching(active: Boolean, autoPip: Boolean, keepScreenOn: Boolean)
    fun openSignIn()
}

val LocalWatchHost = staticCompositionLocalOf<WatchHost?> { null }

@OptIn(UnstableApi::class)
@Composable
fun PlayerArea(
    player: Player,
    state: PlaybackUiState,
    settings: PlayerSettings,
    title: String,
    subtitle: String,
    isLive: Boolean,
    durationSeconds: Long,
    chapters: List<VideoChapter>,
    inPip: Boolean,
    showAdBanner: Boolean,
    onBack: () -> Unit,
    onQuality: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = LocalWatchHost.current
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var controls by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var gestureHint by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var muted by remember { mutableStateOf(player.volume == 0f) }
    val presentation = rememberPresentationState(player)

    LaunchedEffect(controls, state.playing) {
        if (controls && state.playing) {
            delay(3500)
            controls = false
        }
    }
    LaunchedEffect(gestureHint) { if (gestureHint != null) { delay(900); gestureHint = null } }
    // Only tick the seek bar while it's on screen; otherwise the whole player recomposed twice a second.
    LaunchedEffect(controls, isLive) {
        if (!controls || isLive) return@LaunchedEffect
        while (true) {
            position = player.currentPosition
            delay(500)
        }
    }

    Box(
        modifier.background(Color.Black)
            .pointerInput(isLive, settings.doubleTapSeekSeconds) {
                detectTapGestures(
                    onTap = { controls = !controls },
                    onDoubleTap = { o ->
                        if (!isLive) {
                            val forward = o.x > size.width / 2
                            if (forward) player.seekForward() else player.seekBack()
                            gestureHint = if (forward) "+${settings.doubleTapSeekSeconds}s" else "-${settings.doubleTapSeekSeconds}s"
                        } else {
                            zoomed = !zoomed
                        }
                    },
                )
            }
            .pointerInput(settings.swipeBrightness, settings.swipeVolume) {
                var acc = 0f
                detectVerticalDragGestures(onDragStart = { acc = 0f }) { change, drag ->
                    val left = change.position.x < size.width / 2
                    acc -= drag / size.height
                    if (left && settings.swipeBrightness && host != null) {
                        val b = (host.currentBrightness() + acc).coerceIn(0.02f, 1f)
                        host.setBrightness(b)
                        gestureHint = "Brightness ${(b * 100).toInt()}%"
                        acc = 0f
                    } else if (!left && settings.swipeVolume && audio != null) {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        if (kotlin.math.abs(acc) > 1f / max) {
                            val step = if (acc > 0) 1 else -1
                            val v = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + step).coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                            gestureHint = "Volume ${v * 100 / max}%"
                            acc = 0f
                        }
                    }
                }
            }
            .pointerInput(settings.pinchToZoom) {
                if (settings.pinchToZoom) detectTransformGestures { _, _, zoom, _ ->
                    if (zoom > 1.05f) zoomed = true else if (zoom < 0.95f) zoomed = false
                }
            },
    ) {
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            modifier = Modifier.align(Alignment.Center).resizeWithContentScale(
                if (zoomed) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                presentation.videoSizeDp,
            ),
        )
        if (presentation.coverSurface) Box(Modifier.fillMaxSize().background(Color.Black))

        if (state.audioOnly && !inPip) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Headphones, null, tint = Color.White, modifier = Modifier.size(48.dp))
                Text("Audio only", color = Color.White)
            }
        }
        if (state.loading && state.error == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)

        state.error?.let { err ->
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(err, color = Color.White)
                FilledTonalButton(onClick = onRetry) { Text("Retry") }
            }
        }

        if (state.adBreak && showAdBanner) {
            Pill(
                if (state.viaProxy) "Ad break (proxy)" else "Ad break · stream resumes after",
                Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            )
        }

        gestureHint?.let { Pill(it, Modifier.align(Alignment.Center)) }

        if (inPip) return@Box

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f))) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        Text(subtitle, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                    }
                    IconButton(onClick = { host?.enterPip() }) { Icon(Icons.Outlined.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
                    IconButton(onClick = onQuality) { Icon(Icons.Outlined.Settings, "Quality and options", tint = Color.White) }
                }
                IconButton(
                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                    modifier = Modifier.align(Alignment.Center).size(64.dp).background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape),
                ) {
                    Icon(if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (state.playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp)) {
                    if (!isLive && durationSeconds > 0) {
                        var dragging by remember { mutableStateOf(false) }
                        var dragValue by remember { mutableFloatStateOf(0f) }
                        val fraction = if (dragging) dragValue else (position / 1000f / durationSeconds).coerceIn(0f, 1f)
                        Slider(
                            value = fraction,
                            onValueChange = { dragging = true; dragValue = it },
                            onValueChangeFinished = { player.seekTo((dragValue * durationSeconds * 1000).toLong()); dragging = false },
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                        )
                        val chapter = chapters.lastOrNull { it.startSeconds <= position / 1000 }
                        chapter?.let { Text(it.title.ifBlank { it.gameName.orEmpty() }, color = Color.White, fontSize = 11.sp) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isLive) LivePill() else Text("${(position / 1000).secondsClock()} / ${durationSeconds.secondsClock()}", color = Color.White, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        if (isLive && settings.showLatency) state.liveLatencyMs?.let { Pill("%.1fs".format(it / 1000f)) }
                        Spacer(Modifier.width(6.dp))
                        if (state.selected != null) Pill(state.selected!!.label) else if (state.qualities.isNotEmpty()) Pill("Auto")
                        if (settings.showStatsOverlay) {
                            Spacer(Modifier.width(6.dp))
                            val fmt = (player as? androidx.media3.exoplayer.ExoPlayer)?.videoFormat
                            fmt?.let { Pill("${it.width}x${it.height} ${it.frameRate.toInt()}fps ${(it.bitrate / 1000).coerceAtLeast(0)}kbps") }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { muted = !muted; player.volume = if (muted) 0f else 1f }) {
                            Icon(if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, if (muted) "Unmute" else "Mute", tint = Color.White)
                        }
                        val full = host?.isFullscreen() == true
                        IconButton(onClick = { host?.setFullscreen(!full) }) {
                            Icon(if (full) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, if (full) "Exit fullscreen" else "Fullscreen", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}
