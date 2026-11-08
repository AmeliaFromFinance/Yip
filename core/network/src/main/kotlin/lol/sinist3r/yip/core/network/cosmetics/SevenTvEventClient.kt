package lol.sinist3r.yip.core.network.cosmetics

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import lol.sinist3r.yip.core.common.AppScope
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.model.Badge
import lol.sinist3r.yip.core.model.Emote
import lol.sinist3r.yip.core.model.Paint
import lol.sinist3r.yip.core.network.chat.WsEvent
import lol.sinist3r.yip.core.network.chat.webSocketFlow
import lol.sinist3r.yip.core.network.twitch.arr
import lol.sinist3r.yip.core.network.twitch.int
import lol.sinist3r.yip.core.network.twitch.obj
import lol.sinist3r.yip.core.network.twitch.str
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import timber.log.Timber

sealed interface SevenTvEvent {
    data class EmoteAdded(val setId: String, val emote: Emote, val actor: String?) : SevenTvEvent
    data class EmoteRemoved(val setId: String, val emoteId: String, val code: String?, val actor: String?) : SevenTvEvent
    data class EmoteRenamed(val setId: String, val emoteId: String, val oldCode: String, val newCode: String, val actor: String?) : SevenTvEvent
    data class PaintDefined(val paint: Paint) : SevenTvEvent
    data class BadgeDefined(val badge: Badge) : SevenTvEvent
    data class Entitlement(val kind: String, val refId: String, val twitchUserId: String, val granted: Boolean) : SevenTvEvent
    data class ActiveSetChanged(val sevenTvUserId: String, val newSetId: String) : SevenTvEvent
}

/** Shared 7TV EventAPI v3 socket. Subscriptions are replayed after every reconnect. */
@Singleton
class SevenTvEventClient @Inject constructor(
    private val okHttp: OkHttpClient,
    private val json: Json,
    @AppScope private val scope: CoroutineScope,
) {
    private data class Sub(val type: String, val condition: Map<String, String>)

    private val subs = ConcurrentHashMap<Sub, Int>()
    private val events = MutableSharedFlow<SevenTvEvent>(extraBufferCapacity = 256)
    private var socket: WebSocket? = null
    private var job: Job? = null

    val stream: SharedFlow<SevenTvEvent> = events.asSharedFlow()

    @Synchronized
    fun watchChannel(twitchChannelId: String, emoteSetId: String?, sevenTvUserId: String?) {
        val ctx = mapOf("ctx" to "channel", "platform" to "TWITCH", "id" to twitchChannelId)
        add(Sub("cosmetic.*", ctx))
        add(Sub("entitlement.*", ctx))
        emoteSetId?.let { add(Sub("emote_set.update", mapOf("object_id" to it))) }
        sevenTvUserId?.let { add(Sub("user.update", mapOf("object_id" to it))) }
        if (job?.isActive != true) job = scope.launch { loop() }
    }

    @Synchronized
    fun unwatchChannel(twitchChannelId: String, emoteSetId: String?, sevenTvUserId: String?) {
        val ctx = mapOf("ctx" to "channel", "platform" to "TWITCH", "id" to twitchChannelId)
        remove(Sub("cosmetic.*", ctx))
        remove(Sub("entitlement.*", ctx))
        emoteSetId?.let { remove(Sub("emote_set.update", mapOf("object_id" to it))) }
        sevenTvUserId?.let { remove(Sub("user.update", mapOf("object_id" to it))) }
        if (subs.isEmpty()) {
            job?.cancel()
            socket?.close(1000, null)
            socket = null
        }
    }

    /** Watch a specific emote set (e.g. after the channel switches sets). */
    fun watchSet(setId: String) = add(Sub("emote_set.update", mapOf("object_id" to setId)))

    private fun add(sub: Sub) {
        val n = subs.merge(sub, 1, Int::plus) ?: 1
        if (n == 1) socket?.send(frame(35, sub))
    }

    private fun remove(sub: Sub) {
        val n = subs.computeIfPresent(sub) { _, v -> if (v <= 1) null else v - 1 }
        if (n == null) socket?.send(frame(36, sub))
    }

    private fun frame(op: Int, sub: Sub) = buildJsonObject {
        put("op", op)
        putJsonObject("d") {
            put("type", sub.type)
            putJsonObject("condition") { sub.condition.forEach { (k, v) -> put(k, v) } }
        }
    }.toString()

    private suspend fun loop() {
        var backoff = 1
        while (scope.isActive && subs.isNotEmpty()) {
            okHttp.webSocketFlow("wss://events.7tv.io/v3").collect { ev ->
                when (ev) {
                    is WsEvent.Open -> {
                        socket = ev.socket
                        backoff = 1
                        subs.keys.forEach { ev.socket.send(frame(35, it)) }
                    }
                    is WsEvent.Text -> handle(ev.text)
                    is WsEvent.Closed, is WsEvent.Failure -> socket = null
                }
            }
            delay((backoff * 2).seconds)
            backoff = (backoff * 2).coerceAtMost(60)
        }
    }

    private fun handle(text: String) {
        val root = attempt { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val op = root.int("op")
        val d = root.obj("d") ?: return
        when (op) {
            0 -> dispatch(d.str("type") ?: return, d.obj("body") ?: return)
            4 -> socket?.close(1000, "reconnect") // server asked us to reconnect
            6 -> Timber.w("7TV EventAPI error: %s", d)
        }
    }

    private fun dispatch(type: String, body: JsonObject) {
        val actor = body.obj("actor")?.str("display_name")
        when (type) {
            "emote_set.update" -> {
                val setId = body.str("id") ?: return
                body.arr("pushed").forEach { p ->
                    val o = p as? JsonObject ?: return@forEach
                    if (o.str("key") != "emotes") return@forEach
                    SevenTvApi.parseActiveEmote(o.obj("value"), isChannel = true)?.let { events.tryEmit(SevenTvEvent.EmoteAdded(setId, it, actor)) }
                }
                body.arr("pulled").forEach { p ->
                    val o = p as? JsonObject ?: return@forEach
                    if (o.str("key") != "emotes") return@forEach
                    val old = o.obj("old_value") ?: return@forEach
                    events.tryEmit(SevenTvEvent.EmoteRemoved(setId, old.str("id") ?: return@forEach, old.str("name"), actor))
                }
                body.arr("updated").forEach { p ->
                    val o = p as? JsonObject ?: return@forEach
                    if (o.str("key") != "emotes") return@forEach
                    val old = o.obj("old_value") ?: return@forEach
                    val new = o.obj("value") ?: return@forEach
                    events.tryEmit(
                        SevenTvEvent.EmoteRenamed(setId, new.str("id") ?: return@forEach, old.str("name").orEmpty(), new.str("name").orEmpty(), actor),
                    )
                }
            }
            "cosmetic.create" -> {
                val obj = body.obj("object") ?: return
                val id = obj.str("id") ?: return
                val data = obj.obj("data") ?: return
                when (obj.str("kind")) {
                    "PAINT" -> events.tryEmit(SevenTvEvent.PaintDefined(SevenTvApi.parsePaint(id, data)))
                    "BADGE" -> SevenTvApi.parseBadge(id, data)?.let { events.tryEmit(SevenTvEvent.BadgeDefined(it)) }
                }
            }
            "entitlement.create", "entitlement.delete" -> {
                val obj = body.obj("object") ?: return
                val kind = obj.str("kind") ?: return
                val ref = obj.str("ref_id") ?: return
                val twitchId = obj.obj("user")?.arr("connections")
                    ?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { it.str("platform") == "TWITCH" }?.str("id") ?: return
                events.tryEmit(SevenTvEvent.Entitlement(kind, ref, twitchId, granted = type == "entitlement.create"))
            }
            "user.update" -> {
                val userId = body.str("id") ?: return
                body.arr("updated").forEach { u ->
                    val o = u as? JsonObject ?: return@forEach
                    if (o.str("key") != "connections") return@forEach
                    o.arr("value").forEach { v ->
                        val c = v as? JsonObject ?: return@forEach
                        if (c.str("key") == "emote_set") {
                            c.obj("value")?.str("id")?.let { events.tryEmit(SevenTvEvent.ActiveSetChanged(userId, it)) }
                        }
                    }
                }
            }
        }
    }
}
