package lol.sinist3r.yip.core.data.chat

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineDispatcher
import lol.sinist3r.yip.core.common.AppException
import lol.sinist3r.yip.core.common.DefaultDispatcher
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.data.account.AccountRepository
import lol.sinist3r.yip.core.data.cosmetics.CosmeticsRepository
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.model.ChatItem
import lol.sinist3r.yip.core.model.NoticeKind
import lol.sinist3r.yip.core.model.RoomState
import lol.sinist3r.yip.core.network.chat.EventSubChatClient
import lol.sinist3r.yip.core.network.chat.IrcChatClient
import lol.sinist3r.yip.core.network.chat.IrcParser
import lol.sinist3r.yip.core.network.chat.RawChat
import lol.sinist3r.yip.core.network.chat.RawNoticeKind
import lol.sinist3r.yip.core.network.cosmetics.BttvFfzApi
import lol.sinist3r.yip.core.network.twitch.HelixApi
import timber.log.Timber

data class ChatConnection(val connected: Boolean = false, val readOnly: Boolean = true, val message: String? = null)

/** One live chat. Shared by every screen showing the same channel (reference counted). */
class ChatSession internal constructor(
    val channelId: String,
    val channelLogin: String,
    private val repo: ChatRepository,
    private val dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val buffer = ArrayDeque<ChatItem>()
    private val ids = HashSet<String>()
    private val incoming = Channel<RawChat>(Channel.UNLIMITED)
    @Volatile private var dirty = false
    private val knownChatters = LinkedHashSet<String>()

    private val _items = MutableStateFlow<ImmutableList<ChatItem>>(persistentListOf())
    val items: StateFlow<ImmutableList<ChatItem>> = _items.asStateFlow()

    private val _room = MutableStateFlow(RoomState())
    val room: StateFlow<RoomState> = _room.asStateFlow()

    private val _connection = MutableStateFlow(ChatConnection())
    val connection: StateFlow<ChatConnection> = _connection.asStateFlow()

    /** Recent chatter logins, newest last, for @-autocomplete. */
    fun chatters(): List<String> = synchronized(knownChatters) { knownChatters.toList().asReversed() }

    internal fun start() {
        scope.launch { repo.cosmetics.loadChannel(channelId) }
        scope.launch { repo.cosmetics.emoteChanges.collect { if (it.channelId == channelId) system(it.text) } }
        scope.launch { consume() }
        scope.launch { publisher() }
        scope.launch { transport() }
    }

    internal fun stop() {
        repo.cosmetics.releaseChannel(channelId)
        scope.cancel()
    }

    private suspend fun transport() {
        val settings = repo.settings.current()
        if (settings.chat.loadRecentHistory) {
            attempt { repo.bttvFfz.recentMessages(channelLogin, 100) }.getOrNull()?.forEach { line ->
                IrcParser.parseLine(line)?.let { IrcParser.toRawChat(it, isHistory = true) }?.let { incoming.trySend(it) }
            }
        }
        val userId = repo.accounts.userId()
        val flow = if (userId != null) repo.eventSub.join(channelId, userId) else repo.irc.join(channelLogin)
        if (userId != null) attempt { repo.helix.chatSettings(channelId) }.getOrNull()?.let { s ->
            incoming.trySend(RawChat.Room(s.emoteMode, s.followerModeDuration, s.followerMode, if (s.slowMode) s.slowModeWaitTime else 0, s.subscriberMode, s.uniqueChatMode))
        }
        flow.collect { incoming.send(it) }
    }

    private suspend fun consume() {
        // Wait briefly for emotes so the first screen of messages renders with images.
        val cos = repo.cosmetics.channel(channelId)
        kotlinx.coroutines.withTimeoutOrNull(4000) { cos.first { it.loaded } }
        for (ev in incoming) {
            val settings = repo.settings.settings.value
            val self = repo.accounts.state.value.active?.login
            when (ev) {
                is RawChat.Message -> {
                    repo.pipeline.process(ev.msg, cos.value, settings, self)?.let(::append)
                    synchronized(knownChatters) {
                        knownChatters.remove(ev.msg.login)
                        knownChatters += ev.msg.login
                        if (knownChatters.size > 500) knownChatters.remove(knownChatters.first())
                    }
                }
                is RawChat.Notice -> {
                    if (!noticeAllowed(ev.kind, settings)) continue
                    val msg = ev.msg?.let { repo.pipeline.process(it, cos.value, settings, self) }
                    append(ChatItem.Notice(ev.id, ev.timestamp, ev.kind.toModel(), ev.systemText, msg))
                }
                is RawChat.Deleted -> mutate { list -> list.map { if (it is ChatItem.Message && it.id == ev.messageId) it.copy(isDeleted = true) else it } }
                is RawChat.ClearUser -> {
                    mutate { list -> list.map { if (it is ChatItem.Message && it.user.id == ev.userId) it.copy(isDeleted = true) else it } }
                    if (settings.chat.showModActions) {
                        val name = buffer.lastOrNull { it is ChatItem.Message && it.user.id == ev.userId }?.let { (it as ChatItem.Message).user.displayName } ?: "A user"
                        system(if (ev.durationSeconds != null) "$name was timed out for ${ev.durationSeconds}s" else "$name's messages were cleared")
                    }
                }
                RawChat.Clear -> { mutate { emptyList() }; system("Chat was cleared by a moderator") }
                is RawChat.Room -> _room.update { r ->
                    r.copy(
                        emoteOnly = ev.emoteOnly ?: r.emoteOnly,
                        followersOnlyMinutes = if (ev.followersOnly == false) null else ev.followersOnlyMinutes ?: r.followersOnlyMinutes,
                        slowSeconds = ev.slowSeconds ?: r.slowSeconds,
                        subscriberOnly = ev.subsOnly ?: r.subscriberOnly,
                        uniqueChat = ev.unique ?: r.uniqueChat,
                    )
                }
                is RawChat.Status -> {
                    val was = _connection.value.connected
                    _connection.value = ChatConnection(ev.connected, ev.readOnly, ev.message)
                    if (ev.connected && !was && settings.chat.showJoinMessage) system("Joined #$channelLogin" + if (ev.readOnly) " (read only, sign in to chat)" else "")
                    if (!ev.connected && was) system("Disconnected, reconnecting...")
                }
                is RawChat.ServerNotice -> system(ev.text)
            }
        }
    }

    private fun noticeAllowed(kind: RawNoticeKind, s: lol.sinist3r.yip.core.model.AppSettings) = when (kind) {
        RawNoticeKind.SUB, RawNoticeKind.RESUB, RawNoticeKind.GIFT, RawNoticeKind.MYSTERY_GIFT -> s.chat.showSubNotices
        RawNoticeKind.RAID -> s.chat.showRaids
        RawNoticeKind.REDEMPTION -> s.chat.showRedemptions
        else -> true
    }

    private fun RawNoticeKind.toModel() = when (this) {
        RawNoticeKind.SUB -> NoticeKind.SUB
        RawNoticeKind.RESUB -> NoticeKind.RESUB
        RawNoticeKind.GIFT -> NoticeKind.GIFT
        RawNoticeKind.MYSTERY_GIFT -> NoticeKind.MYSTERY_GIFT
        RawNoticeKind.RAID -> NoticeKind.RAID
        RawNoticeKind.ANNOUNCEMENT -> NoticeKind.ANNOUNCEMENT
        RawNoticeKind.BITS_BADGE -> NoticeKind.BITS_BADGE
        RawNoticeKind.CHARITY -> NoticeKind.CHARITY
        RawNoticeKind.WATCH_STREAK -> NoticeKind.WATCH_STREAK
        RawNoticeKind.REDEMPTION -> NoticeKind.REDEMPTION
        RawNoticeKind.OTHER -> NoticeKind.OTHER
    }

    fun system(text: String) = append(ChatItem.System("sys-${System.nanoTime()}", Clock.System.now(), text))

    /** Drops duplicates (history and live overlap); list keys must be unique. */
    private fun append(item: ChatItem) = synchronized(buffer) {
        if (!ids.add(item.id)) return@synchronized
        buffer.addLast(item)
        val limit = repo.settings.settings.value.chat.messageLimit.coerceIn(50, 5000)
        while (buffer.size > limit) ids.remove(buffer.removeFirst().id)
        dirty = true
    }

    private fun mutate(block: (List<ChatItem>) -> List<ChatItem>) = synchronized(buffer) {
        val next = block(buffer.toList())
        buffer.clear()
        ids.clear()
        next.forEach { if (ids.add(it.id)) buffer.addLast(it) }
        dirty = true
    }

    /** Publishes at most ~30 times a second so a spam wave becomes one list update. */
    private suspend fun publisher() {
        while (scope.isActive) {
            delay(33)
            if (dirty) {
                val snapshot = synchronized(buffer) {
                    dirty = false
                    buffer.toImmutableList()
                }
                _items.value = snapshot
            }
        }
    }

    suspend fun send(text: String, replyToId: String? = null): Result<Unit> = repo.send(this, text, replyToId)
}

/** Creates and shares chat sessions; handles sending and slash commands. */
@Singleton
class ChatRepository @Inject constructor(
    internal val cosmetics: CosmeticsRepository,
    internal val settings: SettingsRepository,
    internal val accounts: AccountRepository,
    internal val eventSub: EventSubChatClient,
    internal val irc: IrcChatClient,
    internal val bttvFfz: BttvFfzApi,
    internal val helix: HelixApi,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    internal val pipeline = ChatPipeline(cosmetics)
    private val sessions = ConcurrentHashMap<String, Pair<ChatSession, Int>>()

    @Synchronized
    fun open(channelId: String, channelLogin: String): ChatSession {
        val existing = sessions[channelId]
        if (existing != null) {
            sessions[channelId] = existing.first to existing.second + 1
            return existing.first
        }
        val s = ChatSession(channelId, channelLogin.lowercase(), this, dispatcher)
        sessions[channelId] = s to 1
        s.start()
        return s
    }

    @Synchronized
    fun close(session: ChatSession) {
        val (s, refs) = sessions[session.channelId] ?: return
        if (refs <= 1) {
            sessions.remove(session.channelId)
            s.stop()
        } else {
            sessions[session.channelId] = s to refs - 1
        }
    }

    internal suspend fun send(session: ChatSession, text: String, replyTo: String?): Result<Unit> = attempt {
        val me = accounts.userId() ?: throw AppException(AppException.Kind.AUTH, "Sign in to chat")
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@attempt
        if (trimmed.startsWith("/") && !trimmed.startsWith("//")) {
            command(session, me, trimmed)
            return@attempt
        }
        val result = helix.sendMessage(session.channelId, me, trimmed.removePrefix("/"), replyTo)
        if (!result.isSent) session.system("Message not sent: ${result.dropReason?.message ?: "rejected by Twitch"}")
    }.onFailure { Timber.w(it, "send failed"); session.system(it.message ?: "Couldn't send") }

    private suspend fun userId(login: String): String =
        helix.users(logins = listOf(login.removePrefix("@"))).firstOrNull()?.id ?: throw AppException(AppException.Kind.NOT_FOUND, "No user called $login")

    private suspend fun command(s: ChatSession, me: String, line: String) {
        val args = line.split(' ').filter { it.isNotBlank() }
        val cmd = args[0].lowercase()
        val ch = s.channelId
        when (cmd) {
            "/me" -> helix.sendMessage(ch, me, args.drop(1).joinToString(" ")) // Helix has no /me; send as plain text
            "/ban" -> helix.ban(ch, me, userId(args[1]), null, args.drop(2).joinToString(" ").ifBlank { null })
            "/timeout" -> helix.ban(ch, me, userId(args[1]), args.getOrNull(2)?.toIntOrNull() ?: 600, args.drop(3).joinToString(" ").ifBlank { null })
            "/unban", "/untimeout" -> helix.unban(ch, me, userId(args[1]))
            "/clear" -> helix.deleteMessage(ch, me, null)
            "/slow" -> helix.updateChatSettings(ch, me, mapOf("slow_mode" to true, "slow_mode_wait_time" to (args.getOrNull(1)?.toIntOrNull() ?: 30)))
            "/slowoff" -> helix.updateChatSettings(ch, me, mapOf("slow_mode" to false))
            "/followers" -> helix.updateChatSettings(ch, me, mapOf("follower_mode" to true, "follower_mode_duration" to (args.getOrNull(1)?.toIntOrNull() ?: 0)))
            "/followersoff" -> helix.updateChatSettings(ch, me, mapOf("follower_mode" to false))
            "/emoteonly" -> helix.updateChatSettings(ch, me, mapOf("emote_mode" to true))
            "/emoteonlyoff" -> helix.updateChatSettings(ch, me, mapOf("emote_mode" to false))
            "/subscribers" -> helix.updateChatSettings(ch, me, mapOf("subscriber_mode" to true))
            "/subscribersoff" -> helix.updateChatSettings(ch, me, mapOf("subscriber_mode" to false))
            "/uniquechat" -> helix.updateChatSettings(ch, me, mapOf("unique_chat_mode" to true))
            "/uniquechatoff" -> helix.updateChatSettings(ch, me, mapOf("unique_chat_mode" to false))
            "/announce" -> helix.announce(ch, me, args.drop(1).joinToString(" "))
            "/shoutout" -> helix.shoutout(ch, userId(args[1]), me)
            "/color" -> helix.setChatColor(me, args.getOrNull(1) ?: "blue")
            "/block" -> helix.block(userId(args[1]))
            "/unblock" -> helix.unblock(userId(args[1]))
            "/help" -> s.system(HELP)
            else -> s.system("Unknown command $cmd. Type /help for the list.")
        }
    }

    companion object {
        const val HELP = "Commands: /me, /ban, /timeout, /unban, /clear, /slow, /slowoff, /followers, /followersoff, " +
            "/emoteonly, /emoteonlyoff, /subscribers, /subscribersoff, /uniquechat, /uniquechatoff, /announce, /shoutout, /color, /block, /unblock"
    }
}
