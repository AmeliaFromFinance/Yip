package lol.sinist3r.yip.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.data.twitch.TwitchRepository
import lol.sinist3r.yip.core.designsystem.component.ClipCard
import lol.sinist3r.yip.core.designsystem.component.ErrorState
import lol.sinist3r.yip.core.designsystem.component.LoadingState
import lol.sinist3r.yip.core.designsystem.component.MessageState
import lol.sinist3r.yip.core.model.Clip

data class FollowsClipsState(val loading: Boolean = true, val clips: List<Clip> = emptyList(), val error: String? = null)

@HiltViewModel
class FollowsClipsViewModel @Inject constructor(private val twitch: TwitchRepository, settingsRepo: SettingsRepository) : ViewModel() {
    val settings = settingsRepo.settings
    private val _state = MutableStateFlow(FollowsClipsState())
    val state = _state.asStateFlow()

    init { load(false) }

    fun load(force: Boolean = true) = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        attempt { twitch.followsClips(force) }
            .onSuccess { c -> _state.value = FollowsClipsState(false, c) }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load clips") } }
    }
}

/** The most-watched clips of the past week from channels you follow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowsClipsScreen(onBack: () -> Unit, onOpenClip: (Clip) -> Unit, viewModel: FollowsClipsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Top clips this week") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                state.loading && state.clips.isEmpty() -> LoadingState()
                state.error != null && state.clips.isEmpty() -> ErrorState(state.error!!, { viewModel.load() })
                state.clips.isEmpty() -> MessageState("No clips this week", "Clips from channels you follow show up here.")
                else -> PullToRefreshBox(isRefreshing = state.loading, onRefresh = { viewModel.load() }) {
                    LazyVerticalGrid(
                        columns = gridCells(settings.appearance),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.clips, key = { it.id }) { c -> ClipCard(c, onClick = { onOpenClip(c) }, showChannel = true) }
                    }
                }
            }
        }
    }
}
