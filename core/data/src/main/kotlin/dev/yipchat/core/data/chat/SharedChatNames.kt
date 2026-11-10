package dev.yipchat.core.data.chat

import dev.yipchat.core.common.AppScope
import dev.yipchat.core.common.attempt
import dev.yipchat.core.network.twitch.GqlApi
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Channel names for shared chat. Anonymous chat only gives the channel id, so look the name up once. */
@Singleton
class SharedChatNames @Inject constructor(private val gql: GqlApi, @AppScope private val scope: CoroutineScope) {
    private val names = ConcurrentHashMap<String, String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** Cached name, or null while it's being looked up (later messages get it). */
    fun nameFor(id: String): String? {
        names[id]?.let { return it }
        if (pending.add(id)) scope.launch { attempt { gql.displayName(id) }.getOrNull()?.let { names[id] = it }; pending.remove(id) }
        return null
    }
}
