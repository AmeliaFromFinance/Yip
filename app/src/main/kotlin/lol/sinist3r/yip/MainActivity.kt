package lol.sinist3r.yip

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import lol.sinist3r.yip.core.data.account.AccountRepository
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.designsystem.theme.YipTheme
import lol.sinist3r.yip.core.playback.PlaybackController
import lol.sinist3r.yip.feature.watch.LocalWatchHost
import lol.sinist3r.yip.feature.watch.WatchHost
import lol.sinist3r.yip.navigation.YipApp
import timber.log.Timber

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class MainActivity : ComponentActivity(), WatchHost {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var accounts: AccountRepository
    @Inject lateinit var playback: PlaybackController
    @Inject lateinit var follows: lol.sinist3r.yip.core.data.follow.FollowRepository

    /** Deep links handed to the nav graph (twitch.tv URLs, notification taps). */
    val deepLinks = MutableSharedFlow<android.net.Uri>(replay = 1, extraBufferCapacity = 4)
    private var inPip by mutableStateOf(false)
    private var watching = false
    private var autoPipAllowed = false
    private var fullscreenOn by mutableStateOf(false)
    var openSignInRequest: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        lifecycleScope.launch { accounts.validateActive() }
        setContent {
            val s by settings.settings.collectAsStateWithLifecycle()
            YipTheme(s.appearance) {
                CompositionLocalProvider(LocalWatchHost provides this) {
                    YipApp(activity = this, settings = s, inPip = inPip)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.host == "yip.sinist3r.lol" && uri.path == "/auth") {
            // App Link: token in the fragment. Fallback page: token moved into the query.
            val fragment = uri.fragment ?: uri.encodedQuery ?: return
            lifecycleScope.launch {
                val account = accounts.completeBrowserLogin(fragment)
                Timber.i("Browser sign-in %s", if (account != null) "succeeded" else "failed")
            }
            return
        }
        deepLinks.tryEmit(uri)
    }

    // region WatchHost

    override fun enterPip() {
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        enterPictureInPictureMode(pipParams())
    }

    private fun pipParams(): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { setAutoEnterEnabled(watching && autoPipAllowed); setSeamlessResizeEnabled(true) } }
        .build()

    override fun setWatching(active: Boolean, autoPip: Boolean, keepScreenOn: Boolean) {
        watching = active
        autoPipAllowed = autoPip
        if (keepScreenOn && active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching { setPictureInPictureParams(pipParams()) }
        if (!active && fullscreenOn) setFullscreen(false)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ handles this via setAutoEnterEnabled.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && watching && autoPipAllowed && playback.state.value.playing) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app without PiP and without background audio: pause.
        if (!inPip && !isChangingConfigurations && !settings.settings.value.player.backgroundAudio) playback.player.pause()
    }

    override fun setFullscreen(on: Boolean) {
        fullscreenOn = on
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun isFullscreen(): Boolean = fullscreenOn || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    override fun currentBrightness(): Float = window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f

    override fun openSignIn() { openSignInRequest?.invoke() }

    // endregion
}
