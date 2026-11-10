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
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.PushPin
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.ui.draw.alpha
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
    showLocked: Boolean = true,
    channelName: String? = null,
    mostUsed: List<String> = emptyList(),
) {
    val all = cosmetics?.emotes?.values.orEmpty()
    val tabs = remember(cosmetics, twitchEmotes, recent, favourites, showLocked, mostUsed) { emoteTabs(all, twitchEmotes, recent, favourites, showLocked, mostUsed) }
    val lockedCodes = remember(tabs) { tabs.flatMap { it.sections }.filter { it.locked }.flatMap { it.emotes }.associateBy { it.code } }
    val context = androidx.compose.ui.platform.LocalContext.current
    val onTap: (Emote) -> Unit = { e ->
        val lock = lockedCodes[e.code]
        if (lock != null && e.provider == EmoteProvider.TWITCH) {
            val how = when (val g = lock.group ?: "Tier 1") {
                "Follower" -> "Follow ${channelName ?: "this channel"} to use ${e.code}"
                "Bits" -> "${e.code} unlocks by cheering bits in ${channelName ?: "this channel"}"
                else -> "Subscribe to ${channelName ?: "this channel"} at $g to use ${e.code}"
            }
            android.widget.Toast.makeText(context, how, android.widget.Toast.LENGTH_SHORT).show()
        } else onPick(e)
    }
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
                        val locked = e.provider == EmoteProvider.TWITCH && e.code in lockedCodes
                        Box(
                            Modifier.size(44.dp).combinedClickable(
                                onClickLabel = if (locked) "Locked: how to unlock" else "Insert",
                                onClick = { onTap(e) }, onLongClick = { onLongPress(e) },
                            ),
                        ) {
                            EmoteImage(e, animate = animate && !locked, modifier = Modifier.fillMaxSize().padding(4.dp).alpha(if (locked) 0.35f else 1f))
                            if (locked) Icon(Icons.Outlined.Lock, "Locked", Modifier.size(14.dp).align(Alignment.BottomEnd), tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

data class EmoteSection(val title: String?, val emotes: List<Emote>, val locked: Boolean = false)
data class EmoteTab(val name: String, val sections: List<EmoteSection>)

/**
 * Favourites, Recent, Twitch, 3rd Party.
 * Twitch: this channel, then one section per channel you have emotes from, then events (Hype Train, Prime...), then Twitch's own.
 * 3rd Party: 7TV, BTTV, FFZ, channel emotes first.
 */
fun emoteTabs(all: Collection<Emote>, twitchEmotes: List<Emote>, recent: List<Emote>, favourites: List<String>, showLocked: Boolean = true, mostUsed: List<String> = emptyList()): List<EmoteTab> {
    fun List<Emote>.alpha() = distinctBy { it.code }.sortedBy { it.code.lowercase() }
    val byKey = (all + twitchEmotes).associateBy { it.favouriteKey }
    val twitch = twitchEmotes.filter { it.provider == EmoteProvider.TWITCH }
    val (mine, others) = twitch.partition { it.isChannel }
    val owned = twitch.map { it.code }.toSet()
    // The channel's full set. Until your own list loads (or when signed out) nothing is marked locked.
    val channelAll = all.filter { it.provider == EmoteProvider.TWITCH && it.isChannel }
    val (usableExtra, locked) = if (twitch.isEmpty()) channelAll to emptyList() else channelAll.partition { it.code in owned }
    val channelTwitch = mine + usableExtra
    val usable = channelTwitch.map { it.code }.toSet()
    val groups = others.filter { it.code !in usable }.groupBy { it.group ?: TwitchEmoteGroups.TWITCH }
    val order = compareBy<String>({ when (it) { TwitchEmoteGroups.TWITCH -> 2; in TwitchEmoteGroups.events -> 1; else -> 0 } }, { it.lowercase() })
    val lockOrder = listOf("Follower", "Tier 1", "Tier 2", "Tier 3", "Bits")
    val lockedSections = if (!showLocked) emptyList() else locked.groupBy { it.group ?: "Tier 1" }.entries
        .sortedBy { lockOrder.indexOf(it.key).let { i -> if (i < 0) 99 else i } }
        .map { (label, list) -> EmoteSection("$label · locked", list.alpha(), locked = true) }
    val twitchSections = listOf(EmoteSection("This channel", channelTwitch.alpha())) + lockedSections +
        groups.keys.sortedWith(order).map { EmoteSection(it, groups.getValue(it).alpha()) }
    fun provider(p: EmoteProvider, title: String): EmoteSection {
        val (ch, global) = all.filter { it.provider == p }.partition { it.isChannel }
        return EmoteSection(title, ch.alpha() + global.alpha())
    }
    return listOf(
        EmoteTab("Favourites", listOf(EmoteSection(null, favourites.mapNotNull(byKey::get)))),
        EmoteTab("Recent", listOf(EmoteSection(if (mostUsed.isEmpty()) null else "Recent", recent), EmoteSection("Most used", mostUsed.mapNotNull(byKey::get))).filter { it.emotes.isNotEmpty() || it.title == null }),
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
    /** Stream volume, 0..1; null hides the slider. */
    volume: Float? = null,
    onVolume: (Float) -> Unit = {},
) {
    // Fully expanded straight away: a sheet still settling eats the first tap.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (volume != null) {
                SheetTitle("Volume (this channel)")
                androidx.compose.material3.Slider(
                    value = volume, onValueChange = onVolume,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
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
    note: lol.sinist3r.yip.core.model.UserNote? = null,
    /** Preset reasons; the picked one goes with timeouts and bans. */
    reasons: List<String> = emptyList(),
    onModWithReason: ((ModAction, String, String?) -> Unit)? = null,
    onLoadLogs: (() -> Unit)? = null,
    onSaveNote: ((lol.sinist3r.yip.core.model.UserNote) -> Unit)? = null,
    onWhisper: ((String) -> Unit)? = null,
    macros: List<lol.sinist3r.yip.core.model.ModMacro> = emptyList(),
    onMacro: ((lol.sinist3r.yip.core.model.ModMacro, String) -> Unit)? = null,
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
                onWhisper?.let { w -> OutlinedButton(onClick = { w(card.login) }) { Icon(Icons.Outlined.MailOutline, null); Text(" Whisper") } }
                user?.let { u -> OutlinedButton(onClick = { onBlock(u.id) }) { Icon(Icons.Outlined.Block, null); Text(" Block") } }
            }
            onSaveNote?.let { save -> UserNoteEditor(note ?: lol.sinist3r.yip.core.model.UserNote(), save) }
            if (isModerator && user != null) {
                var reason by rememberSaveable(card.login) { mutableStateOf<String?>(null) }
                val mod: (ModAction, String) -> Unit = { a, id -> onModWithReason?.invoke(a, id, reason) ?: onMod(a, id) }
                Text("Moderation", style = MaterialTheme.typography.labelLarge)
                if (macros.isNotEmpty() && onMacro != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        macros.forEach { m -> FilledTonalButton(onClick = { onMacro(m, user.id) }) { Text(m.label) } }
                    }
                }
                if (reasons.isNotEmpty() && onModWithReason != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        reasons.forEach { r ->
                            androidx.compose.material3.FilterChip(selected = reason == r, onClick = { reason = if (reason == r) null else r }, label = { Text(r) })
                        }
                    }
                }
                // Timeouts and bans on separate lines; each wraps on narrow screens.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Timer, "Timeout", Modifier.padding(end = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { mod(ModAction.TIMEOUT_1M, user.id) }) { Text("1m") }
                    OutlinedButton(onClick = { mod(ModAction.TIMEOUT_10M, user.id) }) { Text("10m") }
                    OutlinedButton(onClick = { mod(ModAction.TIMEOUT_1H, user.id) }) { Text("1h") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { mod(ModAction.BAN, user.id) },
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
            // Older history from logs.ivr.fi.
            onLoadLogs?.let { load ->
                when (val logs = card.logs) {
                    LogsState.Idle -> TextButton(onClick = load) { Text("Load older messages") }
                    LogsState.Loading -> Text("Loading older messages…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is LogsState.Unavailable -> Text(logs.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is LogsState.Loaded -> {
                        val sessionIds = card.messages.map { it.id }.toSet()
                        val older = logs.messages.filter { it.id !in sessionIds }
                        Text("Older messages · ${older.size}", style = MaterialTheme.typography.labelLarge)
                        if (older.isEmpty()) Text("Nothing older in the logs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(older, key = { "log-" + it.id }) { m ->
                                Column {
                                    Text(m.timestamp.toString().take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    ChatRow(m, options, object : ChatActions {}, alternate = false)
                                }
                            }
                        }
                        Text("From logs.ivr.fi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
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
    onTranslate: (() -> Unit)? = null,
    onThread: (() -> Unit)? = null,
    macros: List<lol.sinist3r.yip.core.model.ModMacro> = emptyList(),
    onMacro: ((lol.sinist3r.yip.core.model.ModMacro) -> Unit)? = null,
    /** Pins for everyone; [Int] is seconds, null until removed. Null hides the option. */
    onPin: ((Int?) -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    var pinPicker by remember { mutableStateOf(false) }
    if (pinPicker && onPin != null) {
        PinDurationDialog(onPick = { onPin(it); pinPicker = false; onDismiss() }, onDismiss = { pinPicker = false })
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(message.raw, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp), maxLines = 4)
            if (signedIn) ListItem(headlineContent = { Text("Reply") }, leadingContent = { Icon(Icons.Outlined.Reply, null) }, modifier = Modifier.clickable { onReply(); onDismiss() })
            ListItem(headlineContent = { Text("Copy message") }, leadingContent = { Icon(Icons.Outlined.ContentCopy, null) }, modifier = Modifier.clickable { clipboard.setText(AnnotatedString(message.raw)); onDismiss() })
            ListItem(headlineContent = { Text("View ${message.user.displayName}") }, leadingContent = { Icon(Icons.Outlined.Person, null) }, modifier = Modifier.clickable { onUser(); onDismiss() })
            onHideUser?.let { hide -> ListItem(headlineContent = { Text("Hide messages from ${message.user.displayName}") }, leadingContent = { Icon(Icons.Outlined.VisibilityOff, null) }, modifier = Modifier.clickable { hide(); onDismiss() }) }
            onThread?.let { th -> ListItem(headlineContent = { Text("View thread") }, leadingContent = { Icon(Icons.Outlined.Forum, null) }, modifier = Modifier.clickable { th(); onDismiss() }) }
            onTranslate?.let { tr -> ListItem(headlineContent = { Text("Translate") }, leadingContent = { Icon(Icons.Outlined.Translate, null) }, modifier = Modifier.clickable { tr(); onDismiss() }) }
            onMarkBot?.let { mark -> ListItem(headlineContent = { Text("Mark ${message.user.displayName} as a bot") }, leadingContent = { Icon(Icons.Outlined.SmartToy, null) }, modifier = Modifier.clickable { mark(); onDismiss() }) }
            onPin?.let { ListItem(headlineContent = { Text("Pin message") }, supportingContent = { Text("Shows above chat for everyone") }, leadingContent = { Icon(Icons.Outlined.PushPin, null) }, modifier = Modifier.clickable { pinPicker = true }) }
            if (isModerator) {
                if (macros.isNotEmpty() && onMacro != null) {
                    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        macros.forEach { m -> FilledTonalButton(onClick = { onMacro(m); onDismiss() }) { Text(m.label) } }
                    }
                }
                ListItem(headlineContent = { Text("Delete message") }, leadingContent = { Icon(Icons.Outlined.Delete, null) }, modifier = Modifier.clickable { onMod(ModAction.DELETE); onDismiss() })
                ListItem(headlineContent = { Text("Timeout 10 minutes") }, leadingContent = { Icon(Icons.Outlined.Timer, null) }, modifier = Modifier.clickable { onMod(ModAction.TIMEOUT_10M); onDismiss() })
                ListItem(headlineContent = { Text("Ban") }, leadingContent = { Icon(Icons.Outlined.Gavel, null) }, modifier = Modifier.clickable { onMod(ModAction.BAN); onDismiss() })
            }
        }
    }
}

private val pinDurations = listOf(30 to "30 seconds", 60 to "1 minute", 120 to "2 minutes", 300 to "5 minutes", 600 to "10 minutes", 1800 to "30 minutes", 3600 to "1 hour")

@Composable
private fun PinDurationDialog(onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pin for") },
        text = {
            Column {
                pinDurations.forEach { (s, label) -> ListItem(headlineContent = { Text(label) }, modifier = Modifier.clickable { onPick(s) }) }
                ListItem(headlineContent = { Text("Until removed or the stream ends") }, modifier = Modifier.clickable { onPick(null) })
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal val emptyChat = persistentListOf<ChatItem>()

/** Nickname shown next to their name in chat, plus a free-text note. Only on this device. */
@Composable
private fun UserNoteEditor(initial: lol.sinist3r.yip.core.model.UserNote, onSave: (lol.sinist3r.yip.core.model.UserNote) -> Unit) {
    var nickname by rememberSaveable(initial) { mutableStateOf(initial.nickname) }
    var note by rememberSaveable(initial) { mutableStateOf(initial.note) }
    val changed = nickname.trim() != initial.nickname || note.trim() != initial.note
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your note (only you see this)", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(nickname, { nickname = it.take(40) }, label = { Text("Nickname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it.take(500) }, label = { Text("Note") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
        if (changed) FilledTonalButton(onClick = { onSave(lol.sinist3r.yip.core.model.UserNote(nickname.trim(), note.trim())) }) { Text("Save note") }
    }
}
