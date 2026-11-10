package lol.sinist3r.yip.core.network.chat

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import timber.log.Timber

/** A channel points redemption anyone in the channel can see. */
data class RawRedemption(
    val id: String,
    val userLogin: String,
    val userName: String,
    val rewardId: String,
    val rewardTitle: String,
    val cost: Int,
    /** True when the viewer typed a message with it; that message shows up in chat too. */
    val needsInput: Boolean,
    val timestamp: Instant,
)

/**
 * Twitch's web realtime socket (Hermes). Only used for channel points redemptions, which
 * EventSub only gives to the broadcaster. Public topic, no sign-in needed.
 */
@Singleton
class HermesClient @Inject constructor(private val okHttp: OkHttpClient, private val json: Json) {

    fun redemptions(channelId: String): Flow<RawRedemption> = flow {
        var backoff = 2_000L
        while (true) {
            val events = Channel<RawRedemption>(Channel.BUFFERED)
            runCatching {
                coroutineScope {
                    val pump = launch {
                        okHttp.webSocketFlow(URL).collect { ev ->
                            when (ev) {
                                is WsEvent.Open -> ev.socket.send(subscribe(channelId))
                                is WsEvent.Text -> parse(ev.text)?.let { events.send(it) }
                                is WsEvent.Failure -> throw ev.error
                                is WsEvent.Closed -> throw IllegalStateException("closed ${ev.code}")
                            }
                        }
                    }
                    pump.invokeOnCompletion { events.close(it) }
                    for (r in events) emit(r)
                }
            }.onFailure { Timber.i("Hermes for $channelId dropped: ${it.message}") }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000)
        }
    }

    private fun subscribe(channelId: String): String = buildJsonObject {
        put("type", "subscribe")
        put("id", UUID.randomUUID().toString())
        putJsonObject("subscribe") {
            put("id", UUID.randomUUID().toString())
            put("type", "pubsub")
            putJsonObject("pubsub") { put("topic", "community-points-channel-v1.$channelId") }
        }
        put("timestamp", Clock.System.now().toString())
    }.toString()

    private fun parse(text: String): RawRedemption? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val inner = root["notification"]?.jsonObject?.get("pubsub")?.jsonPrimitive?.contentOrNull ?: return null
        parseRedemption(json, inner)
    }.getOrNull()

    companion object {
        private const val URL = "wss://hermes.twitch.tv/v1?clientId=kimne78kx3ncx6brgo4mv6wki5h1ko"

        /** The inner pubsub payload of a "reward-redeemed" message. */
        fun parseRedemption(json: Json, payload: String): RawRedemption? {
            val msg = json.parseToJsonElement(payload).jsonObject
            if (msg["type"]?.jsonPrimitive?.contentOrNull != "reward-redeemed") return null
            val r = msg["data"]?.jsonObject?.get("redemption")?.jsonObject ?: return null
            val user = r["user"]?.jsonObject ?: return null
            val reward = r["reward"]?.jsonObject ?: return null
            fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull
            return RawRedemption(
                id = r.s("id") ?: return null,
                userLogin = user.s("login").orEmpty(),
                userName = user.s("display_name") ?: user.s("login").orEmpty(),
                rewardId = reward.s("id").orEmpty(),
                rewardTitle = reward.s("title").orEmpty(),
                cost = reward["cost"]?.jsonPrimitive?.intOrNull ?: 0,
                needsInput = reward["is_user_input_required"]?.jsonPrimitive?.booleanOrNull == true || !r.s("user_input").isNullOrBlank(),
                timestamp = r.s("redeemed_at")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Clock.System.now(),
            )
        }
    }
}
