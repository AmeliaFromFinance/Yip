package lol.sinist3r.yip.core.network.chat

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import lol.sinist3r.yip.core.common.AppException
import lol.sinist3r.yip.core.common.AppScope
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.network.twitch.EventSubCreate
import lol.sinist3r.yip.core.network.twitch.EventSubTransport
import lol.sinist3r.yip.core.network.twitch.HelixApi
import lol.sinist3r.yip.core.network.twitch.arr
import lol.sinist3r.yip.core.network.twitch.bool
import lol.sinist3r.yip.core.network.twitch.int
import lol.sinist3r.yip.core.network.twitch.obj
import lol.sinist3r.yip.core.network.twitch.str
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import timber.log.Timber

/**
 * One shared EventSub WebSocket for every joined channel. Twitch allows only three sockets per
 * user, so multi-chat must share. Channels join and leave by reference count.
 */
@Singleton
class EventSubChatClient @Inject constructor(
    private val okHttp: OkHttpClient,
    private val helix: HelixApi,
    private val json: Json,
    @AppScope private val scope: CoroutineScope,
) {
    private data class Joined(val flow: MutableSharedFlow<RawChat>, var refs: Int, val subIds: MutableList<String> = mutableListOf())

    private val channels = ConcurrentHashMap<String, Joined>()
    private val mutex = Mutex()
    private var connection: Job? = null
    private var socket: WebSocket? = null
    private var sessionId: String? = null
    private var userId: String? = null
    @Volatile private var lastMessageAt = 0L
    @Volatile private var keepaliveSeconds = 10

    fun join(channelId: String, viewerUserId: String): Flow<RawChat> = channelFlow {
        val joined = mutex.withLock {
            if (userId != null && userId != viewerUserId) closeAllLocked()
            userId = viewerUserId
            val j = channels.getOrPut(channelId) { Joined(MutableSharedFlow(extraBufferCapacity = 512), 0) }
            j.refs++
            ensureConnectedLocked()
            sessionId?.let { sid -> scope.launch { subscribe(channelId, sid) } }
            j
        }
        if (sessionId != null) send(RawChat.Status(connected = true, readOnly = false))
        val relay = launch { joined.flow.collect { send(it) } }
        awaitClose {
            relay.cancel()
            scope.launch { leave(channelId) }
        }
    }

    private suspend fun leave(channelId: String) = mutex.withLock {
        val j = channels[channelId] ?: return@withLock
        j.refs--
        if (j.refs > 0) return@withLock
        channels.remove(channelId)
        j.subIds.forEach { id -> scope.launch { attempt { helix.deleteEventSub(id) } } }
        if (channels.isEmpty()) closeAllLocked()
    }

    private fun closeAllLocked() {
        connection?.cancel()
        connection = null
        socket?.close(1000, null)
        socket = null
        sessionId = null
    }

    private fun ensureConnectedLocked() {
        if (connection?.isActive == true) return
        connection = scope.launch { connectLoop() }
    }

    private suspend fun connectLoop() {
        var url = DEFAULT_URL
        var backoff = 1
        while (scope.isActive && channels.isNotEmpty()) {
            var reconnectUrl: String? = null
            val watchdog = scope.launch {
                while (isActive) {
                    delay(5.seconds)
                    val idle = System.currentTimeMillis() - lastMessageAt
                    if (lastMessageAt > 0 && idle > (keepaliveSeconds + 10) * 1000L) {
                        Timber.w("EventSub keepalive missed, reconnecting")
                        socket?.cancel()
                    }
                }
            }
            okHttp.webSocketFlow(url).collect { ev ->
                when (ev) {
                    is WsEvent.Open -> { socket = ev.socket; lastMessageAt = System.currentTimeMillis() }
                    is WsEvent.Text -> {
                        lastMessageAt = System.currentTimeMillis()
                        reconnectUrl = handle(ev.text) ?: reconnectUrl
                        if (reconnectUrl != null) socket?.close(1000, "reconnect")
                    }
                    is WsEvent.Closed, is WsEvent.Failure -> broadcast(RawChat.Status(connected = false, readOnly = false))
                }
            }
            watchdog.cancel()
            lastMessageAt = 0
            if (reconnectUrl != null) {
                url = reconnectUrl!!
                backoff = 1
            } else {
                url = DEFAULT_URL
                sessionId = null
                channels.values.forEach { it.subIds.clear() }
                delay((backoff * 2).seconds)
                backoff = (backoff * 2).coerceAtMost(30)
            }
        }
    }

    /** Returns a reconnect URL when Twitch asks us to move. */
    private suspend fun handle(text: String): String? {
        val root = attempt { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val meta = root.obj("metadata") ?: return null
        val payload = root.obj("payload") ?: return null
        when (meta.str("message_type")) {
            "session_welcome" -> {
                val session = payload.obj("session") ?: return null
                val id = session.str("id") ?: return null
                keepaliveSeconds = session.int("keepalive_timeout_seconds") ?: 10
                val carried = id == sessionId
                sessionId = id
                broadcast(RawChat.Status(connected = true, readOnly = false))
                if (!carried) channels.keys.forEach { ch -> scope.launch { subscribe(ch, id) } }
            }
            "session_reconnect" -> return payload.obj("session")?.str("reconnect_url")
            "notification" -> {
                val event = payload.obj("event") ?: return null
                val type = meta.str("subscription_type") ?: return null
                if (type == "user.whisper.message") {
                    parseWhisper(event)?.let { channels[WHISPERS]?.flow?.tryEmit(it) }
                    return null
                }
                // Outgoing raids are keyed by the raiding channel.
                val channel = event.str("broadcaster_user_id") ?: event.str("from_broadcaster_user_id") ?: return null
                mapEvent(type, event)?.let { channels[channel]?.flow?.tryEmit(it) }
            }
            "revocation" -> {
                val sub = payload.obj("subscription") ?: return null
                val channel = if (sub.str("type") == "user.whisper.message") WHISPERS
                    else sub.obj("condition")?.let { it.str("broadcaster_user_id") ?: it.str("from_broadcaster_user_id") } ?: return null
                val status = sub.str("status")
                channels[channel]?.let { j ->
                    j.subIds.remove(sub.str("id"))
                    j.flow.tryEmit(RawChat.ServerNotice(revocationText(status)))
                    if (status == "user_removed") scope.launch {
                        delay(60.seconds)
                        sessionId?.let { subscribe(channel, it) }
                    }
                }
            }
        }
        return null
    }

    private fun revocationText(status: String?) = when (status) {
        "user_removed" -> "You were removed from this chat (timeout or ban). Rejoining in a minute."
        "authorization_revoked" -> "Chat access was revoked. Sign in again to chat."
        else -> "Chat subscription ended ($status)."
    }

    private suspend fun subscribe(channelId: String, sid: String) {
        val uid = userId ?: return
        val j = channels[channelId] ?: return
        if (j.subIds.isNotEmpty()) return
        if (channelId == WHISPERS) {
            attempt { helix.createEventSub(EventSubCreate("user.whisper.message", "1", mapOf("user_id" to uid), EventSubTransport.webSocket(sid))) }
                .onSuccess { created -> created?.id?.let(j.subIds::add) }
                .onFailure { e -> Timber.i("Whisper events unavailable: ${e.message}") }
            return
        }
        val condition = mapOf("broadcaster_user_id" to channelId, "user_id" to uid)
        for (type in CHAT_TYPES) {
            val result = attempt {
                helix.createEventSub(EventSubCreate(type, "1", condition, EventSubTransport.webSocket(sid)))
            }
            result.onSuccess { created -> created?.id?.let(j.subIds::add) }
                .onFailure { e ->
                    Timber.w(e, "EventSub subscribe $type failed")
                    if (type == "channel.chat.message") {
                        j.flow.tryEmit(RawChat.JoinFailed(twitchReason(e), notAllowed = e is AppException && e.kind == AppException.Kind.AUTH))
                        return // the rest would fail the same way
                    }
                }
        }
        // Outgoing raids, for the "Go to" prompt. Needs no extra scope.
        attempt { helix.createEventSub(EventSubCreate("channel.raid", "1", mapOf("from_broadcaster_user_id" to channelId), EventSubTransport.webSocket(sid))) }
            .onSuccess { created -> created?.id?.let(j.subIds::add) }
            .onFailure { e -> Timber.i("Raid events unavailable: ${e.message}") }
        // Channels you own or moderate: AutoMod holds, for approving in chat. Optional; older sign-ins lack the scope.
        if (canModerate(channelId, uid)) {
            val modCondition = mapOf("broadcaster_user_id" to channelId, "moderator_user_id" to uid)
            for (type in AUTOMOD_TYPES) {
                attempt { helix.createEventSub(EventSubCreate(type, "2", modCondition, EventSubTransport.webSocket(sid))) }
                    .onSuccess { created -> created?.id?.let(j.subIds::add) }
                    .onFailure { e -> Timber.i("AutoMod events unavailable: ${e.message}") }
            }
        }
    }

    @Volatile private var moderated: Triple<String, Long, Set<String>>? = null // user, fetched at, channel ids

    private suspend fun canModerate(channelId: String, uid: String): Boolean {
        if (channelId == uid) return true
        val cached = moderated?.takeIf { it.first == uid && System.currentTimeMillis() - it.second < 10 * 60_000 }
        val ids = cached?.third ?: attempt { helix.moderatedChannels(uid).toSet() }.getOrDefault(emptySet())
            .also { moderated = Triple(uid, System.currentTimeMillis(), it) }
        return channelId in ids
    }

    /** Pulls Twitch's "message" out of a Helix error body, falling back to the status line. */
    private fun twitchReason(e: Throwable): String {
        val text = e.message.orEmpty()
        val body = text.substringAfter(": ", "").trim()
        val fromJson = attempt { json.parseToJsonElement(body).jsonObject.str("message") }.getOrNull()
        return fromJson?.takeIf { it.isNotBlank() } ?: text.substringBefore(": ").ifBlank { "unknown error" }
    }

    private fun broadcast(event: RawChat) = channels.values.forEach { it.flow.tryEmit(event) }

    private fun mapEvent(type: String, e: JsonObject): RawChat? = when (type) {
        "channel.chat.message" -> RawChat.Message(message(e))
        "automod.message.hold" -> e.str("message_id")?.let { id ->
            RawChat.AutoModHeld(
                messageId = id,
                login = e.str("user_login").orEmpty(),
                displayName = e.str("user_name") ?: e.str("user_login").orEmpty(),
                text = e.obj("message")?.str("text").orEmpty(),
                reason = when (e.str("reason")) {
                    "blocked_term" -> "blocked term"
                    else -> e.obj("automod")?.str("category")?.replace('_', ' ') ?: "AutoMod"
                },
            )
        }
        "automod.message.update" -> e.str("message_id")?.let { RawChat.AutoModResolved(it, e.str("status").orEmpty().lowercase()) }
        "channel.chat.notification" -> notification(e)
        "channel.raid" -> e.str("to_broadcaster_user_login")?.let { RawChat.RaidOut(it, e.str("to_broadcaster_user_name") ?: it, e.int("viewers") ?: 0) }
        "channel.chat.message_delete" -> e.str("message_id")?.let(RawChat::Deleted)
        "channel.chat.clear_user_messages" -> e.str("target_user_id")?.let { RawChat.ClearUser(it, null) }
        "channel.chat.clear" -> RawChat.Clear
        "channel.chat_settings.update" -> RawChat.Room(
            emoteOnly = e.bool("emote_mode"),
            followersOnly = e.bool("follower_mode"),
            followersOnlyMinutes = e.int("follower_mode_duration_minutes"),
            slowSeconds = if (e.bool("slow_mode") == true) e.int("slow_mode_wait_time_seconds") ?: 0 else 0,
            subsOnly = e.bool("subscriber_mode"),
            unique = e.bool("unique_chat_mode"),
            channelId = e.str("broadcaster_user_id"),
        )
        else -> null
    }

    private fun parts(message: JsonObject?): List<RawPart> = message?.arr("fragments")?.mapNotNull { f ->
        val o = f as? JsonObject ?: return@mapNotNull null
        val text = o.str("text").orEmpty()
        when (o.str("type")) {
            "emote" -> o.obj("emote")?.str("id")?.let { RawPart.TwitchEmote(it, text) } ?: RawPart.Text(text)
            "cheermote" -> o.obj("cheermote")?.let { c ->
                RawPart.Cheer(c.str("prefix").orEmpty(), c.int("bits") ?: 0, c.int("tier") ?: 1)
            } ?: RawPart.Text(text)
            "mention" -> o.obj("mention")?.let { m -> RawPart.Mention(m.str("user_login").orEmpty(), text.removePrefix("@")) } ?: RawPart.Text(text)
            else -> RawPart.Text(text)
        }
    }.orEmpty()

    private fun message(e: JsonObject, chatterPrefix: String = "chatter"): RawMessage {
        val msg = e.obj("message")
        val text = msg?.str("text").orEmpty()
        val reply = e.obj("reply")
        val type = e.str("message_type")
        return RawMessage(
            id = e.str("message_id") ?: e.str("id") ?: "${e.str("${chatterPrefix}_user_id")}-${System.nanoTime()}",
            channelId = e.str("broadcaster_user_id").orEmpty(),
            userId = e.str("${chatterPrefix}_user_id").orEmpty(),
            login = e.str("${chatterPrefix}_user_login").orEmpty(),
            displayName = e.str("${chatterPrefix}_user_name") ?: e.str("${chatterPrefix}_user_login").orEmpty(),
            color = e.str("color")?.takeIf { it.isNotBlank() },
            badges = e.arr("badges").mapNotNull { b -> (b as? JsonObject)?.let { (it.str("set_id") ?: return@mapNotNull null) to it.str("id").orEmpty() } },
            parts = parts(msg),
            text = text,
            timestamp = Clock.System.now(),
            isAction = false,
            isFirstMessage = type == "user_intro",
            replyParentId = reply?.str("parent_message_id"),
            replyParentLogin = reply?.str("parent_user_name"),
            replyParentText = reply?.str("parent_message_body"),
            bits = e.obj("cheer")?.int("bits") ?: 0,
            rewardId = e.str("channel_points_custom_reward_id"),
            isHighlightedRedemption = type == "channel_points_highlighted",
        )
    }

    private fun notification(e: JsonObject): RawChat {
        val kind = when (e.str("notice_type")?.removePrefix("shared_chat_")) {
            "sub" -> RawNoticeKind.SUB
            "resub" -> RawNoticeKind.RESUB
            "sub_gift", "gift_paid_upgrade", "prime_paid_upgrade", "pay_it_forward" -> RawNoticeKind.GIFT
            "community_sub_gift" -> RawNoticeKind.MYSTERY_GIFT
            "raid" -> RawNoticeKind.RAID
            "announcement" -> RawNoticeKind.ANNOUNCEMENT
            "bits_badge_tier" -> RawNoticeKind.BITS_BADGE
            "charity_donation" -> RawNoticeKind.CHARITY
            "watch_streak" -> RawNoticeKind.WATCH_STREAK
            else -> RawNoticeKind.OTHER
        }
        val body = e.obj("message")?.str("text").orEmpty()
        val msg = if (body.isNotBlank()) message(e) else null
        return RawChat.Notice(
            id = e.str("message_id") ?: "notice-${System.nanoTime()}",
            kind = kind,
            systemText = e.str("system_message").orEmpty(),
            msg = msg,
            timestamp = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        )
    }

    companion object {
        /** Pseudo channel for [join]: receives [RawChat.Whisper] for the signed-in user. */
        const val WHISPERS = "whispers"

        fun parseWhisper(event: JsonObject): RawChat.Whisper? {
            val from = event.str("from_user_id") ?: return null
            return RawChat.Whisper(
                id = event.str("whisper_id") ?: "w-$from-${System.nanoTime()}",
                fromId = from,
                fromLogin = event.str("from_user_login").orEmpty(),
                fromName = event.str("from_user_name") ?: event.str("from_user_login").orEmpty(),
                text = event.obj("whisper")?.str("text").orEmpty(),
            )
        }
        private const val DEFAULT_URL = "wss://eventsub.wss.twitch.tv/ws?keepalive_timeout_seconds=30"
        private val AUTOMOD_TYPES = listOf("automod.message.hold", "automod.message.update")
        private val CHAT_TYPES = listOf(
            "channel.chat.message",
            "channel.chat.notification",
            "channel.chat.message_delete",
            "channel.chat.clear_user_messages",
            "channel.chat.clear",
            "channel.chat_settings.update",
        )
    }
}
