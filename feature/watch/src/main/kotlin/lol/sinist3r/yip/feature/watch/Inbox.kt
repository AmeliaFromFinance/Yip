package lol.sinist3r.yip.feature.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Instant
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lol.sinist3r.yip.core.data.account.AccountRepository
import lol.sinist3r.yip.core.data.chat.MentionInbox
import lol.sinist3r.yip.core.data.chat.WhisperRepository
import lol.sinist3r.yip.core.data.chat.WhisperThread
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.designsystem.chat.ChatActions
import lol.sinist3r.yip.core.designsystem.chat.ChatRenderOptions
import lol.sinist3r.yip.core.designsystem.chat.ChatRow

interface InboxNavigator {
    fun back()
    fun openLive(login: String)
    fun openWhisper(login: String)
    fun openLink(url: String)
}

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val inbox: MentionInbox,
    private val whispers: WhisperRepository,
    accounts: AccountRepository,
    settingsRepo: SettingsRepository,
) : ViewModel() {
    val settings = settingsRepo.settings
    val mentions = inbox.items
    val threads = whispers.threads.map { m -> m.values.sortedByDescending { it.last?.at ?: Instant.DISTANT_PAST } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val signedIn = accounts.active.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun markMentionsRead() = inbox.markRead()
    fun clearMentions() = inbox.clear()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(nav: InboxNavigator, viewModel: InboxViewModel = hiltViewModel()) {
    val mentions by viewModel.mentions.collectAsStateWithLifecycle()
    val threads by viewModel.threads.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var newWhisper by remember { mutableStateOf(false) }
    val animate = rememberAnimate(settings.chat.animateEmotes, settings.appearance.reduceMotion, settings.data.dataSaver)
    val options = remember(settings.chat, settings.thirdParty, animate) { ChatRenderOptions(settings.chat, settings.thirdParty, animate) }
    val actions = remember { object : ChatActions { override fun onLink(url: String) = nav.openLink(url) } }
    LaunchedEffect(tab, mentions.size) { if (tab == 0) viewModel.markMentionsRead() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Inbox") },
                    navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = { if (tab == 0 && mentions.isNotEmpty()) TextButton(onClick = viewModel::clearMentions) { Text("Clear") } },
                )
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Mentions") })
                    Tab(tab == 1, { tab = 1 }, text = {
                        val unread = threads.sumOf { it.unread }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Whispers")
                            if (unread > 0) Badge { Text("$unread") }
                        }
                    })
                }
            }
        },
        floatingActionButton = {
            if (tab == 1 && signedIn) FloatingActionButton(onClick = { newWhisper = true }) { Icon(Icons.Outlined.Edit, "New whisper") }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                tab == 0 && mentions.isEmpty() -> Empty("No mentions yet. Anything that @'s you in a chat you open shows up here.")
                tab == 0 -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(mentions, key = { it.message.id }) { m ->
                        Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Open #${m.channelLogin}") { nav.openLive(m.channelLogin) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text("#${m.channelLogin} · ${m.message.timestamp.clock()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ChatRow(m.message, options, actions, alternate = false)
                        }
                        HorizontalDivider()
                    }
                }
                !signedIn -> Empty("Sign in to send and receive whispers.")
                threads.isEmpty() -> Empty("No whispers yet. They arrive while Yip is open or playing.")
                else -> LazyColumn {
                    items(threads, key = { it.login }) { t -> ThreadRow(t) { nav.openWhisper(t.login) } }
                }
            }
        }
    }
    if (newWhisper) {
        var to by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newWhisper = false },
            title = { Text("New whisper") },
            text = { OutlinedTextField(to, { to = it.trim().removePrefix("@") }, label = { Text("Username") }, singleLine = true) },
            confirmButton = { TextButton(enabled = to.isNotBlank(), onClick = { newWhisper = false; nav.openWhisper(to) }) { Text("Open") } },
            dismissButton = { TextButton(onClick = { newWhisper = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ThreadRow(t: WhisperThread, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(t.displayName, maxLines = 1) },
        supportingContent = t.last?.let { m -> { Text((if (m.fromMe) "You: " else "") + m.text, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = { if (t.unread > 0) Badge { Text("${t.unread}") } },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun Instant.clock(): String {
    val t = java.time.Instant.ofEpochMilli(toEpochMilliseconds()).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    return "%02d:%02d".format(t.hour, t.minute)
}

@HiltViewModel
class WhisperViewModel @Inject constructor(savedState: SavedStateHandle, private val whispers: WhisperRepository) : ViewModel() {
    val login: String = savedState.get<String>("login").orEmpty()
    val thread = whispers.threads.map { it[login.lowercase()] }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setOpen(open: Boolean) = whispers.setOpen(if (open) login else null)
    suspend fun send(text: String) = whispers.send(login, text)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisperScreen(nav: InboxNavigator, viewModel: WhisperViewModel = hiltViewModel()) {
    val thread by viewModel.thread.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val messages = thread?.messages.orEmpty()
    DisposableEffect(Unit) {
        viewModel.setOpen(true)
        onDispose { viewModel.setOpen(false) }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex) }
    fun send() {
        val msg = text.trim().takeIf { it.isNotEmpty() } ?: return
        scope.launch {
            viewModel.send(msg)
                .onSuccess { text = "" }
                .onFailure { snackbar.showSnackbar(it.message ?: "Couldn't send. Twitch needs a verified phone number to whisper.") }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(thread?.displayName ?: viewModel.login) },
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (messages.isEmpty()) item {
                    Text("Whispers here last until Yip closes.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
                items(messages, key = { it.id }) { m ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromMe) Alignment.CenterEnd else Alignment.CenterStart) {
                        Surface(
                            color = if (m.fromMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.widthIn(max = 300.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(m.text, style = MaterialTheme.typography.bodyLarge)
                                Text(m.at.clock(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    text, { text = it.take(500) },
                    placeholder = { Text("Whisper") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    maxLines = 4,
                )
                IconButton(onClick = ::send, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.Send, "Send") }
            }
        }
    }
}
