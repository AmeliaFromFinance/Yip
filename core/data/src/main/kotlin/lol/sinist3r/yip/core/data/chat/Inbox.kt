package lol.sinist3r.yip.core.data.chat

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lol.sinist3r.yip.core.common.AppScope
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.data.account.AccountRepository
import lol.sinist3r.yip.core.model.ChatItem
import lol.sinist3r.yip.core.model.HighlightKind
import lol.sinist3r.yip.core.network.chat.EventSubChatClient
import lol.sinist3r.yip.core.network.chat.RawChat
import lol.sinist3r.yip.core.network.twitch.HelixApi

data class InboxMention(val channelLogin: String, val message: ChatItem.Message)

/** Mentions from every chat opened this session, newest first. */
@Singleton
class MentionInbox @Inject constructor() {
    private val _items = MutableStateFlow<List<InboxMention>>(emptyList())
    val items: StateFlow<List<InboxMention>> = _items.asStateFlow()
    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    fun onMessage(channelLogin: String, m: ChatItem.Message) {
        if (m.highlight != HighlightKind.MENTION || m.isHistory || m.isSelf) return
        _items.update { list -> if (list.any { it.message.id == m.id }) list else (listOf(InboxMention(channelLogin, m)) + list).take(MAX) }
        _unread.update { it + 1 }
    }

    fun markRead() { _unread.value = 0 }
    fun clear() { _items.value = emptyList(); _unread.value = 0 }

    private companion object { const val MAX = 200 }
}

data class WhisperMessage(val id: String, val fromMe: Boolean, val text: String, val at: Instant)
data class WhisperThread(val userId: String, val login: String, val displayName: String, val messages: List<WhisperMessage> = emptyList(), val unread: Int = 0) {
    val last get() = messages.lastOrNull()
}

/**
 * Whispers for the signed-in account, kept for this session. Listens only while [setActive] is on
 * (app on screen or playing) so it doesn't hold a socket open in the background for nothing.
 */
@Singleton
class WhisperRepository @Inject constructor(
    private val eventSub: EventSubChatClient,
    private val helix: HelixApi,
    private val accounts: AccountRepository,
    private val notifier: MentionNotifier,
    @AppScope private val scope: CoroutineScope,
) {
    private val _threads = MutableStateFlow<Map<String, WhisperThread>>(emptyMap())
    /** Keyed by lowercase login. */
    val threads: StateFlow<Map<String, WhisperThread>> = _threads.asStateFlow()
    val unread = _threads.map { m -> m.values.sumOf { it.unread } }
    private var job: Job? = null
    @Volatile private var openThread: String? = null

    fun setActive(active: Boolean) {
        if (active == (job?.isActive == true)) return
        job?.cancel()
        job = if (!active) null else scope.launch {
            accounts.active.map { it?.userId }.distinctUntilChanged().collectLatest { uid ->
                _threads.value = emptyMap()
                uid ?: return@collectLatest
                eventSub.join(EventSubChatClient.WHISPERS, uid).filterIsInstance<RawChat.Whisper>().collect(::receive)
            }
        }
    }

    private fun receive(w: RawChat.Whisper) {
        val key = w.fromLogin.lowercase()
        val visible = openThread == key
        _threads.update { m ->
            val t = m[key] ?: WhisperThread(w.fromId, w.fromLogin, w.fromName)
            m + (key to t.copy(displayName = w.fromName, messages = (t.messages + WhisperMessage(w.id, false, w.text, Clock.System.now())).takeLast(MAX), unread = if (visible) 0 else t.unread + 1))
        }
        if (!visible) notifier.onWhisper(w.fromLogin, w.fromName, w.text)
    }

    /** Marks the thread read and stops notifications for it while open. Pass null when it closes. */
    fun setOpen(login: String?) {
        openThread = login?.lowercase()
        login ?: return
        _threads.update { m -> m[login.lowercase()]?.let { m + (login.lowercase() to it.copy(unread = 0)) } ?: m }
        notifier.clearWhisper(login)
    }

    suspend fun send(login: String, text: String): Result<Unit> = attempt {
        val me = accounts.userId() ?: error("Sign in to whisper")
        val key = login.lowercase()
        val existing = _threads.value[key]
        val target = existing?.let { it.userId to it.displayName }
            ?: helix.users(logins = listOf(key)).firstOrNull()?.let { it.id to it.displayName }
            ?: error("No user called $login")
        helix.sendWhisper(me, target.first, text)
        _threads.update { m ->
            val t = m[key] ?: WhisperThread(target.first, key, target.second)
            m + (key to t.copy(messages = (t.messages + WhisperMessage("me-${System.nanoTime()}", true, text, Clock.System.now())).takeLast(MAX)))
        }
    }

    private companion object { const val MAX = 300 }
}
