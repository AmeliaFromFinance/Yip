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
    @Inject lateinit var linkPreviews: lol.sinist3r.yip.core.data.chat.LinkPreviews
    private val linkPreviewLoader by lazy {
        object : lol.sinist3r.yip.core.designsystem.chat.LinkPreviewLoader {
            override fun cached(url: String) = linkPreviews.cached(url)
            override suspend fun load(url: String) = linkPreviews.load(url)
        }
    }

    /** Deep links handed to the nav graph (twitch.tv URLs, notification taps). */
    val deepLinks = MutableSharedFlow<android.net.Uri>(replay = 1, extraBufferCapacity = 4)
    private var inPip by mutableStateOf(false)
    private var watching = false
    private var autoPipAllowed = false
    private var fullscreenOn by mutableStateOf(false)
    var openSignInRequest: (() -> Unit)? = null
    /** Settings from a QR link or file, waiting for the user to confirm. */
    var pendingImport by mutableStateOf<lol.sinist3r.yip.core.model.AppSettings?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        lifecycleScope.launch { accounts.validateActive() }
        setContent {
            val s by settings.settings.collectAsStateWithLifecycle()
            val bedtime = lol.sinist3r.yip.core.designsystem.util.rememberBedtimeActive(s.general.bedtime)
            val appearance = if (bedtime && s.general.bedtime.oledTheme) s.appearance.copy(theme = lol.sinist3r.yip.core.model.ThemeMode.BLACK) else s.appearance
            YipTheme(appearance) {
                CompositionLocalProvider(
                    LocalWatchHost provides this,
                    lol.sinist3r.yip.core.designsystem.chat.LocalLinkPreviews provides linkPreviewLoader,
                ) {
                    YipApp(activity = this, settings = s, inPip = inPip)
                    pendingImport?.let { incoming ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { pendingImport = null },
                            title = { androidx.compose.material3.Text("Import settings?") },
                            text = { androidx.compose.material3.Text("This replaces your current settings, filters and favourites with the ones you received. Accounts stay as they are.") },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    pendingImport = null
                                    lifecycleScope.launch { settings.apply(incoming) }
                                }) { androidx.compose.material3.Text("Import") }
                            },
                            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingImport = null }) { androidx.compose.material3.Text("Cancel") } },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.type == "application/json" && (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND)) {
            val file = intent.data ?: androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java) ?: return
            lifecycleScope.launch {
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { contentResolver.openInputStream(file)?.use { it.readBytes().decodeToString() } }.getOrNull()
                }
                pendingImport = text?.let { runCatching { lol.sinist3r.yip.core.datastore.SettingsJson.import(it) }.getOrNull() }
                if (pendingImport == null) Timber.w("Opened file isn't a Yip settings export")
            }
            return
        }
        val uri = intent.data ?: return
        if (uri.host == "yip.sinist3r.lol" && uri.path == "/import") {
            // Fragment from the QR link; query from the fallback page.
            val payload = uri.fragment ?: uri.encodedQuery ?: return
            pendingImport = runCatching { settings.readTransfer(payload) }.onFailure { Timber.w(it, "Bad settings link") }.getOrNull()
            return
        }
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
        // Drop fullscreen on the way in, so expanding goes straight back to the normal screen.
        if (fullscreenOn) setFullscreen(false)
    }

    override fun onStop() {
        super.onStop()
        // Coming back from the notification or recents should land on the normal screen. Screen-off keeps fullscreen.
        val screenOn = getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
        if (!inPip && !isChangingConfigurations && screenOn && fullscreenOn) setFullscreen(false)
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

    override fun isFullscreenRequested(): Boolean = fullscreenOn

    override fun isFullscreen(): Boolean = !inPip && (fullscreenOn || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)

    override fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    override fun currentBrightness(): Float = window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f

    override fun openSignIn() { openSignInRequest?.invoke() }

    // endregion
}
