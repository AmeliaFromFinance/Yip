package lol.sinist3r.yip.feature.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import lol.sinist3r.yip.core.data.chat.KeywordMatcher
import lol.sinist3r.yip.core.model.FilterSettings

/** One-tap chat filters, opened from beside the chat. Same settings as Filters and highlights. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatFiltersSheet(filters: FilterSettings, onChange: ((FilterSettings) -> FilterSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Chat filters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            FilterSwitch("Hide bots", "${filters.botNames.size} known bots, e.g. Nightbot, StreamElements", filters.hideBots) { v -> onChange { it.copy(hideBots = v) } }
            FilterSwitch("Hide commands", "Messages starting with ${filters.commandPrefixes.joinToString(" ")}", filters.hideCommands) { v -> onChange { it.copy(hideCommands = v) } }
            FilterSwitch("Hide emote-only messages", null, filters.hideEmoteOnly) { v -> onChange { it.copy(hideEmoteOnly = v) } }
            FilterSwitch("Hide first-time chatters", null, filters.hideFirstTimeChatters) { v -> onChange { it.copy(hideFirstTimeChatters = v) } }

            Text("Blocked words", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
            Text(
                "Whole words, any case. Wrap in slashes for a pattern, e.g. /bit\\.ly/",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                filters.blockedTerms.forEach { t ->
                    InputChip(selected = false, onClick = { onChange { it.copy(blockedTerms = it.blockedTerms - t) } }, label = { Text(t) },
                        trailingIcon = { Icon(Icons.Outlined.Close, "Remove $t", Modifier.size(16.dp)) })
                }
            }
            var entry by remember { mutableStateOf("") }
            var error by remember { mutableStateOf<String?>(null) }
            fun add() {
                val e = entry.trim()
                error = when {
                    e.isEmpty() -> null
                    KeywordMatcher.problem(e) != null -> KeywordMatcher.problem(e)
                    filters.blockedTerms.any { it.equals(e, true) } -> "Already blocked"
                    else -> { onChange { it.copy(blockedTerms = it.blockedTerms + e) }; entry = ""; null }
                }
            }
            OutlinedTextField(
                value = entry, onValueChange = { entry = it; error = null },
                label = { Text("Word, phrase or /pattern/") }, isError = error != null,
                supportingText = error?.let { e -> { Text(e) } }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (filters.hiddenUsers.isNotEmpty()) {
                Text("Hidden users", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    filters.hiddenUsers.forEach { u ->
                        InputChip(selected = false, onClick = { onChange { it.copy(hiddenUsers = it.hiddenUsers - u) } }, label = { Text(u) },
                            trailingIcon = { Icon(Icons.Outlined.Close, "Show $u again", Modifier.size(16.dp)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
