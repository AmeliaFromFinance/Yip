package lol.sinist3r.yip.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import kotlinx.coroutines.launch
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.model.AppSettings

/** Pins and hides. Pinned favourites share the list used for "Only favourites" notifications. */
@HiltViewModel
class QuickActionsViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    val state = settings.settings

    fun togglePin(login: String) = viewModelScope.launch {
        val l = login.lowercase()
        settings.update { s ->
            val fav = s.notifications.favourites
            s.copy(notifications = s.notifications.copy(favourites = if (l in fav) fav - l else fav + l))
        }
    }

    fun hideChannel(login: String) = viewModelScope.launch {
        val l = login.lowercase()
        settings.update { s -> s.copy(general = s.general.copy(hiddenChannels = (s.general.hiddenChannels + l).distinct())) }
    }

    fun hideCategory(name: String) = viewModelScope.launch {
        val n = name.trim().lowercase().takeIf { it.isNotEmpty() } ?: return@launch
        settings.update { s -> s.copy(general = s.general.copy(hiddenCategories = (s.general.hiddenCategories + n).distinct())) }
    }
}

fun AppSettings.isPinned(login: String) = login.lowercase() in notifications.favourites
fun AppSettings.isHiddenChannel(login: String) = login.lowercase() in general.hiddenChannels
fun AppSettings.isHiddenCategory(name: String?) = name != null && name.lowercase() in general.hiddenCategories

/** What a long-press acts on. [category] is null for channels with nothing live. */
data class QuickTarget(val login: String, val displayName: String, val category: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionsSheet(
    target: QuickTarget,
    onAddToMultiView: ((String) -> Unit)?,
    onDismiss: () -> Unit,
    allowHide: Boolean = true,
    viewModel: QuickActionsViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val pinned = s.isPinned(target.login)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(target.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            ListItem(
                headlineContent = { Text(if (pinned) "Unpin from Following" else "Pin to top of Following") },
                leadingContent = { Icon(if (pinned) Icons.Outlined.Star else Icons.Outlined.StarBorder, null) },
                modifier = Modifier.clickable { viewModel.togglePin(target.login); onDismiss() },
            )
            if (onAddToMultiView != null) ListItem(
                headlineContent = { Text("Add to multi-view") },
                leadingContent = { Icon(Icons.Outlined.ViewQuilt, null) },
                modifier = Modifier.clickable { onAddToMultiView(target.login); onDismiss() },
            )
            if (allowHide) {
                ListItem(
                    headlineContent = { Text("Hide ${target.displayName} from Browse") },
                    leadingContent = { Icon(Icons.Outlined.VisibilityOff, null) },
                    modifier = Modifier.clickable { viewModel.hideChannel(target.login); onDismiss() },
                )
                target.category?.takeIf { it.isNotBlank() }?.let { cat ->
                    ListItem(
                        headlineContent = { Text("Hide $cat from Browse") },
                        leadingContent = { Icon(Icons.Outlined.VisibilityOff, null) },
                        modifier = Modifier.clickable { viewModel.hideCategory(cat); onDismiss() },
                    )
                }
                Text(
                    "Undo under Settings → General → Hidden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}
