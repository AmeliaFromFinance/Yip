package dev.yipchat.core.data.chat

import android.util.LruCache
import dev.yipchat.core.common.AppScope
import dev.yipchat.core.common.IoDispatcher
import dev.yipchat.core.common.attempt
import dev.yipchat.core.data.settings.SettingsRepository
import dev.yipchat.core.network.cosmetics.PronounDef
import dev.yipchat.core.network.cosmetics.PronounsApi
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pronoun labels by login. The service has no batch lookup, so a few workers fetch chatters
 * one at a time; results (including "none set") are kept for an hour.
 */
@Singleton
class PronounRepository @Inject constructor(
    private val api: PronounsApi,
    private val settings: SettingsRepository,
    @AppScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private class Entry(val label: String?, val at: Long)

    private val cache = LruCache<String, Entry>(5000)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val queue = Channel<Pair<String, String>>(capacity = 500) // userId to login; drops when flooded
    private val defsMutex = Mutex()
    @Volatile private var defs: Map<String, PronounDef> = emptyMap()
    private val updated = MutableSharedFlow<Set<String>>(extraBufferCapacity = 64)

    /** Twitch user ids whose pronouns just loaded; open chats add the tag. */
    val pronounsUpdated: SharedFlow<Set<String>> = updated.asSharedFlow()

    init {
        repeat(WORKERS) { scope.launch(io) { for ((uid, login) in queue) lookup(uid, login) } }
    }

    fun cached(login: String): String? = cache.get(login.lowercase())?.label

    fun note(userId: String, login: String) {
        if (!settings.settings.value.chat.showPronouns || login.isEmpty()) return
        val key = login.lowercase()
        val e = cache.get(key)
        if (e != null && System.currentTimeMillis() - e.at < TTL_MS) return
        if (!inFlight.add(key)) return
        if (queue.trySend(userId to key).isFailure) inFlight.remove(key)
    }

    private suspend fun lookup(userId: String, login: String) {
        try {
            val d = ensureDefs() ?: return
            attempt { api.user(login) }
                .onSuccess { ids ->
                    val label = ids?.let { (p, a) -> PronounsApi.label(p, a, d) }
                    val old = cache.get(login)?.label
                    cache.put(login, Entry(label, System.currentTimeMillis()))
                    if (label != old) updated.tryEmit(setOf(userId))
                }
        } finally {
            inFlight.remove(login)
        }
    }

    private suspend fun ensureDefs(): Map<String, PronounDef>? = defsMutex.withLock {
        if (defs.isEmpty()) defs = attempt { api.catalogue() }.getOrNull().orEmpty()
        defs.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val WORKERS = 4
        const val TTL_MS = 60 * 60 * 1000L
    }
}
