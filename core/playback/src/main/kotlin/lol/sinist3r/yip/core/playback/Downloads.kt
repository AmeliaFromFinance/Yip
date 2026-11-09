package lol.sinist3r.yip.core.playback

import android.app.Notification
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** What we show for a download; stored inside the Media3 request so it survives restarts. */
@Serializable
data class DownloadMeta(
    val kind: String, // VOD or CLIP
    val contentId: String,
    val title: String,
    val channelLogin: String,
    val channelName: String,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long = 0,
    val quality: String = "",
)

enum class DownloadStatus { QUEUED, DOWNLOADING, DONE, FAILED, PAUSED }

data class DownloadItem(val id: String, val meta: DownloadMeta, val status: DownloadStatus, val percent: Int, val bytes: Long)

/** Offline VODs and clips, kept in app storage until removed. */
@UnstableApi
@Singleton
class Downloads @Inject constructor(@ApplicationContext private val context: Context, okHttp: OkHttpClient) {
    private val db by lazy { StandaloneDatabaseProvider(context) }
    private val cache by lazy { SimpleCache(File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads"), NoOpCacheEvictor(), db) }
    private val http = OkHttpDataSource.Factory(okHttp).setUserAgent("Mozilla/5.0 (Linux; Android) Yip")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var created = false

    /** Opened on first use (a download or an offline check), never at app start. */
    val manager: DownloadManager by lazy {
        created = true
        DownloadManager(context, db, cache, http, Executors.newFixedThreadPool(2)).apply {
            maxParallelDownloads = 2
            addListener(object : DownloadManager.Listener {
                override fun onInitialized(downloadManager: DownloadManager) = refresh()
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) = refresh()
                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh()
            })
        }
    }

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    /** Reading this opens the download store (off the main thread). */
    val items: StateFlow<List<DownloadItem>> get() {
        if (!created) scope.launch(Dispatchers.IO) { manager; refresh() }
        return _items.asStateFlow()
    }

    init {
        // Progress isn't pushed by Media3; tick while anything is running.
        scope.launch {
            while (true) {
                if (created && manager.isInitialized && manager.currentDownloads.isNotEmpty()) refresh()
                delay(1_000)
            }
        }
    }

    private fun refresh() {
        val out = mutableListOf<DownloadItem>()
        manager.downloadIndex.getDownloads().use { c ->
            while (c.moveToNext()) {
                val d = c.download
                val meta = runCatching { json.decodeFromString(DownloadMeta.serializer(), d.request.data.decodeToString()) }.getOrNull() ?: continue
                out += DownloadItem(d.request.id, meta, d.status(), d.percentDownloaded.takeIf { it >= 0 }?.toInt() ?: 0, d.bytesDownloaded)
            }
        }
        _items.value = out.sortedByDescending { it.status != DownloadStatus.DONE }
    }

    private fun Download.status() = when (state) {
        Download.STATE_COMPLETED -> DownloadStatus.DONE
        Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
        Download.STATE_FAILED -> DownloadStatus.FAILED
        Download.STATE_STOPPED -> DownloadStatus.PAUSED
        else -> DownloadStatus.QUEUED
    }

    fun id(kind: String, contentId: String) = "$kind:$contentId"

    /** [url] is a single-quality HLS playlist for VODs, or the MP4 for clips. */
    fun start(url: String, meta: DownloadMeta) {
        val request = DownloadRequest.Builder(id(meta.kind, meta.contentId), url.toUri())
            .setMimeType(if (meta.kind == "VOD") MimeTypes.APPLICATION_M3U8 else null)
            .setData(json.encodeToString(DownloadMeta.serializer(), meta).encodeToByteArray())
            .build()
        DownloadService.sendAddDownload(context, YipDownloadService::class.java, request, false)
    }

    fun remove(id: String) = DownloadService.sendRemoveDownload(context, YipDownloadService::class.java, id, false)

    /** Finished download for this item, if any. */
    suspend fun completed(kind: String, contentId: String): Download? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching { manager.downloadIndex.getDownload(id(kind, contentId)) }.getOrNull()?.takeIf { it.state == Download.STATE_COMPLETED }
    }

    /** Plays from storage only; no network. */
    fun offlineSource(download: Download, meta: MediaMetadata): MediaSource {
        val read = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)
        val item = download.request.toMediaItem().buildUpon().setMediaMetadata(meta).build()
        return if (download.request.mimeType == MimeTypes.APPLICATION_M3U8) HlsMediaSource.Factory(read).createMediaSource(item)
        else ProgressiveMediaSource.Factory(read).createMediaSource(item)
    }

    internal fun notification(service: DownloadService, downloads: List<Download>, notMet: Int): Notification =
        DownloadNotificationHelper(service, CHANNEL_ID).buildProgressNotification(
            service, android.R.drawable.stat_sys_download, null,
            downloads.firstOrNull()?.request?.data?.let { runCatching { json.decodeFromString(DownloadMeta.serializer(), it.decodeToString()).title }.getOrNull() },
            downloads, notMet,
        )

    companion object { const val CHANNEL_ID = "downloads" }
}

@UnstableApi
@AndroidEntryPoint
class YipDownloadService : DownloadService(4201, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, Downloads.CHANNEL_ID, R.string.downloads_channel, 0) {
    @Inject lateinit var downloads: Downloads
    override fun getDownloadManager(): DownloadManager = downloads.manager
    override fun getScheduler(): Scheduler? = null
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: @Requirements.RequirementFlags Int): Notification =
        this.downloads.notification(this, downloads, notMetRequirements)
}

