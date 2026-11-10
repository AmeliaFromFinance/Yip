package dev.yipchat.feature.watch

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import dev.yipchat.core.designsystem.theme.YipTheme
import dev.yipchat.core.model.PlayerSettings
import dev.yipchat.core.playback.PlaybackUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** One-finger vertical swipes must reach brightness/volume (pinch-to-zoom used to swallow them). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xxhdpi")
class PlayerGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun swipeUpOnRightRaisesVolume() {
        val context: Context = RuntimeEnvironment.getApplication()
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
        val player = ExoPlayer.Builder(context).build()
        compose.setContent {
            YipTheme {
                PlayerArea(
                    player = player, state = PlaybackUiState(), settings = PlayerSettings(pinchToZoom = true),
                    title = "t", subtitle = "s", isLive = true, durationSeconds = 0, chapters = emptyList(),
                    inPip = false, showAdBanner = false, onBack = {}, onQuality = {}, onRetry = {},
                    modifier = Modifier.width(400.dp).height(225.dp),
                )
            }
        }
        compose.onRoot().performTouchInput {
            swipe(start = Offset(width * 0.8f, height * 0.85f), end = Offset(width * 0.8f, height * 0.1f), durationMillis = 400)
        }
        compose.waitForIdle()
        val v = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        player.release()
        assertTrue("volume stayed at $v", v > 2)
    }
}
