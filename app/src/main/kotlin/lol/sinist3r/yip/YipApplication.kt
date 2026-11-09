package lol.sinist3r.yip

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import lol.sinist3r.yip.core.common.AppScope
import lol.sinist3r.yip.core.common.LogBuffer
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.network.config.RemoteConfigSource
import lol.sinist3r.yip.work.LiveNotificationWorker
import okhttp3.OkHttpClient
import timber.log.Timber

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@HiltAndroidApp
class YipApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    // Lazy so building the HTTP stack doesn't block the first frame; warmed on IO in onCreate.
    @Inject lateinit var okHttp: dagger.Lazy<OkHttpClient>
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var remoteConfig: dagger.Lazy<RemoteConfigSource>
    @Inject lateinit var accounts: dagger.Lazy<lol.sinist3r.yip.core.data.account.AccountRepository>
    @Inject @AppScope lateinit var scope: CoroutineScope
    @Inject lateinit var whispers: dagger.Lazy<lol.sinist3r.yip.core.data.chat.WhisperRepository>
    @Inject lateinit var playback: dagger.Lazy<lol.sinist3r.yip.core.playback.PlaybackController>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Timber.plant(LogBuffer)
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        createChannels()
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { okHttp.get() }
        scope.launch {
            settings.settings.map { it.data.verboseLogging }.distinctUntilChanged().collect { LogBuffer.verbose = it || BuildConfig.DEBUG }
        }
        scope.launch {
            settings.settings.map { it.data.remoteConfigUrl }.distinctUntilChanged().collect {
                remoteConfig.get().url = it
                remoteConfig.get().refresh(force = true)
            }
        }
        // Twitch asks apps to validate user tokens hourly.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60 * 60 * 1000L)
                if (accounts.get().userId() != null) accounts.get().validateActive()
            }
        }
        // Whispers arrive while Yip is on screen or playing in the background.
        // Main thread: lifecycle observers can't be added from a background thread (crashed on launch).
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val visible = kotlinx.coroutines.flow.MutableStateFlow(false)
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, _ ->
                visible.value = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            })
            kotlinx.coroutines.flow.combine(visible, playback.get().state.map { it.playing }) { v, p -> v || p }
                .distinctUntilChanged()
                .collect { whispers.get().setActive(it) }
        }
        scope.launch {
            settings.settings.map { it.notifications }.distinctUntilChanged().collect { n ->
                LiveNotificationWorker.schedule(this@YipApplication, n.enabled, n.intervalMinutes)
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val cacheMb = settings.settings.value.data.imageCacheMb.coerceIn(64, 1024)
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttp.get() }))
                add(AnimatedImageDecoder.Factory())
                add(SvgDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(cacheMb * 1024L * 1024L).build() }
            .crossfade(false)
            .build()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, getString(R.string.channel_live), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.channel_live_desc)
            },
        )
    }

    companion object {
        const val CHANNEL_LIVE = "live"
    }
}
