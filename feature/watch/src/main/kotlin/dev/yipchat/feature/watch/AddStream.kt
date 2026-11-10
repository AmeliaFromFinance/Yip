package dev.yipchat.feature.watch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yipchat.core.common.attempt
import dev.yipchat.core.data.twitch.TwitchRepository
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StreamPick(val login: String, val name: String, val detail: String?, val avatar: String?)

data class AddStreamState(val query: String = "", val following: List<StreamPick> = emptyList(), val results: List<StreamPick> = emptyList(), val loading: Boolean = true)

@OptIn(FlowPreview::class)
@HiltViewModel
class AddStreamViewModel @Inject constructor(private val twitch: TwitchRepository) : ViewModel() {
    private val _state = MutableStateFlow(AddStreamState())
    val state = _state.asStateFlow()
    private val query = MutableStateFlow("")

    init {
        viewModelScope.launch {
            val live = attempt { twitch.followedLive() }.getOrDefault(emptyList())
            _state.update { s -> s.copy(loading = false, following = live.map { StreamPick(it.userLogin, it.userName, it.gameName, it.profileImageUrl) }) }
        }
        viewModelScope.launch {
            query.debounce(300).distinctUntilChanged().collect { q ->
                if (q.isBlank()) { _state.update { it.copy(results = emptyList()) }; return@collect }
                val page = attempt { twitch.searchChannels(q.trim(), null) }.getOrNull()
                val live = page?.items.orEmpty().filter { it.isLive }.map { StreamPick(it.login, it.displayName, it.gameName, it.profileImageUrl) }
                _state.update { it.copy(results = live) }
            }
        }
    }

    fun search(q: String) {
        _state.update { it.copy(query = q) }
        query.value = q
    }
}

/** Live follows plus channel search; [exclude] hides streams already open. */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStreamSheet(exclude: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit, viewModel: AddStreamViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val list = (if (s.query.isBlank()) s.following else s.results).filter { it.login.lowercase() !in exclude }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.heightIn(max = 560.dp)) {
            Text("Add a stream", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            OutlinedTextField(
                s.query, viewModel::search,
                placeholder = { Text("Search live channels") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (s.query.isBlank()) {
                Text("Live now", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            }
            if (list.isEmpty() && !s.loading) {
                Text(
                    if (s.query.isBlank()) "No one you follow is live. Search above." else "No live channels found",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn {
                items(list, key = { it.login }) { p ->
                    ListItem(
                        headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = p.detail?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                        leadingContent = { AsyncImage(p.avatar, null, Modifier.size(40.dp).clip(CircleShape)) },
                        modifier = Modifier.clickable { onPick(p.login) },
                    )
                }
            }
        }
    }
}
