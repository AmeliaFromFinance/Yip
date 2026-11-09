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
import kotlinx.coroutines.runBlocking
import lol.sinist3r.yip.core.common.AppScope
import lol.sinist3r.yip.core.common.LogBuffer
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.network.config.RemoteConfigSource
import lol.sinist3r.yip.work.LiveNotificationWorker
import okhttp3.OkHttpClient
import timber.log.Timber

@HiltAndroidApp
class YipApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var okHttp: OkHttpClient
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var remoteConfig: RemoteConfigSource
    @Inject lateinit var accounts: lol.sinist3r.yip.core.data.account.AccountRepository
    @Inject @AppScope lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Timber.plant(LogBuffer)
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        createChannels()
        scope.launch {
            settings.settings.map { it.data.verboseLogging }.distinctUntilChanged().collect { LogBuffer.verbose = it || BuildConfig.DEBUG }
        }
        scope.launch {
            settings.settings.map { it.data.remoteConfigUrl }.distinctUntilChanged().collect {
                remoteConfig.url = it
                remoteConfig.refresh(force = true)
            }
        }
        // Twitch asks apps to validate user tokens hourly.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60 * 60 * 1000L)
                if (accounts.userId() != null) accounts.validateActive()
            }
        }
        scope.launch {
            settings.settings.map { it.notifications }.distinctUntilChanged().collect { n ->
                LiveNotificationWorker.schedule(this@YipApplication, n.enabled, n.intervalMinutes)
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val cacheMb = runBlocking { settings.current().data.imageCacheMb }.coerceIn(64, 1024)
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttp }))
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
