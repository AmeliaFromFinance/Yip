package dev.yipchat.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yipchat.core.common.attempt
import dev.yipchat.core.data.account.AccountRepository
import dev.yipchat.core.data.twitch.TwitchRepository
import dev.yipchat.core.designsystem.component.Avatar
import dev.yipchat.core.model.Category
import dev.yipchat.core.model.LiveStream
import dev.yipchat.core.network.twitch.HelixAdSchedule
import dev.yipchat.core.network.twitch.HelixApi
import dev.yipchat.core.network.twitch.HelixUnbanRequest
import dev.yipchat.core.network.twitch.TwitchAuthApi
import javax.inject.Inject
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class StreamManagerState(
    val loading: Boolean = true,
    val signedIn: Boolean = true,
    val missingScopes: Boolean = false,
    val userId: String = "",
    val login: String = "",
    val displayName: String = "",
    val avatar: String? = null,
    val live: LiveStream? = null,
    val title: String = "",
    val category: Pair<String, String>? = null, // id to name
    val tags: List<String> = emptyList(),
    val language: String = "",
    val saved: Saved = Saved(),
    val saving: Boolean = false,
    val ads: HelixAdSchedule? = null,
    val chatterCount: Int? = null,
    val chatters: List<String> = emptyList(),
    val unbanRequests: List<HelixUnbanRequest> = emptyList(),
    val error: String? = null,
) {
    data class Saved(val title: String = "", val categoryId: String? = null, val tags: List<String> = emptyList(), val language: String = "")
    val dirty get() = title != saved.title || category?.first != saved.categoryId || tags != saved.tags || language != saved.language
}

/** Your own channel: stream info, markers, ads, unban requests and who's in chat. */
@HiltViewModel
class StreamManagerViewModel @Inject constructor(
    private val helix: HelixApi,
    private val twitch: TwitchRepository,
    private val accounts: AccountRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(StreamManagerState())
    val state = _state.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    private var poller: Job? = null

    init { load() }

    fun load() = viewModelScope.launch {
        val me = accounts.state.value.active
        if (me == null) { _state.value = StreamManagerState(loading = false, signedIn = false); return@launch }
        val missing = TwitchAuthApi.STREAM_MANAGER_SCOPES.any { it !in me.scopes }
        _state.update { it.copy(loading = true, userId = me.userId, login = me.login, displayName = me.displayName, avatar = me.profileImageUrl, missingScopes = missing, error = null) }
        attempt { helix.channelInfo(listOf(me.userId)).firstOrNull() }
            .onSuccess { c ->
                val cat = c?.gameId?.takeIf { it.isNotEmpty() }?.let { it to c.gameName.orEmpty() }
                val saved = StreamManagerState.Saved(c?.title.orEmpty(), cat?.first, c?.tags.orEmpty(), c?.broadcasterLanguage.orEmpty())
                _state.update { it.copy(title = saved.title, category = cat, tags = saved.tags, language = saved.language, saved = saved) }
            }
            .onFailure { e -> _state.update { it.copy(error = e.message) } }
        refreshLive()
        _state.update { it.copy(loading = false) }
        poller?.cancel()
        poller = launch { while (isActive) { delay(30_000); refreshLive() } }
    }

    private suspend fun refreshLive() {
        val s = _state.value
        val live = attempt { twitch.channel(id = s.userId)?.stream }.getOrNull()
        val ads = if (!s.missingScopes) viewModelScope.async { attempt { helix.adSchedule(s.userId) }.getOrNull() } else null
        val chatters = if (!s.missingScopes) viewModelScope.async { attempt { helix.chatters(s.userId, s.userId) }.getOrNull() } else null
        val unban = if (!s.missingScopes) viewModelScope.async { attempt { helix.unbanRequests(s.userId, s.userId) }.getOrNull() } else null
        val page = chatters?.await()
        _state.update {
            it.copy(
                live = live,
                ads = ads?.await() ?: it.ads,
                chatterCount = page?.total ?: page?.data?.size ?: it.chatterCount,
                chatters = page?.data?.map { c -> c.userName }?.sortedBy { n -> n.lowercase() } ?: it.chatters,
                unbanRequests = unban?.await() ?: it.unbanRequests,
            )
        }
    }

    fun setTitle(v: String) = _state.update { it.copy(title = v.take(140)) }
    fun setCategory(c: Category) = _state.update { it.copy(category = c.id to c.name) }
    fun setLanguage(v: String) = _state.update { it.copy(language = v.lowercase().filter(Char::isLetter).take(2)) }
    fun removeTag(t: String) = _state.update { it.copy(tags = it.tags - t) }

    /** Twitch tags: up to 10, 25 characters, letters and numbers only. */
    fun addTag(raw: String): String? {
        val t = raw.trim().filter { it.isLetterOrDigit() }
        return when {
            t.isEmpty() -> "Letters and numbers only"
            t.length > 25 -> "25 characters at most"
            _state.value.tags.size >= 10 -> "10 tags at most"
            _state.value.tags.any { it.equals(t, true) } -> "Already added"
            else -> { _state.update { it.copy(tags = it.tags + t) }; null }
        }
    }

    fun revert() = _state.update { s -> s.copy(title = s.saved.title, tags = s.saved.tags, language = s.saved.language, category = s.saved.categoryId?.let { id -> id to (s.category?.takeIf { it.first == id }?.second ?: "") }) }

    fun save() = viewModelScope.launch {
        val s = _state.value
        _state.update { it.copy(saving = true) }
        attempt {
            helix.updateChannel(
                s.userId,
                title = s.title.takeIf { it != s.saved.title },
                gameId = s.category?.first?.takeIf { it != s.saved.categoryId },
                tags = s.tags.takeIf { it != s.saved.tags },
                language = s.language.takeIf { it != s.saved.language && it.length == 2 },
            )
        }.onSuccess {
            _state.update { it.copy(saving = false, saved = StreamManagerState.Saved(it.title, it.category?.first, it.tags, it.language)) }
            _messages.emit("Stream info saved")
        }.onFailure { e -> _state.update { it.copy(saving = false) }; _messages.emit("Couldn't save: ${e.message}") }
    }

    fun addMarker(description: String) = act("Marker added") {
        val m = helix.createMarker(_state.value.userId, description)
        m?.let { "Marker added at ${it.positionSeconds.clock()}" }
    }

    fun runAd(seconds: Int) = act(null) {
        val r = helix.startCommercial(_state.value.userId, seconds)
        refreshLive()
        r?.let { "Running a ${it.length}s ad" + if (it.retryAfter > 0) ". Next one available in ${it.retryAfter / 60} min." else "" } ?: "Ad started"
    }

    fun snooze() = act(null) {
        val a = helix.snoozeNextAd(_state.value.userId)
        refreshLive()
        a?.let { "Next ad snoozed. ${it.snoozeCount} snoozes left." } ?: "Next ad snoozed"
    }

    fun resolveUnban(r: HelixUnbanRequest, approve: Boolean) = act(if (approve) "${r.userName} unbanned" else "Request from ${r.userName} denied") {
        helix.resolveUnbanRequest(_state.value.userId, _state.value.userId, r.id, approve)
        _state.update { it.copy(unbanRequests = it.unbanRequests - r) }
        null
    }

    private fun act(done: String?, block: suspend () -> String?) = viewModelScope.launch {
        attempt { block() }
            .onSuccess { msg -> (msg ?: done)?.let { _messages.emit(it) } }
            .onFailure { e -> _messages.emit("Twitch said no: ${e.message?.substringAfter(": ")?.take(160)}") }
    }

    suspend fun searchCategories(q: String): List<Category> =
        if (q.trim().length < 2) emptyList() else attempt { twitch.searchCategories(q.trim(), null).items }.getOrDefault(emptyList())
}

private fun Int.clock(): String = "%d:%02d:%02d".format(this / 3600, this / 60 % 60, this % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamManagerScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit, onSignIn: () -> Unit, viewModel: StreamManagerViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    var picker by remember { mutableStateOf(false) }
    var marker by remember { mutableStateOf(false) }
    var adLength by remember { mutableStateOf<Int?>(null) }
    var chatterFilter by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stream manager") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { viewModel.load() }) { Icon(Icons.Outlined.Refresh, "Refresh") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!s.signedIn) {
            Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sign in to manage your stream.")
                Button(onClick = onSignIn) { Text("Sign in") }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (s.missingScopes) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sign in again to unlock everything", style = MaterialTheme.typography.titleSmall)
                        Text("Ads, markers, unban requests and the viewer list need permissions your current sign-in doesn't have. Editing stream info may also fail until you do.", style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(onClick = onSignIn) { Text("Sign in again") }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(s.avatar, s.displayName, 48.dp, live = s.live != null)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(s.displayName, style = MaterialTheme.typography.titleLarge)
                        val live = s.live
                        Text(
                            if (live != null) "Live · ${live.viewerCount} watching · ${(Clock.System.now() - live.startedAt).inWholeSeconds.toInt().clock()}" else "Offline",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (live != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onOpenChat(s.login) }) { Icon(Icons.AutoMirrored.Outlined.Chat, "Open my chat") }
                }
            }

            item { SectionTitle("Stream info") }
            item {
                OutlinedTextField(
                    value = s.title, onValueChange = viewModel::setTitle,
                    label = { Text("Title") }, supportingText = { Text("${s.title.length}/140") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(s.category?.second?.ifBlank { null } ?: "No category") },
                    overlineContent = { Text("Category") },
                    trailingContent = { TextButton(onClick = { picker = true }) { Text("Change") } },
                    modifier = Modifier.clickable { picker = true },
                )
            }
            item {
                var tag by remember { mutableStateOf("") }
                var tagError by remember { mutableStateOf<String?>(null) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.tags.forEach { t ->
                            InputChip(selected = false, onClick = { viewModel.removeTag(t) }, label = { Text(t) },
                                trailingIcon = { Icon(Icons.Outlined.Close, "Remove $t", Modifier.size(16.dp)) })
                        }
                    }
                    OutlinedTextField(
                        value = tag, onValueChange = { tag = it; tagError = null },
                        label = { Text("Add tag (${s.tags.size}/10)") }, isError = tagError != null,
                        supportingText = tagError?.let { e -> { Text(e) } },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { tagError = viewModel.addTag(tag); if (tagError == null) tag = "" }),
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = s.language, onValueChange = viewModel::setLanguage,
                    label = { Text("Language") }, supportingText = { Text("Two-letter code, e.g. en") },
                    singleLine = true, modifier = Modifier.width(160.dp),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::save, enabled = s.dirty && !s.saving) { Text(if (s.saving) "Saving…" else "Save") }
                    if (s.dirty) OutlinedButton(onClick = viewModel::revert) { Text("Undo changes") }
                }
            }

            item { SectionTitle("While live") }
            item {
                val live = s.live != null
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { marker = true }, enabled = live) { Icon(Icons.Outlined.Bookmark, null, Modifier.size(18.dp)); Text(" Add marker") }
                    OutlinedButton(onClick = { adLength = 60 }, enabled = live) { Icon(Icons.Outlined.Tv, null, Modifier.size(18.dp)); Text(" Run ad") }
                    OutlinedButton(onClick = viewModel::snooze, enabled = live && (s.ads?.snoozeCount ?: 0) > 0) { Icon(Icons.Outlined.Snooze, null, Modifier.size(18.dp)); Text(" Snooze next ad") }
                }
            }
            s.ads?.let { a ->
                item {
                    val now = System.currentTimeMillis() / 1000
                    val parts = buildList {
                        if (a.nextAdAt > now) add("Next ad in ${(a.nextAdAt - now) / 60} min (${a.duration}s)")
                        if (a.prerollFreeTime > 0) add("Pre-roll free for ${a.prerollFreeTime / 60} min")
                        add("${a.snoozeCount} snoozes left")
                    }
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item { SectionTitle("Unban requests") }
            if (s.unbanRequests.isEmpty()) item { Text("None waiting.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(s.unbanRequests, key = { "unban-${it.id}" }) { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(r.userName, style = MaterialTheme.typography.titleSmall)
                        if (r.text.isNotBlank()) Text(r.text, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { viewModel.resolveUnban(r, true) }) { Text("Unban") }
                            OutlinedButton(onClick = { viewModel.resolveUnban(r, false) }) { Text("Deny") }
                        }
                    }
                }
            }
            item { Text("AutoMod holds show up in your chat with Allow and Deny.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            item { SectionTitle("In chat" + (s.chatterCount?.let { " · $it" } ?: "")) }
            if (s.chatters.isNotEmpty()) item {
                OutlinedTextField(value = chatterFilter, onValueChange = { chatterFilter = it }, label = { Text("Find a viewer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            val shown = s.chatters.filter { chatterFilter.isBlank() || it.contains(chatterFilter.trim(), true) }.take(200)
            items(shown, key = { "chatter-$it" }) { name ->
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = 4.dp))
            }
            if (s.chatters.isEmpty() && !s.missingScopes) item { Text(if (s.live != null) "Nobody yet." else "Shows who's in chat while you're live.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Spacer(Modifier.heightIn(min = 24.dp)) }
        }
    }

    if (picker) CategoryPicker(viewModel, onPick = { viewModel.setCategory(it); picker = false }, onDismiss = { picker = false })
    if (marker) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { marker = false },
            title = { Text("Add stream marker") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(140) }, label = { Text("Note (optional)") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { viewModel.addMarker(text); marker = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { marker = false }) { Text("Cancel") } },
        )
    }
    adLength?.let { len ->
        AlertDialog(
            onDismissRequest = { adLength = null },
            title = { Text("Run an ad") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Viewers without Turbo or a sub will see it.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(30, 60, 90, 120, 150, 180).forEach { n ->
                            FilterChip(selected = n == len, onClick = { adLength = n }, label = { Text("${n}s") })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.runAd(len); adLength = null }) { Text("Run ${len}s ad") } },
            dismissButton = { TextButton(onClick = { adLength = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun CategoryPicker(viewModel: StreamManagerViewModel, onPick: (Category) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Category>()) }
    LaunchedEffect(query) {
        delay(300)
        results = viewModel.searchCategories(query)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(results, key = { it.id }) { c ->
                        ListItem(
                            headlineContent = { Text(c.name) },
                            leadingContent = {
                                AsyncImage(model = c.boxArt(54, 72), contentDescription = null, modifier = Modifier.size(27.dp, 36.dp))
                            },
                            modifier = Modifier.clickable { onPick(c) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
