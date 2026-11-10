package dev.yipchat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.util.UnstableApi
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
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import dev.yipchat.core.common.AppScope
import dev.yipchat.core.common.LogBuffer
import dev.yipchat.core.data.account.AccountRepository
import dev.yipchat.core.data.chat.WhisperRepository
import dev.yipchat.core.data.live.LiveSnapshot
import dev.yipchat.core.data.settings.SettingsRepository
import dev.yipchat.core.network.config.RemoteConfigSource
import dev.yipchat.core.playback.DataUsage
import dev.yipchat.core.playback.PlaybackController
import dev.yipchat.live.refreshLiveSurfaces
import dev.yipchat.work.LiveNotificationWorker
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber

@androidx.annotation.OptIn(UnstableApi::class)
@HiltAndroidApp
class YipApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    // Lazy so building the HTTP stack doesn't block the first frame; warmed on IO in onCreate.
    @Inject lateinit var okHttp: Lazy<OkHttpClient>
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var remoteConfig: Lazy<RemoteConfigSource>
    @Inject lateinit var accounts: Lazy<AccountRepository>
    @Inject @AppScope lateinit var scope: CoroutineScope
    @Inject lateinit var liveSnapshot: Lazy<LiveSnapshot>
    @Inject lateinit var whispers: Lazy<WhisperRepository>
    @Inject lateinit var playback: Lazy<PlaybackController>
    @Inject lateinit var dataUsage: Lazy<DataUsage>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Timber.plant(LogBuffer)
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        createChannels()
        scope.launch(Dispatchers.IO) { okHttp.get() }
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
                delay(60 * 60 * 1000L)
                if (accounts.get().userId() != null) accounts.get().validateActive()
            }
        }
        scope.launch(Dispatchers.Main) { dataUsage.get().start() }
        // Keep the widget and Quick Settings tile in step with what the app last saw.
        scope.launch {
            liveSnapshot.get().data.drop(1).collect { refreshLiveSurfaces(this@YipApplication) }
        }
        // Whispers arrive while Yip is on screen or playing in the background.
        // Lifecycle observers must be added on the main thread.
        scope.launch(Dispatchers.Main) {
            val visible = MutableStateFlow(false)
            ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, _ ->
                visible.value = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            })
            combine(visible, playback.get().state.map { it.playing }) { v, p -> v || p }
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
