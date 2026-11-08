package lol.sinist3r.yip.core.network.config

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.model.RemoteConfig
import lol.sinist3r.yip.core.network.BuildConfig
import timber.log.Timber

/**
 * Loads the signed playback config the server watcher publishes. The envelope is
 * `{"payload": base64(json), "sig": base64(ed25519)}`; unsigned or tampered files are ignored
 * and the last good copy (or the built-in defaults) stays in use.
 */
@Singleton
class RemoteConfigSource @Inject constructor(
    @ApplicationContext context: Context,
    private val http: HttpClient,
    private val json: Json,
) {
    @Serializable
    private data class Envelope(val payload: String, val sig: String, val kid: String? = null)

    private val cacheFile = File(context.filesDir, "remote_config.json")
    private val mutex = Mutex()
    private val state = MutableStateFlow(loadCached() ?: RemoteConfig())
    private var lastFetchMillis = 0L

    val config: StateFlow<RemoteConfig> = state.asStateFlow()

    var url: String = lol.sinist3r.yip.core.model.DataSettings.DEFAULT_CONFIG_URL

    fun current(): RemoteConfig = state.value

    /** Fetches a fresh copy if the last one is older than [maxAgeMillis]. Never throws. */
    suspend fun refresh(maxAgeMillis: Long = 60 * 60 * 1000L, force: Boolean = false): RemoteConfig = mutex.withLock {
        if (!force && System.currentTimeMillis() - lastFetchMillis < maxAgeMillis) return state.value
        attempt {
            val res = http.get(url) { expectSuccess = false }
            if (!res.status.isSuccess()) error("HTTP ${res.status.value}")
            val text = res.bodyAsText()
            val cfg = parse(text) ?: error("Signature check failed")
            if (cfg.generatedAt < state.value.generatedAt) error("Older than cached config")
            cacheFile.writeText(text)
            state.value = cfg
            lastFetchMillis = System.currentTimeMillis()
            cfg
        }.onFailure { Timber.w(it, "Remote config refresh failed") }.getOrDefault(state.value)
    }

    private fun parse(text: String): RemoteConfig? {
        val env = json.decodeFromString(Envelope.serializer(), text)
        val payload = Base64.getDecoder().decode(env.payload)
        val key = BuildConfig.CONFIG_PUBLIC_KEY
        if (key.isBlank()) {
            Timber.w("No config public key built in; refusing remote config")
            return null
        }
        if (!ConfigSignature.verify(key, payload, env.sig)) return null
        return json.decodeFromString(RemoteConfig.serializer(), payload.decodeToString())
    }

    private fun loadCached(): RemoteConfig? =
        attempt { cacheFile.takeIf { it.exists() }?.readText()?.let(::parse) }.getOrNull()
}
