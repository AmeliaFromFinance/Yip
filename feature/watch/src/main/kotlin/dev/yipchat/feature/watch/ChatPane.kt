package dev.yipchat.feature.watch

import android.app.PendingIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yipchat.core.data.chat.ChatCommand
import dev.yipchat.core.data.chat.ChatConnection
import dev.yipchat.core.data.chat.RaidTarget
import dev.yipchat.core.designsystem.chat.ChatActions
import dev.yipchat.core.designsystem.chat.ChatRenderOptions
import dev.yipchat.core.designsystem.chat.ChatRow
import dev.yipchat.core.designsystem.chat.EmoteImage
import dev.yipchat.core.model.ChatItem
import dev.yipchat.core.model.Emote
import dev.yipchat.core.model.RoomState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch

/**
 * Newest message at the bottom. Follows new messages until the user drags the list; then it
 * pauses (like Twitch) until they tap the pill or scroll back down to the newest message.
 */
@Composable
fun ChatList(
    items: ImmutableList<ChatItem>,
    options: ChatRenderOptions,
    actions: ChatActions,
    modifier: Modifier = Modifier,
    translations: Map<String, Translation> = emptyMap(),
    onTranslationFix: (PendingIntent) -> Unit = {},
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var following by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }
    // Newest message seen when chat paused; counts what arrived since.
    var pausedAt by remember { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(items)

    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { i ->
            when (i) {
                is DragInteraction.Start -> {
                    dragging = true
                    if (following) {
                        following = false
                        pausedAt = latest.lastOrNull()?.id
                    }
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> dragging = false
            }
        }
    }
    // Back at the newest message by hand: resume.
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 && !state.isScrollInProgress }
            .collect { atNewest -> if (atNewest && !dragging && !following) following = true }
    }
    val newest = items.lastOrNull()?.id
    LaunchedEffect(newest, following) {
        if (following && items.isNotEmpty() && !dragging) state.scrollToItem(0)
    }
    val unseen = remember(items, pausedAt, following) {
        if (following) 0 else {
            val idx = items.indexOfLast { it.id == pausedAt }
            if (idx < 0) items.size else items.size - 1 - idx
        }
    }

    Box(modifier) {
        LazyColumn(
            state = state,
            reverseLayout = true,
            contentPadding = PaddingValues(vertical = 6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val size = items.size
            items(count = size, key = { items[size - 1 - it].id }, contentType = { items[size - 1 - it]::class }) { i ->
                val idx = size - 1 - i
                val item = items[idx]
                if (translations.isEmpty() || item.id !in translations) {
                    ChatRow(item, options, actions, alternate = idx % 2 == 1)
                } else Column {
                    ChatRow(item, options, actions, alternate = idx % 2 == 1)
                    TranslationLine(translations.getValue(item.id), onTranslationFix)
                }
            }
        }
        AnimatedVisibility(!following, Modifier.align(Alignment.BottomCenter).padding(10.dp), enter = fadeIn(), exit = fadeOut()) {
            SmallFloatingActionButton(onClick = {
                following = true
                scope.launch { state.scrollToItem(0) }
            }) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(16.dp))
                    Text(
                        when {
                            unseen >= 999 -> " 999+ new messages"
                            unseen > 0 -> " $unseen new message" + if (unseen == 1) "" else "s"
                            else -> " Chat paused due to scroll"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
fun RoomStateBar(room: RoomState, connection: ChatConnection, points: Int?, modifier: Modifier = Modifier) {
    val parts = buildList {
        if (!connection.connected) add("Connecting…")
        if (room.slowSeconds > 0) add("Slow ${room.slowSeconds}s")
        room.followersOnlyMinutes?.let { add(if (it == 0) "Followers only" else "Followers ${it}m") }
        if (room.subscriberOnly) add("Subs only")
        if (room.emoteOnly) add("Emote only")
        if (room.uniqueChat) add("Unique chat")
        points?.let { add("◆ ${"%,d".format(it)}") }
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("  ·  "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Composer(
    enabled: Boolean,
    signedIn: Boolean,
    replyTo: ChatItem.Message?,
    sendOnEnter: Boolean,
    keepOnSend: Boolean,
    tabCompletion: Boolean,
    recent: List<Emote>,
    showRecent: Boolean,
    completions: (String) -> List<String>,
    emoteFor: (String) -> Emote?,
    onSend: (String) -> Unit,
    onCancelReply: () -> Unit,
    onOpenEmotes: () -> Unit,
    onSignIn: () -> Unit,
    insert: String?,
    onInserted: () -> Unit,
    modifier: Modifier = Modifier,
    commands: List<ChatCommand> = emptyList(),
    quickReplies: List<String> = emptyList(),
) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var tabState by remember { mutableStateOf<Pair<String, Int>?>(null) }

    LaunchedEffect(insert) {
        insert ?: return@LaunchedEffect
        val t = value.text
        val sep = if (t.isEmpty() || t.endsWith(' ')) "" else " "
        val next = "$t$sep$insert "
        value = TextFieldValue(next, TextRange(next.length))
        onInserted()
    }

    val currentWord = value.text.substring(0, value.selection.start.coerceIn(0, value.text.length)).substringAfterLast(' ')
    // Slash commands: pick one while typing the name, then see its arguments.
    val typingCommand = commands.isNotEmpty() && value.text.startsWith("/") && ' ' !in value.text
    val commandMatches = remember(value.text, commands) { if (typingCommand) commands.filter { it.name.startsWith(value.text, true) }.take(6) else emptyList() }
    val activeCommand = remember(value.text, commands) {
        if (commands.isEmpty() || !value.text.startsWith("/") || ' ' !in value.text) null
        else commands.firstOrNull { it.name.equals(value.text.substringBefore(' '), true) }
    }
    val onUserArg = activeCommand?.userArg == true && value.text.substringBefore(' ').length + 1 + currentWord.length == value.selection.start &&
        value.text.trimEnd().count { it == ' ' } <= 1
    val suggestions = remember(currentWord, onUserArg) {
        when {
            typingCommand -> emptyList()
            onUserArg -> completions("@$currentWord").map { it.removePrefix("@") }
            else -> completions(currentWord)
        }
    }

    fun applyCompletion(word: String) {
        val cursor = value.selection.start
        val before = value.text.substring(0, cursor).dropLast(currentWord.length)
        val after = value.text.substring(cursor)
        val text = "$before$word ${after.trimStart()}"
        value = TextFieldValue(text, TextRange(before.length + word.length + 1))
    }

    fun send() {
        val t = value.text.trim()
        if (t.isEmpty()) return
        onSend(t)
        value = if (keepOnSend) value else TextFieldValue("")
    }

    Column(modifier.fillMaxWidth().imePadding()) {
        if (!signedIn) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sign in to chat", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                AssistChip(onClick = onSignIn, label = { Text("Sign in") })
            }
            return@Column
        }
        replyTo?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Replying to ${it.user.displayName}: ${it.raw}", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onCancelReply) { Icon(Icons.Outlined.Close, "Cancel reply") }
            }
        }
        if (commandMatches.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                commandMatches.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClickLabel = "Use ${c.name}") {
                            val text = c.name + " "
                            value = TextFieldValue(text, TextRange(text.length))
                        }.padding(vertical = 6.dp, horizontal = 4.dp),
                    ) {
                        Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(" ${c.args}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Text("  ${c.description}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        activeCommand?.takeIf { it.args.isNotEmpty() }?.let { c ->
            Text("${c.name} ${c.args} · ${c.description}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (quickReplies.isNotEmpty() && value.text.isEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(quickReplies) { q ->
                    SuggestionChip(
                        onClick = { value = TextFieldValue(q, TextRange(q.length)) },
                        label = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        if (suggestions.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(suggestions) { s ->
                    AssistChip(
                        onClick = { applyCompletion(s) },
                        label = { Text(s) },
                        leadingIcon = emoteFor(s)?.let { e -> { EmoteImage(e, modifier = Modifier.size(20.dp)) } },
                    )
                }
            }
        } else if (showRecent && recent.isNotEmpty() && value.text.isEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(recent, key = { it.provider.name + it.id }) { e ->
                    EmoteImage(e, modifier = Modifier.size(32.dp).clickable { applyCompletion(e.code) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenEmotes) { Icon(Icons.Outlined.EmojiEmotions, "Emotes") }
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; tabState = null },
                enabled = enabled,
                placeholder = { Text("Send a message") },
                singleLine = sendOnEnter,
                maxLines = 4,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.Default),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f).height(52.dp).onPreviewKeyEvent { ev ->
                    if (tabCompletion && ev.key == Key.Tab && ev.type == KeyEventType.KeyDown) {
                        val (prefix, index) = tabState ?: (currentWord to -1)
                        val options = completions(prefix)
                        if (options.isNotEmpty()) {
                            val next = (index + 1) % options.size
                            applyCompletion(options[next])
                            tabState = prefix to next
                        }
                        true
                    } else {
                        false
                    }
                },
            )
            FilledIconButton(onClick = ::send, enabled = enabled && value.text.isNotBlank(), modifier = Modifier.padding(start = 6.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

@Composable
fun RaidBanner(raid: RaidTarget, onGo: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Raiding ${raid.displayName}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = onGo) { Text("Go") }
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Dismiss") }
        }
    }
}

@Composable
private fun TranslationLine(t: Translation, onFix: (PendingIntent) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp)) {
        when (t) {
            Translation.Working -> Text("Translating…", style = MaterialTheme.typography.bodySmall, color = muted)
            is Translation.Done -> {
                Text(t.text, style = MaterialTheme.typography.bodyMedium)
                Text("Translated from ${t.fromLanguage} on this phone", style = MaterialTheme.typography.labelSmall, color = muted)
            }
            is Translation.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.reason, style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f))
                t.fix?.let { intent -> TextButton(onClick = { onFix(intent) }) { Text("Download") } }
            }
        }
    }
}
