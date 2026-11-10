package dev.yipchat.feature.watch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.yipchat.core.designsystem.chat.ChatActions
import dev.yipchat.core.designsystem.chat.ChatRenderOptions
import dev.yipchat.core.designsystem.chat.ChatRow
import dev.yipchat.core.model.ChatItem
import kotlinx.collections.immutable.ImmutableList

/** Words match anywhere in a message; "from:name" limits to one chatter. Newest first. */
fun searchChat(items: List<ChatItem>, query: String): List<ChatItem.Message> {
    val parts = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return emptyList()
    val from = parts.firstOrNull { it.startsWith("from:", true) }?.substringAfter(':')?.removePrefix("@")?.lowercase()?.takeIf { it.isNotBlank() }
    val words = parts.filterNot { it.startsWith("from:", true) }
    return items.asReversed().asSequence().filterIsInstance<ChatItem.Message>().filter { m ->
        (from == null || m.user.login.lowercase().startsWith(from) || m.user.displayName.lowercase().startsWith(from)) &&
            words.all { w -> m.raw.contains(w, ignoreCase = true) }
    }.take(200).toList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSearchSheet(items: ImmutableList<ChatItem>, options: ChatRenderOptions, actions: ChatActions, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(items, query) { searchChat(items, query) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.heightIn(max = 640.dp)) {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("Search chat, or from:name") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            val hint = when {
                query.isBlank() -> "Searches messages loaded in this chat."
                results.isEmpty() -> "No matches"
                else -> "${results.size}${if (results.size == 200) "+" else ""} matches, newest first"
            }
            Text(hint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(Modifier.fillMaxWidth()) {
                items(results, key = { it.id }) { m -> ChatRow(m, options, actions, alternate = false, modifier = Modifier.padding(horizontal = 4.dp)) }
            }
        }
    }
}

/** A reply thread, oldest first. The first message is missing when it scrolled out of chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadSheet(messages: List<ChatItem.Message>, options: ChatRenderOptions, actions: ChatActions, onReply: (() -> Unit)?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 600.dp).padding(bottom = 16.dp)) {
            Text("Thread · ${messages.size} message${if (messages.size == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            val first = messages.first()
            if (first.replyParentLogin != null && messages.none { it.id == first.threadId }) {
                Text(
                    "Started by @${first.replyParentLogin}: ${first.replyParentText.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(messages, key = { it.id }) { m -> ChatRow(m, options.copy(chat = options.chat.copy(showReplies = false)), actions, alternate = false, modifier = Modifier.padding(horizontal = 4.dp)) }
            }
            onReply?.let { TextButton(onClick = it, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Reply in thread") } }
        }
    }
}
