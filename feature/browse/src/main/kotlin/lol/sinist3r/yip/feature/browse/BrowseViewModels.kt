package lol.sinist3r.yip.feature.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.data.account.AccountRepository
import lol.sinist3r.yip.core.data.history.HistoryEntry
import lol.sinist3r.yip.core.data.history.HistoryRepository
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.data.twitch.TwitchRepository
import lol.sinist3r.yip.core.designsystem.util.Paged
import lol.sinist3r.yip.core.model.AppSettings
import lol.sinist3r.yip.core.model.Category
import lol.sinist3r.yip.core.model.Channel
import lol.sinist3r.yip.core.model.Clip
import lol.sinist3r.yip.core.model.FollowedChannel
import lol.sinist3r.yip.core.model.FollowingSort
import lol.sinist3r.yip.core.model.LiveStream
import lol.sinist3r.yip.core.model.Page
import lol.sinist3r.yip.core.model.ScheduleSegment
import lol.sinist3r.yip.core.model.Video
import lol.sinist3r.yip.core.model.VideoType

/** Small helper so every list screen pages the same way. */
internal class Pager<T>(private val scope: kotlinx.coroutines.CoroutineScope, private val load: suspend (String?) -> Page<T>) {
    val state = MutableStateFlow(Paged<T>())
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        state.value = Paged(loading = true)
        job = scope.launch { fetch(null, reset = true) }
    }

    fun loadMore() {
        val s = state.value
        if (s.loading || s.endReached || !s.initialized) return
        state.update { it.copy(loading = true) }
        job = scope.launch { fetch(s.cursor, reset = false) }
    }

    private suspend fun fetch(cursor: String?, reset: Boolean) {
        attempt { load(cursor) }
            .onSuccess { page ->
                state.update {
                    val items = if (reset) page.items else it.items + page.items
                    Paged(items.distinctBy { item -> item.hashKey() }, page.cursor, false, page.cursor == null, null, true)
                }
            }
            .onFailure { e -> state.update { it.copy(loading = false, error = e.message ?: "Couldn't load", initialized = true) } }
    }

    private fun Any?.hashKey(): Any? = when (this) {
        is LiveStream -> id
        is Category -> id
        is Channel -> id
        is Video -> id
        is Clip -> id
        else -> this
    }
}

data class FollowingUiState(
    val signedIn: Boolean = false,
    val loading: Boolean = true,
    val live: List<LiveStream> = emptyList(),
    val offline: List<FollowedChannel> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class FollowingViewModel @Inject constructor(
    private val twitch: TwitchRepository,
    private val accounts: AccountRepository,
    private val history: HistoryRepository,
    val settingsRepo: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(FollowingUiState())
    val settings: StateFlow<AppSettings> = settingsRepo.settings

    val state: StateFlow<FollowingUiState> = combine(_state, history.recent, settings) { s, h, st ->
        s.copy(live = s.live.sortedWith(st.general.followingSort), history = if (st.general.continueWatching) h.take(12) else emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FollowingUiState())

    init {
        viewModelScope.launch {
            accounts.isSignedIn.distinctUntilChanged().collect { signed ->
                _state.update { it.copy(signedIn = signed) }
                refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            if (!_state.value.signedIn) {
                _state.update { it.copy(loading = false, live = emptyList(), offline = emptyList()) }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            val live = attempt { twitch.followedLive() }
            val offline = if (settings.value.general.showOfflineFollowed) attempt { twitch.followedChannels() }.getOrDefault(emptyList()) else emptyList()
            val liveIds = live.getOrDefault(emptyList()).map { it.userId }.toSet()
            _state.update {
                it.copy(
                    loading = false,
                    live = live.getOrDefault(it.live),
                    offline = offline.filterNot { c -> c.id in liveIds }.sortedBy { c -> c.displayName.lowercase() },
                    error = live.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun removeHistory(key: String) = viewModelScope.launch { history.remove(key) }

    fun setSort(sort: FollowingSort) = viewModelScope.launch {
        settingsRepo.update { it.copy(general = it.general.copy(followingSort = sort)) }
    }

    private fun List<LiveStream>.sortedWith(sort: FollowingSort) = when (sort) {
        FollowingSort.VIEWERS -> sortedByDescending { it.viewerCount }
        FollowingSort.RECENTLY_STARTED -> sortedByDescending { it.startedAt }
        FollowingSort.NAME -> sortedBy { it.userName.lowercase() }
        FollowingSort.CATEGORY -> sortedWith(compareBy<LiveStream> { it.gameName.orEmpty() }.thenByDescending { it.viewerCount })
    }
}

@HiltViewModel
class BrowseViewModel @Inject constructor(twitch: TwitchRepository, settingsRepo: SettingsRepository) : ViewModel() {
    val settings = settingsRepo.settings
    private val streamsPager = Pager(viewModelScope) { twitch.topStreams(it) }
    private val categoriesPager = Pager(viewModelScope) { twitch.topCategories(it) }
    val streams = streamsPager.state.asStateFlow()
    val categories = categoriesPager.state.asStateFlow()

    init {
        streamsPager.refresh()
        categoriesPager.refresh()
    }

    fun refreshStreams() = streamsPager.refresh()
    fun refreshCategories() = categoriesPager.refresh()
    fun moreStreams() = streamsPager.loadMore()
    fun moreCategories() = categoriesPager.loadMore()
}

@HiltViewModel
class CategoryViewModel @Inject constructor(savedState: SavedStateHandle, twitch: TwitchRepository, settingsRepo: SettingsRepository) : ViewModel() {
    val categoryId: String = checkNotNull(savedState["id"])
    val categoryName: String = savedState["name"] ?: ""
    val settings = settingsRepo.settings
    private val pager = Pager(viewModelScope) { twitch.categoryStreams(categoryId, it) }
    val streams = pager.state.asStateFlow()

    init { pager.refresh() }
    fun refresh() = pager.refresh()
    fun more() = pager.loadMore()
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val twitch: TwitchRepository,
    settingsRepo: SettingsRepository,
    private val recentRepo: lol.sinist3r.yip.core.data.search.RecentSearchRepository,
) : ViewModel() {
    val settings = settingsRepo.settings
    val recent = recentRepo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun remember(item: lol.sinist3r.yip.core.model.RecentSearch) = viewModelScope.launch { recentRepo.add(item) }
    fun forget(item: lol.sinist3r.yip.core.model.RecentSearch) = viewModelScope.launch { recentRepo.remove(item) }
    fun clearRecent() = viewModelScope.launch { recentRepo.clear() }

    /** Saves what was typed when the keyboard's search key is pressed. */
    fun rememberQuery() {
        val q = _query.value.trim()
        if (q.length >= 2) remember(lol.sinist3r.yip.core.model.RecentSearch(lol.sinist3r.yip.core.model.RecentKind.QUERY, q, q))
    }

    /**
     * Opening a result replaces the query that found it: only the channel or category is kept.
     * [query] is the search that was on screen when the result was tapped.
     */
    fun rememberResult(item: lol.sinist3r.yip.core.model.RecentSearch, query: String) = viewModelScope.launch {
        val q = query.trim()
        if (q.isNotEmpty()) recentRepo.remove(lol.sinist3r.yip.core.model.RecentSearch(lol.sinist3r.yip.core.model.RecentKind.QUERY, q, q))
        recentRepo.add(item)
    }
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()
    private var channelPager: Pager<Channel>? = null
    private var categoryPager: Pager<Category>? = null
    val channels = MutableStateFlow(Paged<Channel>())
    val categories = MutableStateFlow(Paged<Category>())
    private var relay: Job? = null

    init {
        viewModelScope.launch {
            _query.map { it.trim() }.distinctUntilChanged().debounce(350).filter { it.length >= 2 }.collect(::search)
        }
    }

    fun onQuery(q: String) = _query.update { q }

    private fun search(q: String) {
        relay?.cancel()
        val cp = Pager(viewModelScope) { twitch.searchChannels(q, it) }.also { channelPager = it }
        val gp = Pager(viewModelScope) { twitch.searchCategories(q, it) }.also { categoryPager = it }
        relay = viewModelScope.launch {
            launch { cp.state.collect { channels.value = it } }
            launch { gp.state.collect { categories.value = it } }
        }
        cp.refresh()
        gp.refresh()
    }

    fun moreChannels() = channelPager?.loadMore()
    fun moreCategories() = categoryPager?.loadMore()
}

data class ChannelHeader(
    val info: TwitchRepository.ChannelInfo? = null,
    val following: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val schedule: List<ScheduleSegment> = emptyList(),
    val isMe: Boolean = false,
)

@HiltViewModel
class ChannelViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val twitch: TwitchRepository,
    settingsRepo: SettingsRepository,
    private val accounts: lol.sinist3r.yip.core.data.account.AccountRepository,
) : ViewModel() {
    private val login: String = checkNotNull(savedState["login"])
    val settings = settingsRepo.settings
    private val _header = MutableStateFlow(ChannelHeader())
    val header = _header.asStateFlow()

    private var videoType: VideoType? = null
    private var clipPeriod = TwitchRepository.ClipPeriod.WEEK
    private var videosPager: Pager<Video>? = null
    private var clipsPager: Pager<Clip>? = null
    val videos = MutableStateFlow(Paged<Video>())
    val clips = MutableStateFlow(Paged<Clip>())
    private var relay: Job? = null

    init { load() }

    fun load() = viewModelScope.launch {
        _header.value = ChannelHeader(loading = true)
        attempt { twitch.channel(login = login) }
            .onSuccess { info ->
                if (info == null) {
                    _header.value = ChannelHeader(loading = false, error = "Channel not found")
                    return@onSuccess
                }
                _header.value = ChannelHeader(info, loading = false, isMe = info.user.id == accounts.state.value.active?.userId)
                launch { _header.update { it.copy(following = twitch.isFollowing(info.user.id)) } }
                launch { _header.update { it.copy(schedule = attempt { twitch.schedule(info.user.id) }.getOrDefault(emptyList())) } }
                setPagers(info.user.id, info.user.login)
            }
            .onFailure { _header.value = ChannelHeader(loading = false, error = it.message) }
    }

    private fun setPagers(id: String, login: String) {
        relay?.cancel()
        val vp = Pager(viewModelScope) { twitch.videos(id, login, videoType, it) }.also { videosPager = it }
        val cp = Pager(viewModelScope) { twitch.clips(id, login, clipPeriod, it) }.also { clipsPager = it }
        relay = viewModelScope.launch {
            launch { vp.state.collect { videos.value = it } }
            launch { cp.state.collect { clips.value = it } }
        }
        vp.refresh()
        cp.refresh()
    }

    fun setVideoType(type: VideoType?) {
        videoType = type
        videosPager?.refresh()
    }

    fun setClipPeriod(period: TwitchRepository.ClipPeriod) {
        clipPeriod = period
        clipsPager?.refresh()
    }

    fun moreVideos() = videosPager?.loadMore()
    fun moreClips() = clipsPager?.loadMore()
}
