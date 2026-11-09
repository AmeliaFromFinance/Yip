package lol.sinist3r.yip.feature.watch

import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.material3.PrimaryTabRow
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
import lol.sinist3r.yip.core.model.TwitchEmoteGroups
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
    favourites: List<String> = emptyList(),
    onLongPress: (Emote) -> Unit = {},
) {
    val all = cosmetics?.emotes?.values.orEmpty()
    val tabs = remember(cosmetics, twitchEmotes, recent, favourites) { emoteTabs(all, twitchEmotes, recent, favourites) }
    // Open on the first tab with something in it.
    var tab by rememberSaveable { mutableIntStateOf(tabs.indexOfFirst { t -> t.sections.any { it.emotes.isNotEmpty() } }.coerceAtLeast(0)) }
    var query by remember { mutableStateOf("") }
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
                PrimaryTabRow(selectedTabIndex = tab.coerceIn(0, tabs.lastIndex)) {
                    tabs.forEachIndexed { i, t ->
                        // Default tab padding clips "Favourites" on narrow phones.
                        Tab(tab == i, { tab = i }, selectedContentColor = MaterialTheme.colorScheme.primary, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
                            Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 2.dp, vertical = 14.dp))
                        }
                    }
                }
            }
            val sections = if (query.isNotBlank()) {
                listOf(EmoteSection(null, (all + twitchEmotes).filter { it.code.contains(query, true) }.distinctBy { it.code }.sortedBy { it.code.length }))
            } else {
                tabs.getOrNull(tab.coerceIn(0, tabs.lastIndex.coerceAtLeast(0)))?.sections.orEmpty()
            }
            LazyVerticalGrid(GridCells.Adaptive(48.dp), contentPadding = PaddingValues(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (sections.all { it.emotes.isEmpty() }) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Nothing here yet", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                sections.forEach { sec ->
                    if (sec.title != null && sec.emotes.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h:" + sec.title) {
                            Text(
                                sec.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                            )
                        }
                    }
                    items(sec.emotes, key = { sec.title + it.provider.name + it.id + it.code }) { e ->
                        EmoteImage(
                            e, animate = animate,
                            modifier = Modifier.size(44.dp).padding(4.dp).combinedClickable(onClick = { onPick(e) }, onLongClick = { onLongPress(e) }),
                        )
                    }
                }
            }
        }
    }
}

data class EmoteSection(val title: String?, val emotes: List<Emote>)
data class EmoteTab(val name: String, val sections: List<EmoteSection>)

/**
 * Favourites, Recent, Twitch, 3rd Party.
 * Twitch: this channel, then one section per channel you have emotes from, then events (Hype Train, Prime...), then Twitch's own.
 * 3rd Party: 7TV, BTTV, FFZ, channel emotes first.
 */
fun emoteTabs(all: Collection<Emote>, twitchEmotes: List<Emote>, recent: List<Emote>, favourites: List<String>): List<EmoteTab> {
    fun List<Emote>.alpha() = distinctBy { it.code }.sortedBy { it.code.lowercase() }
    val byKey = (all + twitchEmotes).associateBy { it.favouriteKey }
    val twitch = twitchEmotes.filter { it.provider == EmoteProvider.TWITCH }
    val (mine, others) = twitch.partition { it.isChannel }
    // The channel's own Twitch emotes, including tiers you don't have yet; yours win on duplicates.
    val channelTwitch = mine + all.filter { it.provider == EmoteProvider.TWITCH && it.isChannel }
    val usable = channelTwitch.map { it.code }.toSet()
    val groups = others.filter { it.code !in usable }.groupBy { it.group ?: TwitchEmoteGroups.TWITCH }
    val order = compareBy<String>({ when (it) { TwitchEmoteGroups.TWITCH -> 2; in TwitchEmoteGroups.events -> 1; else -> 0 } }, { it.lowercase() })
    val twitchSections = listOf(EmoteSection("This channel", channelTwitch.alpha())) +
        groups.keys.sortedWith(order).map { EmoteSection(it, groups.getValue(it).alpha()) }
    fun provider(p: EmoteProvider, title: String): EmoteSection {
        val (ch, global) = all.filter { it.provider == p }.partition { it.isChannel }
        return EmoteSection(title, ch.alpha() + global.alpha())
    }
    return listOf(
        EmoteTab("Favourites", listOf(EmoteSection(null, favourites.mapNotNull(byKey::get)))),
        EmoteTab("Recent", listOf(EmoteSection(null, recent))),
        EmoteTab("Twitch", twitchSections.filter { it.emotes.isNotEmpty() }),
        EmoteTab("3rd Party", listOf(provider(EmoteProvider.SEVENTV, "7TV"), provider(EmoteProvider.BTTV, "BTTV"), provider(EmoteProvider.FFZ, "FFZ")).filter { it.emotes.isNotEmpty() }),
    )
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
    audioOnly: Boolean? = null,
    onAudioOnly: () -> Unit = {},
    /** "End of stream" / "End of video"; null hides the option. */
    sleepAtEndLabel: String? = null,
    sleepAtEnd: Boolean = false,
) {
    // Fully expanded straight away: a sheet still settling eats the first tap.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetTitle("Quality")
            Column(Modifier.selectableGroup()) {
                if (showAuto) OptionRow("Auto", null, selected = auto) { onQuality(null) }
                qualities.filterNot { audioOnly != null && it.isAudioOnly }.forEach { q ->
                    OptionRow(
                        title = q.label + if (q.isSource) " (source)" else "",
                        subtitle = q.bandwidth?.let { "%.1f Mbps".format(it / 1_000_000.0) },
                        selected = !auto && selected?.id == q.id,
                    ) { onQuality(q) }
                }
            }
            if (audioOnly != null) {
                SwitchRow("Audio only", "Sound without video. Uses far less data.", audioOnly) { onAudioOnly() }
            }
            if (lowLatency != null) {
                SwitchRow("Low latency", "Closer to live, may buffer more. Restarts the stream.", lowLatency, onLowLatency)
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
                sleepAtEndLabel?.let { OutlinedButton(onClick = { onSleep(lol.sinist3r.yip.core.playback.SLEEP_AT_END) }) { Text(it) } }
            }
            if (sleepAtEnd) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Timer, null)
                        Text("Stops when it ends", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        TextButton(onClick = { onSleep(null) }) { Text("Turn off") }
                    }
                }
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

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = null)
    }
}

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
    onHideUser: (() -> Unit)? = null,
    onMarkBot: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(message.raw, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp), maxLines = 4)
            if (signedIn) ListItem(headlineContent = { Text("Reply") }, leadingContent = { Icon(Icons.Outlined.Reply, null) }, modifier = Modifier.clickable { onReply(); onDismiss() })
            ListItem(headlineContent = { Text("Copy message") }, leadingContent = { Icon(Icons.Outlined.ContentCopy, null) }, modifier = Modifier.clickable { clipboard.setText(AnnotatedString(message.raw)); onDismiss() })
            ListItem(headlineContent = { Text("View ${message.user.displayName}") }, leadingContent = { Icon(Icons.Outlined.Person, null) }, modifier = Modifier.clickable { onUser(); onDismiss() })
            onHideUser?.let { hide -> ListItem(headlineContent = { Text("Hide messages from ${message.user.displayName}") }, leadingContent = { Icon(Icons.Outlined.VisibilityOff, null) }, modifier = Modifier.clickable { hide(); onDismiss() }) }
            onMarkBot?.let { mark -> ListItem(headlineContent = { Text("Mark ${message.user.displayName} as a bot") }, leadingContent = { Icon(Icons.Outlined.SmartToy, null) }, modifier = Modifier.clickable { mark(); onDismiss() }) }
            if (isModerator) {
                ListItem(headlineContent = { Text("Delete message") }, leadingContent = { Icon(Icons.Outlined.Delete, null) }, modifier = Modifier.clickable { onMod(ModAction.DELETE); onDismiss() })
                ListItem(headlineContent = { Text("Timeout 10 minutes") }, leadingContent = { Icon(Icons.Outlined.Timer, null) }, modifier = Modifier.clickable { onMod(ModAction.TIMEOUT_10M); onDismiss() })
                ListItem(headlineContent = { Text("Ban") }, leadingContent = { Icon(Icons.Outlined.Gavel, null) }, modifier = Modifier.clickable { onMod(ModAction.BAN); onDismiss() })
            }
        }
    }
}

internal val emptyChat = persistentListOf<ChatItem>()
