package lol.sinist3r.yip.core.data.cosmetics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lol.sinist3r.yip.core.common.IoDispatcher
import lol.sinist3r.yip.core.model.PaintMode
import lol.sinist3r.yip.core.network.cosmetics.SevenTvStyle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import lol.sinist3r.yip.core.common.AppScope
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.data.account.AccountRepository
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.model.Badge
import lol.sinist3r.yip.core.model.BadgeSource
import lol.sinist3r.yip.core.model.Emote
import lol.sinist3r.yip.core.model.EmoteProvider
import lol.sinist3r.yip.core.model.Paint
import lol.sinist3r.yip.core.model.ProviderSlot
import lol.sinist3r.yip.core.model.ThirdPartySettings
import lol.sinist3r.yip.core.network.cosmetics.BttvFfzApi
import lol.sinist3r.yip.core.network.cosmetics.SevenTvApi
import lol.sinist3r.yip.core.network.cosmetics.SevenTvEvent
import lol.sinist3r.yip.core.network.cosmetics.SevenTvEventClient
import lol.sinist3r.yip.core.network.cosmetics.UserBadges
import lol.sinist3r.yip.core.network.twitch.GqlApi
import lol.sinist3r.yip.core.network.twitch.HelixApi
import lol.sinist3r.yip.core.network.twitch.arr
import lol.sinist3r.yip.core.network.twitch.obj
import lol.sinist3r.yip.core.network.twitch.str
import timber.log.Timber

/** Everything needed to render one channel's chat, swapped atomically when it changes. */
data class ChannelCosmetics(
    val channelId: String,
    val emotes: Map<String, Emote> = emptyMap(),
    val twitchBadges: Map<String, Badge> = emptyMap(),
    val modBadge: Badge? = null,
    val vipBadge: Badge? = null,
    val sevenTvSetId: String? = null,
    val sevenTvUserId: String? = null,
    val loaded: Boolean = false,
) {
    /** Emotes grouped for the picker. */
    fun byProvider(): Map<EmoteProvider, List<Emote>> = emotes.values.groupBy { it.provider }
}

data class EmoteChange(val channelId: String, val text: String)

@Serializable
private data class SevenTvCatalogue(val paints: List<Paint>, val badges: List<Badge>)

private const val STYLE_TTL_MS = 10 * 60 * 1000L
private const val CATALOGUE_TTL_MS = 24 * 60 * 60 * 1000L

fun twitchEmote(id: String, code: String) = Emote(
    id = id,
    code = code,
    provider = EmoteProvider.TWITCH,
    urls = listOf("1.0", "2.0", "3.0").map { "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/$it" },
)

/**
 * Loads and merges Twitch, 7TV, BTTV and FFZ emotes and badges, and keeps them in sync with
 * 7TV's live events (emote set changes, paints, badges, personal emotes).
 */
@Singleton
class CosmeticsRepository @Inject constructor(
    private val seventv: SevenTvApi,
    private val seventvEvents: SevenTvEventClient,
    private val bttvFfz: BttvFfzApi,
    private val clientBadges: lol.sinist3r.yip.core.network.cosmetics.ClientBadgesApi,
    private val helix: HelixApi,
    private val gql: GqlApi,
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
    private val json: Json,
    @ApplicationContext private val context: Context,
    @AppScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val globalsMutex = Mutex()
    private var globalsLoaded = false

    private var globalTwitchBadges: Map<String, Badge> = emptyMap()
    private var global7tv: List<Emote> = emptyList()
    private var globalBttv: List<Emote> = emptyList()
    private var globalFfz: List<Emote> = emptyList()
    @Volatile private var userBadgeSets: List<UserBadges> = emptyList()
    /** Badge slots each Moltorino user chose to hide; applied to every provider. */
    @Volatile private var moltorinoHidden: Map<String, Set<String>> = emptyMap()

    private val channels = ConcurrentHashMap<String, MutableStateFlow<ChannelCosmetics>>()
    private val raw = ConcurrentHashMap<String, RawChannel>()

    private val paints = ConcurrentHashMap<String, Paint>()
    private val sevenTvBadges = ConcurrentHashMap<String, Badge>()
    private val userPaint = ConcurrentHashMap<String, String>() // twitch user id -> paint id
    private val userBadge = ConcurrentHashMap<String, String>() // twitch user id -> 7tv badge id
    private val personalSets = ConcurrentHashMap<String, List<Emote>>() // set id -> emotes
    private val userPersonalSets = ConcurrentHashMap<String, MutableSet<String>>() // twitch user id -> set ids

    private val changes = MutableSharedFlow<EmoteChange>(extraBufferCapacity = 32)
    val emoteChanges: SharedFlow<EmoteChange> = changes.asSharedFlow()

    // 7TV chatter lookups (v4): batched, cached for STYLE_TTL, results pushed to open chats.
    private val lookedUp = ConcurrentHashMap<String, Long>() // twitch user id -> last lookup
    private val pendingLookups = Channel<String>(Channel.UNLIMITED)
    private val catalogueMutex = Mutex()
    @Volatile private var catalogueLoaded = false
    private val updated = MutableSharedFlow<Set<String>>(extraBufferCapacity = 64)

    /** Twitch user ids whose paint or badges changed; open chats restyle their messages. */
    val cosmeticsUpdated: SharedFlow<Set<String>> = updated.asSharedFlow()

    private data class RawChannel(
        val seventv: List<Emote> = emptyList(),
        val bttv: List<Emote> = emptyList(),
        val ffz: List<Emote> = emptyList(),
        val twitch: List<Emote> = emptyList(),
        val twitchBadges: Map<String, Badge> = emptyMap(),
        val modBadge: Badge? = null,
        val vipBadge: Badge? = null,
        val setId: String? = null,
        val sevenTvUserId: String? = null,
    )

    init {
        scope.launch { seventvEvents.stream.collect(::onSevenTvEvent) }
        scope.launch(io) { lookupLoop() }
    }

    fun channel(channelId: String): StateFlow<ChannelCosmetics> =
        channels.getOrPut(channelId) { MutableStateFlow(ChannelCosmetics(channelId)) }

    suspend fun loadChannel(channelId: String) = coroutineScope {
        ensureGlobals()
        val tp = settings.current().thirdParty
        val stv = async { if (tp.seventvEmotes) attempt { seventv.channel(channelId) }.getOrNull() else null }
        val bttv = async { if (tp.bttvEmotes) attempt { bttvFfz.bttvChannel(channelId) }.getOrDefault(emptyList()) else emptyList() }
        val ffz = async { if (tp.ffzEmotes || tp.ffzModBadges) attempt { bttvFfz.ffzRoom(channelId) }.getOrNull() else null }
        val badges = async { attempt { twitchChannelBadges(channelId) }.getOrDefault(emptyMap()) }
        val twitch = async { attempt { twitchChannelEmotes(channelId) }.getOrDefault(emptyList()) }

        val s = stv.await()
        val room = ffz.await()
        val rc = RawChannel(
            seventv = s?.emotes.orEmpty(),
            bttv = bttv.await(),
            ffz = if (tp.ffzEmotes) room?.emotes.orEmpty() else emptyList(),
            twitch = twitch.await(),
            twitchBadges = badges.await(),
            modBadge = room?.modBadgeUrls?.takeIf { tp.ffzModBadges && it.isNotEmpty() }?.let { Badge("ffz:mod", "Moderator", it, BadgeSource.FFZ, "#00AD03") },
            vipBadge = room?.vipBadgeUrls?.takeIf { tp.ffzModBadges && it.isNotEmpty() }?.let { Badge("ffz:vip", "VIP", it, BadgeSource.FFZ) },
            setId = s?.emoteSetId,
            sevenTvUserId = s?.sevenTvUserId,
        )
        raw[channelId] = rc
        publish(channelId, tp)
        if (tp.seventvLiveUpdates || tp.seventvPaints != PaintMode.OFF || tp.seventvBadges || tp.seventvPersonalEmotes) {
            seventvEvents.watchChannel(channelId, rc.setId.takeIf { tp.seventvLiveUpdates }, rc.sevenTvUserId.takeIf { tp.seventvLiveUpdates })
        }
    }

    fun releaseChannel(channelId: String) {
        val rc = raw[channelId] ?: return
        seventvEvents.unwatchChannel(channelId, rc.setId, rc.sevenTvUserId)
    }

    /** Re-merge after a settings change (e.g. provider priority or a toggle). */
    fun refreshMerges() {
        val tp = settings.settings.value.thirdParty
        raw.keys.forEach { publish(it, tp) }
    }

    private fun publish(channelId: String, tp: ThirdPartySettings) {
        val rc = raw[channelId] ?: return
        val map = LinkedHashMap<String, Emote>(1024)
        // Lowest priority first so higher-priority providers overwrite on name clashes.
        fun put(list: List<Emote>) = list.forEach { map[it.code] = it }
        put(rc.twitch)
        val order = tp.priority.reversed()
        val globals = mapOf(ProviderSlot.SEVENTV to global7tv.takeIf { tp.seventvEmotes }, ProviderSlot.BTTV to globalBttv.takeIf { tp.bttvEmotes }, ProviderSlot.FFZ to globalFfz.takeIf { tp.ffzEmotes })
        val locals = mapOf(ProviderSlot.SEVENTV to rc.seventv.takeIf { tp.seventvEmotes }, ProviderSlot.BTTV to rc.bttv, ProviderSlot.FFZ to rc.ffz)
        order.forEach { slot -> globals[slot]?.let(::put) }
        order.forEach { slot -> locals[slot]?.let(::put) }
        if (!tp.zeroWidthEmotes) map.replaceAll { _, e -> if (e.zeroWidth) e.copy(zeroWidth = false) else e }
        channels.getOrPut(channelId) { MutableStateFlow(ChannelCosmetics(channelId)) }.value = ChannelCosmetics(
            channelId = channelId,
            emotes = map,
            twitchBadges = globalTwitchBadges + rc.twitchBadges,
            modBadge = rc.modBadge,
            vipBadge = rc.vipBadge,
            sevenTvSetId = rc.setId,
            sevenTvUserId = rc.sevenTvUserId,
            loaded = true,
        )
    }

    private suspend fun ensureGlobals() = globalsMutex.withLock {
        if (globalsLoaded) return@withLock
        coroutineScope {
            val tb = async { attempt { twitchGlobalBadges() }.getOrDefault(emptyMap()) }
            val s = async { attempt { seventv.globalEmotes() }.getOrDefault(emptyList()) }
            val b = async { attempt { bttvFfz.bttvGlobal() }.getOrDefault(emptyList()) }
            val f = async { attempt { bttvFfz.ffzGlobal() }.getOrDefault(emptyList()) }
            globalTwitchBadges = tb.await()
            global7tv = s.await()
            globalBttv = b.await()
            globalFfz = f.await()
        }
        globalsLoaded = true
        // Badge lists don't block emotes; open chats restyle once they land.
        scope.launch(io) { loadBadgeSets() }
    }

    private suspend fun loadBadgeSets() = coroutineScope {
        val bb = async { attempt { bttvFfz.bttvBadges() }.getOrNull() }
        val fb = async { attempt { bttvFfz.ffzBadges() }.getOrNull() }
        val cb = async { attempt { bttvFfz.chatterinoBadges() }.getOrNull() }
        val ap = async { attempt { clientBadges.ffzApBadges() }.getOrNull() }
        val hb = async { attempt { clientBadges.homiesBadges() }.getOrNull() }
        val hc = async { attempt { homiesCustom() }.getOrNull() }
        val mb = async { attempt { clientBadges.moltorinoBadges() }.getOrNull() }
        val molt = mb.await()
        // List order is display order after the 7TV badge.
        val sets = listOfNotNull(cb.await(), fb.await(), ap.await(), bb.await(), molt, hc.await(), hb.await())
        moltorinoHidden = molt?.hidden.orEmpty()
        userBadgeSets = sets
        updated.tryEmit(sets.flatMapTo(HashSet()) { it.users.keys })
    }

    /** Homies custom badge list is ~4 MB; keep it on disk for 12 hours. */
    private suspend fun homiesCustom(): UserBadges? {
        val file = File(context.cacheDir, "homies_custom_badges.json")
        val fresh = System.currentTimeMillis() - file.lastModified() < 12 * 60 * 60 * 1000L
        val text = (if (fresh) attempt { file.readText() }.getOrNull() else null)
            ?: clientBadges.homiesCustomRaw()?.also { t -> attempt { file.writeText(t) } }
            ?: attempt { file.readText() }.getOrNull() // stale copy beats nothing
            ?: return null
        return clientBadges.parseHomiesCustom(text)
    }

    private suspend fun twitchGlobalBadges(): Map<String, Badge> =
        if (accounts.userId() != null) helix.globalBadges().toBadgeMap() else gqlBadges(gql.globalBadges().arr("badges"))

    private suspend fun twitchChannelBadges(channelId: String): Map<String, Badge> =
        if (accounts.userId() != null) {
            helix.channelBadges(channelId).toBadgeMap()
        } else {
            gqlBadges(gql.channelBadges(channelId).obj("user")?.arr("broadcastBadges").orEmpty())
        }

    private fun List<lol.sinist3r.yip.core.network.twitch.HelixBadgeSet>.toBadgeMap(): Map<String, Badge> =
        flatMap { set -> set.versions.map { v -> "${set.setId}/${v.id}" to Badge("${set.setId}/${v.id}", v.title ?: set.setId, listOf(v.imageUrl1x, v.imageUrl2x, v.imageUrl4x), BadgeSource.TWITCH) } }.toMap()

    private fun gqlBadges(list: List<kotlinx.serialization.json.JsonElement>): Map<String, Badge> = list.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val set = o.str("setID") ?: return@mapNotNull null
        val ver = o.str("version") ?: return@mapNotNull null
        val url = o.str("imageURL") ?: return@mapNotNull null
        val base = url.substringBeforeLast('/')
        "$set/$ver" to Badge("$set/$ver", o.str("title") ?: set, listOf("$base/1", "$base/2", "$base/3"), BadgeSource.TWITCH)
    }.toMap()

    private suspend fun twitchChannelEmotes(channelId: String): List<Emote> {
        if (accounts.userId() == null) return emptyList()
        return helix.channelEmotes(channelId).data.map { twitchEmote(it.id, it.name).copy(isChannel = true, animated = "animated" in it.format) }
    }

    /** The signed-in user's own Twitch emotes (subs, follower, global) for the picker. */
    suspend fun userTwitchEmotes(channelId: String?): List<Emote> {
        val uid = accounts.userId() ?: return attempt { helix.globalEmotes().data.map { twitchEmote(it.id, it.name) } }.getOrDefault(emptyList())
        val out = mutableListOf<Emote>()
        var cursor: String? = null
        do {
            val page = attempt { helix.userEmotes(uid, channelId, cursor) }.getOrNull() ?: break
            out += page.data.map { twitchEmote(it.id, it.name).copy(animated = "animated" in it.format, isChannel = it.ownerId == channelId) }
            cursor = page.pagination?.cursor
        } while (cursor != null && out.size < 3000)
        return out
    }

    // region per-user lookups used by the chat pipeline

    fun paintFor(userId: String): Paint? = userPaint[userId]?.let(paints::get)

    fun sevenTvBadge(userId: String): Badge? =
        if (settings.settings.value.thirdParty.seventvBadges) userBadge[userId]?.let(sevenTvBadges::get) else null

    /** Queues a 7TV style lookup for a chatter unless one ran recently. Cheap; call per message. */
    fun noteChatter(userId: String) {
        if (userId.isEmpty()) return
        val tp = settings.settings.value.thirdParty
        if (tp.seventvPaints == PaintMode.OFF && !tp.seventvBadges && !tp.seventvPersonalEmotes) return
        val now = System.currentTimeMillis()
        val last = lookedUp[userId]
        if (last != null && now - last < STYLE_TTL_MS) return
        lookedUp[userId] = now
        if (lookedUp.size > 20_000) lookedUp.clear()
        pendingLookups.trySend(userId)
    }

    private suspend fun lookupLoop() {
        while (true) {
            val first = pendingLookups.receive()
            delay(250) // let a burst (history load, busy chat) collect into one request
            val batch = linkedSetOf(first)
            while (batch.size < SevenTvApi.MAX_STYLE_BATCH) batch += pendingLookups.tryReceive().getOrNull() ?: break
            ensureCatalogue()
            attempt { seventv.styles(batch) }
                .onSuccess { applyStyles(batch, it) }
                .onFailure { e -> Timber.w(e, "7TV style lookup failed"); batch.forEach(lookedUp::remove) }
        }
    }

    private suspend fun applyStyles(batch: Set<String>, styles: Map<String, SevenTvStyle>) {
        val changed = HashSet<String>()
        for (uid in batch) {
            val st = styles[uid]
            st?.paintId?.let { if (!paints.containsKey(it)) attempt { seventv.paint(it) }.getOrNull()?.let { p -> paints[p.id] = p } }
            st?.badgeId?.let { if (!sevenTvBadges.containsKey(it)) attempt { seventv.badge(it) }.getOrNull()?.let { b -> sevenTvBadges[it] = b } }
            if (setOrClear(userPaint, uid, st?.paintId)) changed += uid
            if (setOrClear(userBadge, uid, st?.badgeId)) changed += uid
            // Personal emotes need an active subscription; equipped cosmetics are the public signal for that.
            val personal = st?.personalSetId?.takeIf { st.paintId != null || st.badgeId != null }
            if (personal != null) {
                userPersonalSets.getOrPut(uid) { ConcurrentHashMap.newKeySet() } += personal
                if (!personalSets.containsKey(personal)) {
                    attempt { seventv.emoteSet(personal, personal = true) }.getOrNull()?.let { (_, emotes) -> personalSets[personal] = emotes }
                }
            }
        }
        if (changed.isNotEmpty()) updated.tryEmit(changed)
    }

    private fun setOrClear(map: ConcurrentHashMap<String, String>, key: String, value: String?): Boolean =
        if (value == null) map.remove(key) != null else map.put(key, value) != value

    /** Paint and badge definitions, cached on disk for a day (the paint list is ~1 MB). */
    private suspend fun ensureCatalogue() = catalogueMutex.withLock {
        if (catalogueLoaded) return@withLock
        val file = File(context.cacheDir, "7tv_catalogue_v2.json")
        val cached = withContext(io) {
            attempt {
                if (System.currentTimeMillis() - file.lastModified() > CATALOGUE_TTL_MS) null
                else json.decodeFromString<SevenTvCatalogue>(file.readText())
            }.getOrNull()
        }
        val cat = cached ?: coroutineScope {
            val p = async { attempt { seventv.paints() }.getOrNull() }
            val b = async { attempt { seventv.badges() }.getOrNull() }
            val fresh = SevenTvCatalogue(p.await() ?: return@coroutineScope null, b.await() ?: return@coroutineScope null)
            withContext(io) { attempt { file.writeText(json.encodeToString(SevenTvCatalogue.serializer(), fresh)) } }
            fresh
        }
        if (cat == null) return@withLock // retry on the next batch
        cat.paints.forEach { paints.putIfAbsent(it.id, it) }
        cat.badges.forEach { sevenTvBadges.putIfAbsent(it.id.removePrefix("7tv:"), it) }
        catalogueLoaded = true
        Timber.i("7TV catalogue: %d paints, %d badges", cat.paints.size, cat.badges.size)
    }

    fun extraBadges(userId: String): List<Badge> {
        val tp = settings.settings.value.thirdParty
        val hidden = moltorinoHidden[userId].orEmpty()
        val out = mutableListOf<Badge>()
        sevenTvBadge(userId)?.takeIf { "7" !in hidden }?.let(out::add)
        userBadgeSets.forEach { set ->
            set.users[userId]?.forEach { id ->
                val b = set.badges[id] ?: return@forEach
                val (allowed, slot) = when (b.source) {
                    BadgeSource.FFZ -> tp.ffzBadges to "ff"
                    BadgeSource.FFZAP -> tp.ffzApBadges to "fa"
                    BadgeSource.BTTV -> tp.bttvBadges to "bt"
                    BadgeSource.CHATTERINO -> tp.chatterinoBadges to "c"
                    BadgeSource.HOMIES -> tp.homiesBadges to "hs"
                    BadgeSource.HOMIES_CUSTOM -> tp.homiesCustomBadges to "hc"
                    BadgeSource.MOLTORINO -> tp.moltorinoBadges to "m"
                    else -> true to ""
                }
                if (allowed && slot !in hidden) out += b
            }
        }
        return out
    }

    fun personalEmotes(userId: String): Map<String, Emote> {
        if (!settings.settings.value.thirdParty.seventvPersonalEmotes) return emptyMap()
        val sets = userPersonalSets[userId] ?: return emptyMap()
        return sets.flatMap { personalSets[it].orEmpty() }.associateBy { it.code }
    }

    // endregion

    private fun onSevenTvEvent(ev: SevenTvEvent) {
        when (ev) {
            is SevenTvEvent.PaintDefined -> paints[ev.paint.id] = ev.paint
            is SevenTvEvent.BadgeDefined -> sevenTvBadges[ev.badge.id.removePrefix("7tv:")] = ev.badge
            is SevenTvEvent.Entitlement -> when (ev.kind) {
                "PAINT" -> {
                    if (ev.granted) userPaint[ev.twitchUserId] = ev.refId else userPaint.remove(ev.twitchUserId, ev.refId)
                    updated.tryEmit(setOf(ev.twitchUserId))
                }
                "BADGE" -> {
                    if (ev.granted) userBadge[ev.twitchUserId] = ev.refId else userBadge.remove(ev.twitchUserId, ev.refId)
                    updated.tryEmit(setOf(ev.twitchUserId))
                }
                "EMOTE_SET" -> if (ev.granted) {
                    userPersonalSets.getOrPut(ev.twitchUserId) { ConcurrentHashMap.newKeySet() } += ev.refId
                    if (!personalSets.containsKey(ev.refId)) scope.launch {
                        attempt { seventv.emoteSet(ev.refId, personal = true) }.getOrNull()?.let { (isPersonal, emotes) ->
                            if (isPersonal) personalSets[ev.refId] = emotes else userPersonalSets[ev.twitchUserId]?.remove(ev.refId)
                        }
                    }
                } else {
                    userPersonalSets[ev.twitchUserId]?.remove(ev.refId)
                }
            }
            is SevenTvEvent.EmoteAdded -> mutateSet(ev.setId, "${ev.actor ?: "Someone"} added ${ev.emote.code}") { it.filterNot { e -> e.id == ev.emote.id } + ev.emote }
                ?: personalSets.computeIfPresent(ev.setId) { _, l -> l + ev.emote.copy(isPersonal = true) }.let { }
            is SevenTvEvent.EmoteRemoved -> mutateSet(ev.setId, "${ev.actor ?: "Someone"} removed ${ev.code ?: "an emote"}") { it.filterNot { e -> e.id == ev.emoteId } }
                ?: personalSets.computeIfPresent(ev.setId) { _, l -> l.filterNot { it.id == ev.emoteId } }.let { }
            is SevenTvEvent.EmoteRenamed -> mutateSet(ev.setId, "${ev.actor ?: "Someone"} renamed ${ev.oldCode} to ${ev.newCode}") {
                it.map { e -> if (e.id == ev.emoteId) e.copy(code = ev.newCode) else e }
            }
            is SevenTvEvent.ActiveSetChanged -> raw.entries.firstOrNull { it.value.sevenTvUserId == ev.sevenTvUserId }?.let { (channelId, _) ->
                scope.launch {
                    Timber.i("7TV set changed for %s, reloading", channelId)
                    loadChannel(channelId)
                    changes.tryEmit(EmoteChange(channelId, "Channel switched to a different 7TV emote set"))
                }
            }
        }
    }

    /** Applies a 7TV set change to every channel using that set. Returns null if no channel matched. */
    private fun mutateSet(setId: String, notice: String, block: (List<Emote>) -> List<Emote>): Unit? {
        val matches = raw.entries.filter { it.value.setId == setId }
        if (matches.isEmpty()) return null
        val tp = settings.settings.value.thirdParty
        matches.forEach { (channelId, rc) ->
            raw[channelId] = rc.copy(seventv = block(rc.seventv))
            publish(channelId, tp)
            changes.tryEmit(EmoteChange(channelId, notice))
        }
        return Unit
    }

    fun clear() {
        channels.values.forEach { flow -> flow.update { ChannelCosmetics(it.channelId) } }
        raw.clear()
        globalsLoaded = false
    }
}
