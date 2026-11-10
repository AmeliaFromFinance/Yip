package dev.yipchat.feature.settings

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import coil3.SingletonImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yipchat.core.common.attempt
import dev.yipchat.core.data.account.AccountRepository
import dev.yipchat.core.data.chat.EmoteStats
import dev.yipchat.core.data.cosmetics.CosmeticsRepository
import dev.yipchat.core.data.history.HistoryRepository
import dev.yipchat.core.data.search.RecentSearchRepository
import dev.yipchat.core.data.settings.SettingsRepository
import dev.yipchat.core.data.update.UpdateRepository
import dev.yipchat.core.model.AccountsState
import dev.yipchat.core.model.AppSettings
import dev.yipchat.core.network.config.RemoteConfigSource
import dev.yipchat.core.playback.PlaybackController
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
    private val accounts: AccountRepository,
    private val history: HistoryRepository,
    private val recentSearches: RecentSearchRepository,
    private val updates: UpdateRepository,
    private val cosmetics: CosmeticsRepository,
    private val remoteConfig: RemoteConfigSource,
    private val playback: PlaybackController,
    private val emoteStats: EmoteStats,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings
    val accountsState: StateFlow<AccountsState> = accounts.state
    val config = remoteConfig.config

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        val before = settings.value
        repo.update(transform)
        val after = settings.value
        if (before.thirdParty != after.thirdParty) cosmetics.refreshMerges()
        if (before.player.bufferProfile != after.player.bufferProfile ||
            before.player.pauseOnCall != after.player.pauseOnCall ||
            before.player.pauseOnHeadphonesUnplugged != after.player.pauseOnHeadphonesUnplugged
        ) playback.recreate()
    }

    fun action(id: String) = viewModelScope.launch {
        when (id) {
            "clear_cache" -> {
                withContext(Dispatchers.IO) {
                    SingletonImageLoader.get(context).let { it.memoryCache?.clear(); it.diskCache?.clear() }
                }
                _messages.emit("Image cache cleared")
            }
            "clear_history" -> { history.clear(); _messages.emit("Watch history cleared") }
            "clear_recent_searches" -> { recentSearches.clear(); _messages.emit("Recent searches cleared") }
            "reset_emote_stats" -> { emoteStats.reset(); _messages.emit("Emote stats reset") }
            "check_updates" -> updates.check(manual = true) // the app-wide dialog shows the result
            "reset" -> { repo.reset(); _messages.emit("Settings reset") }
            "refresh_config" -> {
                val cfg = remoteConfig.refresh(force = true)
                _messages.emit(if (cfg.generatedAt > 0) "Config updated (${cfg.hashes.size} operations)" else "Using built-in defaults (server unreachable or unsigned)")
            }
        }
    }

    /** QR link (null if too big) and full JSON for the transfer sheet. */
    suspend fun transfer(): Pair<String?, String> = repo.transferLink() to repo.exportJson()

    fun export(uri: Uri) = viewModelScope.launch {
        attempt {
            val text = repo.exportJson()
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.encodeToByteArray()) } }
        }.onSuccess { _messages.emit("Settings exported") }.onFailure { _messages.emit("Export failed: ${it.message}") }
    }

    fun import(uri: Uri) = viewModelScope.launch {
        attempt {
            val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } } ?: error("Empty file")
            repo.importJson(text)
        }.onSuccess { cosmetics.refreshMerges(); _messages.emit("Settings imported") }
            .onFailure { _messages.emit("That file isn't a Yip settings export") }
    }

    fun switchAccount(id: String) = viewModelScope.launch { accounts.switchTo(id); cosmetics.clear() }
    fun signOut(id: String) = viewModelScope.launch { accounts.signOut(id); cosmetics.clear(); _messages.emit("Signed out") }
    fun disconnectWeb() = viewModelScope.launch { accounts.disconnectWebToken(); _messages.emit("twitch.tv disconnected") }
}
