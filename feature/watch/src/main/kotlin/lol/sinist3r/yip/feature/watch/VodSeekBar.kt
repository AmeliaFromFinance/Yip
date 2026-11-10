package lol.sinist3r.yip.feature.watch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size as CoilSize
import coil3.toBitmap
import lol.sinist3r.yip.core.common.secondsClock
import lol.sinist3r.yip.core.model.VideoExtras

private val MutedColor = Color(0xFFFFB300)
private const val PREVIEW_WIDTH_DP = 160

/** VOD seek bar with scrub thumbnails and muted parts marked in amber. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VodSeekBar(positionMs: Long, durationSeconds: Long, extras: VideoExtras, onSeek: (Long) -> Unit, modifier: Modifier = Modifier, onScrub: (Boolean) -> Unit = {}) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val fraction = if (dragging) dragValue else (positionMs / 1000f / durationSeconds).coerceIn(0f, 1f)
    val sprites = rememberSprites(extras, load = dragging)
    LaunchedEffect(dragging) { onScrub(dragging) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = maxWidth
        Column {
            if (dragging) {
                val seconds = (dragValue * durationSeconds).toLong()
                val frame = extras.storyboard?.frameAt(seconds)
                val bitmap = frame?.let { sprites[it.imageUrl] }
                val previewH = extras.storyboard?.let { PREVIEW_WIDTH_DP * it.height / it.width.coerceAtLeast(1) } ?: 0
                val x = (width * dragValue - (PREVIEW_WIDTH_DP / 2).dp).coerceIn(0.dp, (width - PREVIEW_WIDTH_DP.dp).coerceAtLeast(0.dp))
                Column(Modifier.offset(x = x), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (bitmap != null && frame != null) {
                        val sb = extras.storyboard!!
                        Canvas(
                            Modifier.size(PREVIEW_WIDTH_DP.dp, previewH.dp).clip(RoundedCornerShape(6.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(6.dp)),
                        ) {
                            drawImage(bitmap, srcOffset = IntOffset(frame.x, frame.y), srcSize = IntSize(sb.width, sb.height), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                        }
                    }
                    val muted = extras.muted.any { seconds in it.offsetSeconds until it.endSeconds }
                    Text(
                        seconds.secondsClock() + if (muted) "  · muted" else "",
                        color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Slider(
                value = fraction,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { onSeek((dragValue * durationSeconds * 1000).toLong()); dragging = false },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                track = { state ->
                    Box {
                        SliderDefaults.Track(
                            sliderState = state,
                            colors = SliderDefaults.colors(activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                            thumbTrackGapSize = 0.dp, drawStopIndicator = null,
                        )
                        if (extras.muted.isNotEmpty() && durationSeconds > 0) {
                            val h = with(LocalDensity.current) { 4.dp.toPx() }
                            Canvas(Modifier.matchParentSize()) {
                                extras.muted.forEach { m ->
                                    val start = size.width * m.offsetSeconds / durationSeconds
                                    val end = size.width * m.endSeconds.coerceAtMost(durationSeconds.toInt()) / durationSeconds
                                    drawRect(MutedColor, topLeft = Offset(start, (size.height - h) / 2), size = Size((end - start).coerceAtLeast(2f), h))
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

/** Loads storyboard sprites the first time the user scrubs. */
@Composable
private fun rememberSprites(extras: VideoExtras, load: Boolean): Map<String, ImageBitmap> {
    val context = LocalContext.current
    val sprites = remember(extras.storyboard) { mutableStateMapOf<String, ImageBitmap>() }
    var started by remember(extras.storyboard) { mutableStateOf(false) }
    if (load) started = true
    LaunchedEffect(extras.storyboard, started) {
        if (!started) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        extras.storyboard?.images?.forEach { url ->
            if (url in sprites) return@forEach
            val r = loader.execute(ImageRequest.Builder(context).data(url).size(CoilSize.ORIGINAL).build())
            (r as? SuccessResult)?.image?.toBitmap()?.let { sprites[url] = it.asImageBitmap() }
        }
    }
    return sprites
}
