package dev.yipchat.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yipchat.core.common.attempt
import dev.yipchat.core.data.follow.FollowRepository
import dev.yipchat.core.data.follow.FollowResult
import dev.yipchat.core.data.twitch.TwitchRepository
import dev.yipchat.core.model.FollowedChannel
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ManagedFollow(val channel: FollowedChannel, val lastLive: Instant?)

enum class FollowsSort(val label: String) { LAST_LIVE("Last live"), FOLLOWED("Followed"), NAME("Name") }

data class ManageFollowsState(
    val loading: Boolean = true,
    val items: List<ManagedFollow> = emptyList(),
    val selected: Set<String> = emptySet(),
    /** (done, total) while unfollowing. */
    val progress: Pair<Int, Int>? = null,
    val message: String? = null,
)

@HiltViewModel
class ManageFollowsViewModel @Inject constructor(private val twitch: TwitchRepository, private val follows: FollowRepository) : ViewModel() {
    private val _state = MutableStateFlow(ManageFollowsState())
    val state = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true) }
        val list = attempt { twitch.followedChannels() }.getOrDefault(emptyList())
        val last = twitch.lastLive(list.map { it.id })
        _state.update { it.copy(loading = false, items = list.map { c -> ManagedFollow(c, last[c.id]) }, selected = emptySet()) }
    }

    fun toggle(id: String) = _state.update { s -> s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id) }
    fun select(ids: Collection<String>) = _state.update { it.copy(selected = ids.toSet()) }

    /** One at a time, so Twitch doesn't rate-limit a big batch. */
    fun unfollowSelected() = viewModelScope.launch {
        val ids = _state.value.selected.toList()
        var done = 0
        var failed: String? = null
        for (id in ids) {
            _state.update { it.copy(progress = done to ids.size) }
            when (val r = follows.setFollowing(id, false)) {
                FollowResult.Done -> done++
                FollowResult.NeedsWeb -> { failed = "Connect twitch.tv under Settings → Accounts to unfollow"; break }
                is FollowResult.Failed -> failed = r.reason
            }
        }
        val gone = ids.take(done).toSet()
        _state.update { s ->
            s.copy(
                progress = null, selected = emptySet(),
                items = s.items.filterNot { it.channel.id in gone },
                message = failed?.let { "Unfollowed $done of ${ids.size}. $it" } ?: "Unfollowed $done channel${if (done == 1) "" else "s"}",
            )
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageFollowsScreen(onBack: () -> Unit, onOpenChannel: (String) -> Unit, viewModel: ManageFollowsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(FollowsSort.LAST_LIVE) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val now = remember { Instant.now() }
    val shown = remember(s.items, sort, query) {
        s.items.filter { query.isBlank() || it.channel.displayName.contains(query, true) || it.channel.login.contains(query, true) }.let { list ->
            when (sort) {
                // Longest since last live first: the ones you'd want to tidy up.
                FollowsSort.LAST_LIVE -> list.sortedBy { it.lastLive ?: Instant.EPOCH }
                FollowsSort.FOLLOWED -> list.sortedBy { it.channel.followedAt?.toEpochMilliseconds() ?: 0L }
                FollowsSort.NAME -> list.sortedBy { it.channel.displayName.lowercase() }
            }
        }
    }
    val inactive = remember(s.items) { s.items.filter { (it.lastLive ?: Instant.EPOCH).isBefore(now.minus(180, ChronoUnit.DAYS)) }.map { it.channel.id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (s.selected.isEmpty()) "Manage follows" else "${s.selected.size} selected") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (s.selected.isNotEmpty()) TextButton(onClick = { viewModel.select(emptyList()) }) { Text("Clear") } },
            )
        },
        bottomBar = {
            if (s.selected.isNotEmpty() || s.progress != null) BottomAppBar {
                if (s.progress != null) {
                    CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(20.dp), strokeWidth = 2.dp)
                    Text("Unfollowing ${s.progress!!.first + 1} of ${s.progress!!.second}…")
                } else {
                    Text("${s.selected.size} selected", Modifier.weight(1f).padding(start = 16.dp))
                    FilledTonalButton(onClick = { confirm = true }, modifier = Modifier.padding(end = 16.dp)) { Text("Unfollow") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                query, { query = it }, placeholder = { Text("Search follows") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(FollowsSort.entries) { o -> FilterChip(sort == o, { sort = o }, label = { Text(o.label) }) }
                if (inactive.isNotEmpty()) item {
                    FilterChip(false, { viewModel.select(inactive) }, label = { Text("Select inactive 6+ months (${inactive.size})") })
                }
            }
            s.message?.let {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                }
            }
            when {
                s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                s.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("You don't follow anyone yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn {
                    items(shown, key = { it.channel.id }) { f ->
                        val checked = f.channel.id in s.selected
                        ListItem(
                            headlineContent = { Text(f.channel.displayName) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        "Last live " + (f.lastLive?.let { ago(it, now) } ?: "never"),
                                        f.channel.followedAt?.let { "followed ${ago(Instant.ofEpochMilli(it.toEpochMilliseconds()), now)}" },
                                    ).joinToString(" · "),
                                )
                            },
                            leadingContent = { AsyncImage(f.channel.profileImageUrl, null, Modifier.size(40.dp).clip(CircleShape)) },
                            trailingContent = { Checkbox(checked, { viewModel.toggle(f.channel.id) }) },
                            modifier = Modifier.clickable(onClickLabel = "Open channel") { if (s.selected.isEmpty()) onOpenChannel(f.channel.login) else viewModel.toggle(f.channel.id) },
                        )
                    }
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Unfollow ${s.selected.size} channel${if (s.selected.size == 1) "" else "s"}?") },
        text = { Text("You can follow them again any time.") },
        confirmButton = { TextButton(onClick = { confirm = false; viewModel.unfollowSelected() }) { Text("Unfollow") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

internal fun ago(t: Instant, now: Instant): String {
    val days = ChronoUnit.DAYS.between(t, now)
    return when {
        days < 1 -> "today"
        days < 2 -> "yesterday"
        days < 30 -> "$days days ago"
        days < 365 -> "${days / 30} months ago"
        else -> "${days / 365}y ago"
    }
}
