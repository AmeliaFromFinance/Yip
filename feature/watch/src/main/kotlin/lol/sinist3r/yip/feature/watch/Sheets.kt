package lol.sinist3r.yip.feature.watch

import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.produceState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import lol.sinist3r.yip.core.data.cosmetics.ChannelCosmetics
import lol.sinist3r.yip.core.designsystem.chat.ChatActions
import lol.sinist3r.yip.core.designsystem.chat.ChatRenderOptions
import lol.sinist3r.yip.core.designsystem.chat.ChatRow
import lol.sinist3r.yip.core.designsystem.chat.EmoteImage
import lol.sinist3r.yip.core.designsystem.component.Avatar
import lol.sinist3r.yip.core.designsystem.component.LoadingState
import lol.sinist3r.yip.core.model.ChatItem
import lol.sinist3r.yip.core.model.Emote
import lol.sinist3r.yip.core.model.EmoteProvider
import lol.sinist3r.yip.core.model.StreamQuality

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmotePickerSheet(
    cosmetics: ChannelCosmetics?,
    twitchEmotes: List<Emote>,
    recent: List<Emote>,
    animate: Boolean,
    onPick: (Emote) -> Unit,
    onDismiss: () -> Unit,
) {
    val all = cosmetics?.emotes?.values.orEmpty()
    val tabs = remember(cosmetics, twitchEmotes, recent) {
        buildList {
            if (recent.isNotEmpty()) add("Recent" to recent)
            add("Channel" to (all.filter { it.isChannel } + twitchEmotes.filter { it.isChannel }).distinctBy { it.code })
            add("7TV" to all.filter { it.provider == EmoteProvider.SEVENTV })
            add("BTTV" to all.filter { it.provider == EmoteProvider.BTTV })
            add("FFZ" to all.filter { it.provider == EmoteProvider.FFZ })
            add("Twitch" to twitchEmotes)
        }.filter { it.second.isNotEmpty() }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<Emote?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 520.dp)) {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("Search emotes") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (query.isBlank() && tabs.isNotEmpty()) {
                PrimaryScrollableTabRow(selectedTabIndex = tab.coerceIn(0, tabs.lastIndex), edgePadding = 8.dp) {
                    tabs.forEachIndexed { i, (name, list) -> Tab(tab == i, { tab = i }, text = { Text("$name ${list.size}") }) }
                }
            }
            val list = if (query.isNotBlank()) {
                (all + twitchEmotes).filter { it.code.contains(query, true) }.distinctBy { it.code }.sortedBy { it.code.length }
            } else {
                tabs.getOrNull(tab)?.second.orEmpty().sortedBy { it.code.lowercase() }
            }
            detail?.let { e ->
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    EmoteImage(e, animate = animate, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.code, style = MaterialTheme.typography.titleMedium)
                        Text(listOfNotNull(e.provider.label(), if (e.animated) "animated" else null, if (e.zeroWidth) "zero-width" else null, e.ownerName?.let { "by $it" }, if (e.isPersonal) "personal" else null).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(onClick = { onPick(e) }) { Text("Insert") }
                }
            }
            LazyVerticalGrid(GridCells.Adaptive(48.dp), contentPadding = PaddingValues(12.dp), modifier = Modifier.fillMaxWidth()) {
                items(list, key = { it.provider.name + it.id + it.code }) { e ->
                    EmoteImage(
                        e, animate = animate,
                        modifier = Modifier.size(44.dp).padding(4.dp).clickable { onPick(e) },
                    )
                }
            }
        }
    }
}

private fun EmoteProvider.label() = when (this) {
    EmoteProvider.TWITCH -> "Twitch"
    EmoteProvider.SEVENTV -> "7TV"
    EmoteProvider.BTTV -> "BTTV"
    EmoteProvider.FFZ -> "FFZ"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySheet(
    qualities: List<StreamQuality>,
    selected: StreamQuality?,
    auto: Boolean,
    showAuto: Boolean,
    speeds: List<Float>,
    speed: Float,
    showSpeed: Boolean,
    sleepAtMillis: Long?,
    onQuality: (StreamQuality?) -> Unit,
    onSpeed: (Float) -> Unit,
    onSleep: (Int?) -> Unit,
    onDismiss: () -> Unit,
    lowLatency: Boolean? = null,
    onLowLatency: (Boolean) -> Unit = {},
) {
    // Fully expanded straight away: a sheet still settling eats the first tap.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetTitle("Quality")
            Column(Modifier.selectableGroup()) {
                if (showAuto) OptionRow("Auto", null, selected = auto) { onQuality(null) }
                qualities.forEach { q ->
                    OptionRow(
                        title = q.label + if (q.isSource) " (source)" else "",
                        subtitle = q.bandwidth?.let { "%.1f Mbps".format(it / 1_000_000.0) },
                        selected = !auto && selected?.id == q.id,
                    ) { onQuality(q) }
                }
            }
            if (lowLatency != null) {
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(value = lowLatency, role = Role.Switch, onValueChange = onLowLatency)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Low latency", style = MaterialTheme.typography.bodyLarge)
                        Text("Closer to live, may buffer more. Restarts the stream.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(checked = lowLatency, onCheckedChange = null)
                }
            }
            if (showSpeed) {
                SheetTitle("Speed")
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    speeds.forEach { s -> FilterChip(selected = s == speed, onClick = { onSpeed(s) }, label = { Text("${s}x") }) }
                }
            }
            SheetTitle("Sleep timer")
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 90).forEach { m -> OutlinedButton(onClick = { onSleep(m) }) { Text("$m min") } }
            }
            if (sleepAtMillis != null) {
                // Ticks while the sheet is open.
                val left by produceState(minutesLeft(sleepAtMillis), sleepAtMillis) {
                    while (true) { value = minutesLeft(sleepAtMillis); kotlinx.coroutines.delay(15_000) }
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Timer, null)
                        Text(
                            if (left < 1) "Stopping in under a minute" else "Stops in $left min",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                        TextButton(onClick = { onSleep(null) }) { Text("Turn off") }
                    }
                }
            }
        }
    }
}

private fun minutesLeft(at: Long): Long = ((at - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)

@Composable
private fun SheetTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))

/** Whole row is the touch target, at least 56dp tall. */
@Composable
private fun OptionRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (selected) Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserCardSheet(
    card: UserCard,
    options: ChatRenderOptions,
    isModerator: Boolean,
    onMention: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onBlock: (String) -> Unit,
    onMod: (ModAction, String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (card.loading) {
            LoadingState(Modifier.height(200.dp))
            return@ModalBottomSheet
        }
        val user = card.user
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(user?.profileImageUrl, null, 56.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(user?.displayName ?: card.login, style = MaterialTheme.typography.titleLarge)
                    user?.createdAt?.let { Text("Account created ${it.toString().take(10)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onMention(card.login) }) { Text("Mention") }
                OutlinedButton(onClick = { onOpenChannel(card.login) }) { Icon(Icons.Outlined.Person, null); Text(" Channel") }
                user?.let { u -> OutlinedButton(onClick = { onBlock(u.id) }) { Icon(Icons.Outlined.Block, null); Text(" Block") } }
            }
            if (isModerator && user != null) {
                Text("Moderation", style = MaterialTheme.typography.labelLarge)
                // Timeouts and bans on separate lines; each wraps on narrow screens.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Timer, "Timeout", Modifier.padding(end = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { onMod(ModAction.TIMEOUT_1M, user.id) }) { Text("1m") }
                    OutlinedButton(onClick = { onMod(ModAction.TIMEOUT_10M, user.id) }) { Text("10m") }
                    OutlinedButton(onClick = { onMod(ModAction.TIMEOUT_1H, user.id) }) { Text("1h") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { onMod(ModAction.BAN, user.id) },
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Icon(Icons.Outlined.Gavel, null, Modifier.size(18.dp)); Text(" Ban") }
                    OutlinedButton(onClick = { onMod(ModAction.UNBAN, user.id) }) { Text("Unban") }
                }
            }
            Text("Messages this session · ${card.messages.size}", style = MaterialTheme.typography.labelLarge)
            val state = rememberLazyListState()
            LazyColumn(state = state, modifier = Modifier.heightIn(max = 300.dp), reverseLayout = true) {
                items(card.messages.reversed(), key = { it.id }) { ChatRow(it, options, object : ChatActions {}, alternate = false) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageMenuSheet(
    message: ChatItem.Message,
    isModerator: Boolean,
    signedIn: Boolean,
    onReply: () -> Unit,
    onMod: (ModAction) -> Unit,
    onUser: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(message.raw, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp), maxLines = 4)
            if (signedIn) ListItem(headlineContent = { Text("Reply") }, leadingContent = { Icon(Icons.Outlined.Reply, null) }, modifier = Modifier.clickable { onReply(); onDismiss() })
            ListItem(headlineContent = { Text("Copy message") }, leadingContent = { Icon(Icons.Outlined.ContentCopy, null) }, modifier = Modifier.clickable { clipboard.setText(AnnotatedString(message.raw)); onDismiss() })
            ListItem(headlineContent = { Text("View ${message.user.displayName}") }, leadingContent = { Icon(Icons.Outlined.Person, null) }, modifier = Modifier.clickable { onUser(); onDismiss() })
            if (isModerator) {
                ListItem(headlineContent = { Text("Delete message") }, leadingContent = { Icon(Icons.Outlined.Delete, null) }, modifier = Modifier.clickable { onMod(ModAction.DELETE); onDismiss() })
                ListItem(headlineContent = { Text("Timeout 10 minutes") }, leadingContent = { Icon(Icons.Outlined.Timer, null) }, modifier = Modifier.clickable { onMod(ModAction.TIMEOUT_10M); onDismiss() })
                ListItem(headlineContent = { Text("Ban") }, leadingContent = { Icon(Icons.Outlined.Gavel, null) }, modifier = Modifier.clickable { onMod(ModAction.BAN); onDismiss() })
            }
        }
    }
}

internal val emptyChat = persistentListOf<ChatItem>()
