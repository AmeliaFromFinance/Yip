package dev.yipchat.feature.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yipchat.core.model.AppSettings
import kotlinx.coroutines.launch

interface SettingsNavigator {
    fun back()
    fun openPage(key: String)
    fun openLogin()
    fun openConnectWeb()
    fun openLogs()
    fun openDataUsage() {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoot(nav: SettingsNavigator, showBack: Boolean, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { if (showBack) IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("Search settings") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear") } },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (query.isBlank()) {
                items(SettingsSpec.pages, key = { it.key }) { p ->
                    ListItem(
                        headlineContent = { Text(p.title) },
                        supportingContent = { Text(p.summary) },
                        leadingContent = { Icon(p.icon, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        modifier = Modifier.clickable { nav.openPage(p.key) },
                    )
                }
            } else {
                val q = query.trim().lowercase()
                val hits = SettingsSpec.pages.flatMap { p ->
                    p.rows.withIndex().filter { (_, r) -> r !is Row.Header && (r.title.lowercase().contains(q) || r.summary?.lowercase()?.contains(q) == true) }
                        .map { (i, r) -> Triple(p, i, r) }
                }
                if (hits.isEmpty()) item { Text("No settings match \"$query\"", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(hits, key = { (p, i, _) -> "${p.key}-$i" }) { (p, _, r) ->
                    Column {
                        Text(p.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                        RowView(r, settings, viewModel::update, onAction = { nav.openPage(p.key) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(key: String, nav: SettingsNavigator, viewModel: SettingsViewModel = hiltViewModel()) {
    val page = SettingsSpec.page(key) ?: return
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(viewModel::export) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::import) }
    var confirm by remember { mutableStateOf<Row.Action?>(null) }
    var transfer by remember { mutableStateOf<Pair<String?, String>?>(null) }
    val scope = rememberCoroutineScope()
    transfer?.let { (link, json) -> TransferSheet(link, json, onDismiss = { transfer = null }) }

    val onAction: (Row.Action) -> Unit = { a ->
        when {
            a.destructive -> confirm = a
            a.id == "transfer" -> scope.launch { transfer = viewModel.transfer() }
            a.id == "export" -> exportLauncher.launch("yip-settings.json")
            a.id == "import" -> importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            a.id == "logs" -> nav.openLogs()
            a.id == "data_usage" -> nav.openDataUsage()
            a.id == "system_notifications" -> context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            else -> viewModel.action(a.id)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(page.title) }, navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            // Titles repeat across sections (e.g. "Emotes" under 7TV and BTTV), so key by position.
            itemsIndexed(page.rows, key = { i, _ -> "${page.key}-$i" }) { _, row -> RowView(row, settings, viewModel::update, onAction) }
        }
    }
    confirm?.let { a ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(a.title) },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton(onClick = { viewModel.action(a.id); confirm = null }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RowView(row: Row, s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, onAction: (Row.Action) -> Unit) {
    when (row) {
        is Row.Header -> Text(
            row.title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        )
        is Row.Note -> Surface(
            color = if (row.warning) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Row(Modifier.padding(14.dp)) {
                if (row.warning) { Icon(Icons.Outlined.WarningAmber, null); Spacer(Modifier.width(10.dp)) }
                Column {
                    Text(row.title, fontWeight = FontWeight.SemiBold)
                    row.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        is Row.Toggle -> {
            val enabled = row.enabled(s)
            val checked = row.get(s)
            ListItem(
                headlineContent = { Text(row.title) },
                supportingContent = row.summary?.let { { Text(it) } },
                trailingContent = { Switch(checked = checked, onCheckedChange = { v -> update { row.set(it, v) } }, enabled = enabled) },
                modifier = Modifier.alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled) { update { row.set(it, !checked) } },
            )
        }
        is Row.Choice -> {
            var open by remember { mutableStateOf(false) }
            val enabled = row.enabled(s)
            val current = row.get(s)
            ListItem(
                headlineContent = { Text(row.title) },
                supportingContent = { Text(row.options.firstOrNull { it.first == current }?.second ?: current) },
                modifier = Modifier.alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled) { open = true },
            )
            if (open) AlertDialog(
                onDismissRequest = { open = false },
                title = { Text(row.title) },
                text = {
                    Column {
                        row.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
                        row.options.forEach { (value, label) ->
                            Row(Modifier.fillMaxWidth().clickable { update { row.set(it, value) }; open = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = value == current, onClick = { update { row.set(it, value) }; open = false })
                                Text(label)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
            )
        }
        is Row.Slider -> {
            val enabled = row.enabled(s)
            var local by remember(row.get(s)) { mutableFloatStateOf(row.get(s)) }
            Column(Modifier.alpha(if (enabled) 1f else 0.5f).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(row.format(local), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                row.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Slider(
                    value = local, onValueChange = { local = it }, valueRange = row.range, steps = row.steps, enabled = enabled,
                    onValueChangeFinished = { val v = local; update { row.set(it, v) } },
                )
            }
        }
        is Row.Words -> WordsEditor(row, s, update)
        is Row.MultiChoice -> MultiEditor(row, s, update)
        is Row.Action -> ListItem(
            headlineContent = { Text(row.title, color = if (row.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
            supportingContent = row.summary?.let { { Text(it) } },
            modifier = Modifier.clickable { onAction(row) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordsEditor(row: Row.Words, s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val words = row.get(s)
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun add() {
        val w = input.trim().let { if (row.lowercase) it.lowercase() else it }
        if (w.isEmpty()) return
        row.validate(w)?.let { error = it; return }
        if (w !in words) update { row.set(it, words + w) }
        input = ""
        error = null
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(row.title, style = MaterialTheme.typography.bodyLarge)
        row.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            words.forEach { w ->
                InputChip(selected = false, onClick = { update { row.set(it, words - w) } }, label = { Text(w) }, trailingIcon = { Icon(Icons.Outlined.Close, "Remove $w") })
            }
        }
        OutlinedTextField(
            input, { input = it; error = null },
            placeholder = { Text(row.placeholder) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = true,
            trailingIcon = { IconButton(onClick = ::add) { Icon(Icons.Outlined.Add, "Add") } },
            keyboardActions = KeyboardActions(onDone = { add() }),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MultiEditor(row: Row.MultiChoice, s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val selected = row.get(s)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(row.title, style = MaterialTheme.typography.bodyLarge)
        row.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val ordered = if (row.ordered) selected.mapNotNull { k -> row.options.firstOrNull { it.first == k } } + row.options.filter { it.first !in selected } else row.options
        ordered.forEach { (key, label) ->
            val on = key in selected
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = on, onCheckedChange = { v -> update { row.set(it, if (v) selected + key else selected - key) } })
                Text(label, Modifier.weight(1f))
                if (row.ordered && on) {
                    val i = selected.indexOf(key)
                    IconButton(onClick = { if (i > 0) update { row.set(it, selected.toMutableList().apply { add(i - 1, removeAt(i)) }) } }, enabled = i > 0) { Icon(Icons.Outlined.ArrowUpward, "Move up") }
                    IconButton(onClick = { if (i < selected.lastIndex) update { row.set(it, selected.toMutableList().apply { add(i + 1, removeAt(i)) }) } }, enabled = i < selected.lastIndex) { Icon(Icons.Outlined.ArrowDownward, "Move down") }
                }
            }
        }
    }
}
