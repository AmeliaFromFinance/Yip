package lol.sinist3r.yip.core.data.chat

import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.data.cosmetics.CosmeticsRepository
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.model.ChatItem
import lol.sinist3r.yip.core.network.chat.RawMessage
import lol.sinist3r.yip.core.network.chat.RawPart
import lol.sinist3r.yip.core.network.twitch.GqlApi
import lol.sinist3r.yip.core.network.twitch.arr
import lol.sinist3r.yip.core.network.twitch.edges
import lol.sinist3r.yip.core.network.twitch.int
import lol.sinist3r.yip.core.network.twitch.obj
import lol.sinist3r.yip.core.network.twitch.path
import lol.sinist3r.yip.core.network.twitch.str

/** Time-synced VOD chat replay. Call [seekTo] whenever the player position moves. */
class ReplaySession @Inject constructor(
    private val gql: GqlApi,
    private val cosmetics: CosmeticsRepository,
    private val settings: SettingsRepository,
) {
    private data class Timed(val offset: Int, val raw: RawMessage)

    private val pipeline = ChatPipeline(cosmetics)
    private val mutex = Mutex()
    private var videoId = ""
    private var channelId = ""
    private var loaded = ArrayList<Timed>()
    private var nextCursor: String? = null
    private var loadedUntil = -1
    private var loadedFrom = Int.MAX_VALUE

    private val _items = MutableStateFlow<ImmutableList<ChatItem>>(persistentListOf())
    val items: StateFlow<ImmutableList<ChatItem>> = _items.asStateFlow()

    suspend fun start(videoId: String, channelId: String) {
        this.videoId = videoId
        this.channelId = channelId
        cosmetics.loadChannel(channelId)
    }

    suspend fun seekTo(seconds: Int) = mutex.withLock {
        if (seconds < loadedFrom || seconds > loadedUntil + 60) {
            loaded.clear()
            nextCursor = null
            fetch(offset = seconds, cursor = null)
            loadedFrom = seconds
        }
        while (loadedUntil < seconds + 30 && nextCursor != null) fetch(null, nextCursor)
        val cos = cosmetics.channel(channelId).value
        val s = settings.settings.value
        val limit = s.chat.messageLimit
        val visible = loaded.asSequence().filter { it.offset <= seconds }.toList().takeLast(limit)
        _items.value = visible.mapNotNull { pipeline.process(it.raw, cos, s, null) }.distinctBy { it.id }.toImmutableList()
    }

    private suspend fun fetch(offset: Int?, cursor: String?) {
        val data = attempt { gql.videoComments(videoId, offset, cursor) }.getOrNull() ?: run { nextCursor = null; return }
        val comments = data.path("video", "comments")
        val edges = data.edges("video", "comments")
        edges.forEach { e ->
            val n = e.obj("node") ?: return@forEach
            parse(n)?.let { loaded += it }
        }
        loaded = ArrayList(loaded.distinctBy { it.raw.id }.sortedBy { it.offset })
        loadedUntil = loaded.lastOrNull()?.offset ?: loadedUntil
        nextCursor = if (comments?.obj("pageInfo")?.str("hasNextPage") == "true") edges.lastOrNull()?.str("cursor") else null
    }

    private fun parse(n: JsonObject): Timed? {
        val offset = n.int("contentOffsetSeconds") ?: return null
        val commenter = n.obj("commenter")
        val message = n.obj("message") ?: return null
        val parts = message.arr("fragments").mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            val text = o.str("text").orEmpty()
            val emoteId = o.obj("emote")?.str("emoteID")
            if (emoteId != null) RawPart.TwitchEmote(emoteId, text) else RawPart.Text(text)
        }
        return Timed(
            offset,
            RawMessage(
                id = n.str("id") ?: "$offset-${parts.hashCode()}",
                channelId = channelId,
                userId = commenter?.str("id").orEmpty(),
                login = commenter?.str("login").orEmpty(),
                displayName = commenter?.str("displayName") ?: commenter?.str("login").orEmpty(),
                color = message.str("userColor"),
                badges = message.arr("userBadges").mapNotNull { b -> (b as? JsonObject)?.let { (it.str("setID") ?: return@mapNotNull null) to it.str("version").orEmpty() } },
                parts = parts,
                text = parts.joinToString("") { (it as? RawPart.Text)?.text ?: (it as? RawPart.TwitchEmote)?.code.orEmpty() },
                timestamp = n.str("createdAt")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Clock.System.now(),
            ),
        )
    }
}
