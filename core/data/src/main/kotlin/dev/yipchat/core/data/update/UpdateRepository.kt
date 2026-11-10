package dev.yipchat.core.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yipchat.core.common.IoDispatcher
import dev.yipchat.core.common.attempt
import dev.yipchat.core.data.settings.SettingsRepository
import dev.yipchat.core.network.BuildConfig
import dev.yipchat.core.network.update.AppRelease
import dev.yipchat.core.network.update.GitHubReleasesApi
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    /** [manual] is true when the user asked; skipped versions still show then. */
    data class Available(val release: AppRelease, val manual: Boolean) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float?) : UpdateState
    data class NeedsPermission(val release: AppRelease, val file: File) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState
}

/** Checks GitHub releases and installs the APK. Android only accepts it if it's signed with the same key. */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: GitHubReleasesApi,
    private val okHttp: OkHttpClient,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val installedVersion: String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /** Debug and staging builds have their own package, so a release APK can't replace them. */
    val unsupportedReason: String? = when {
        BuildConfig.UPDATE_REPO.isBlank() -> "This build has no update source."
        context.packageName != RELEASE_PACKAGE -> "Updates are for release builds. This is a ${context.packageName.substringAfterLast('.')} build."
        else -> null
    }

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** Release notes for the version just installed, shown once after an update. */
    private val _whatsNew = MutableStateFlow<WhatsNew?>(null)
    val whatsNew = _whatsNew.asStateFlow()
    fun dismissWhatsNew() { _whatsNew.value = null }

    private suspend fun loadWhatsNew() {
        val seen = prefs.getString("last_seen_version", null)
        prefs.edit { putString("last_seen_version", installedVersion) }
        // First install or same version: nothing to show.
        if (seen == null || seen == installedVersion || BuildConfig.UPDATE_REPO.isBlank()) return
        val (title, notes) = attempt { api.notesFor(BuildConfig.UPDATE_REPO, installedVersion) }.getOrNull() ?: return
        if (notes.isNotBlank()) _whatsNew.value = WhatsNew(installedVersion, title, notes)
    }

    /** Automatic check, at most every few hours. */
    suspend fun checkInBackground() {
        loadWhatsNew()
        val cfg = settings.current().updates
        if (unsupportedReason != null || !cfg.autoCheck) return
        val last = prefs.getLong("last_check", 0)
        if (System.currentTimeMillis() - last < AUTO_INTERVAL_MS) return
        check(manual = false)
    }

    suspend fun check(manual: Boolean) {
        unsupportedReason?.let { if (manual) _state.value = UpdateState.Failed(it); return }
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        _state.value = UpdateState.Checking
        val cfg = settings.current().updates
        attempt { api.latest(BuildConfig.UPDATE_REPO, cfg.prereleases) }
            .onSuccess { release ->
                prefs.edit { putLong("last_check", System.currentTimeMillis()) }
                val newer = release != null && GitHubReleasesApi.compareVersions(release.version, installedVersion) > 0
                _state.value = when {
                    !newer -> if (manual) UpdateState.UpToDate else UpdateState.Idle
                    !manual && release.version == cfg.skippedVersion -> UpdateState.Idle
                    else -> UpdateState.Available(release, manual)
                }
            }
            .onFailure { e ->
                Timber.w(e, "Update check failed")
                _state.value = if (manual) UpdateState.Failed("Couldn't check for updates (${e.message}).") else UpdateState.Idle
            }
    }

    suspend fun skip(release: AppRelease) {
        settings.update { it.copy(updates = it.updates.copy(skippedVersion = release.version)) }
        _state.value = UpdateState.Idle
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading && _state.value !is UpdateState.Installing) _state.value = UpdateState.Idle
    }

    suspend fun downloadAndInstall(release: AppRelease) {
        _state.value = UpdateState.Downloading(release, null)
        val file = attempt { download(release) }.getOrElse { e ->
            _state.value = UpdateState.Failed("Download failed: ${e.message}", release)
            return
        }
        install(release, file)
    }

    /** Called again after the user grants "Install unknown apps". */
    fun install(release: AppRelease, file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release, file)
            return
        }
        _state.value = UpdateState.Installing(release)
        attempt {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("yip.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java)
                val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        }.onFailure { e -> _state.value = UpdateState.Failed("Couldn't start install: ${e.message}", release) }
    }

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    internal fun onInstallResult(status: Int, message: String?) {
        val release = (_state.value as? UpdateState.Installing)?.release
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle // the app restarts on the new version
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Failed("This update is signed differently from the installed app, so Android refused it.", release)
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Failed("Not enough storage to install the update.", release)
            else -> UpdateState.Failed("Install failed${message?.let { ": $it" }.orEmpty()}", release)
        }
    }

    private suspend fun download(release: AppRelease): File = withContext(io) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only ever keep the latest
        val expected = release.sha256 ?: release.sha256Url?.let { api.text(it) }?.trim()?.substringBefore(' ')?.lowercase()
        val target = File(dir, "Yip-${release.version.filter { it.isLetterOrDigit() || it in ".-" }}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        okHttp.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { res ->
            check(res.isSuccessful) { "HTTP ${res.code}" }
            val body = checkNotNull(res.body)
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
            var read = 0L
            var lastPct = -1
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        val pct = if (total > 0) (read * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            _state.value = UpdateState.Downloading(release, if (pct >= 0) pct / 100f else null)
                        }
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expected != null && expected.isNotEmpty() && expected != actual) {
            target.delete()
            error("checksum didn't match, so the file was discarded")
        }
        target
    }

    private companion object {
        const val RELEASE_PACKAGE = "dev.yipchat"
        const val AUTO_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}

data class WhatsNew(val version: String, val title: String, val notes: String)
