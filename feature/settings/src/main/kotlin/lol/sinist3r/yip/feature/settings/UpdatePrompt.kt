package lol.sinist3r.yip.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import lol.sinist3r.yip.core.data.update.UpdateRepository
import lol.sinist3r.yip.core.data.update.UpdateState
import lol.sinist3r.yip.core.network.update.AppRelease

@HiltViewModel
class UpdateViewModel @Inject constructor(private val repo: UpdateRepository) : ViewModel() {
    val state = repo.state
    val installedVersion = repo.installedVersion
    val whatsNew = repo.whatsNew
    fun dismissWhatsNew() = repo.dismissWhatsNew()

    init { viewModelScope.launch { repo.checkInBackground() } }

    fun check() = viewModelScope.launch { repo.check(manual = true) }
    fun update(r: AppRelease) = viewModelScope.launch { repo.downloadAndInstall(r) }
    fun skip(r: AppRelease) = viewModelScope.launch { repo.skip(r) }
    fun dismiss() = repo.dismiss()
    fun retryInstall(s: UpdateState.NeedsPermission) = repo.install(s.release, s.file)
    fun permissionIntent() = repo.permissionIntent()
}

/** App-wide update dialog. Shows only when there's something to act on. */
@Composable
fun UpdatePrompt(viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Back from the "Install unknown apps" screen: carry on if it was allowed.
    LifecycleResumeEffect(state) {
        (state as? UpdateState.NeedsPermission)?.let { if (context.packageManager.canRequestPackageInstalls()) viewModel.retryInstall(it) }
        onPauseOrDispose { }
    }
    val news by viewModel.whatsNew.collectAsStateWithLifecycle()
    news?.takeIf { state !is UpdateState.Available }?.let { n ->
        AlertDialog(
            onDismissRequest = viewModel::dismissWhatsNew,
            title = { Text("What's new in ${n.version}") },
            text = {
                Text(n.notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = viewModel::dismissWhatsNew) { Text("Got it") } },
        )
    }
    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("Yip ${s.release.version} is available") },
            text = {
                Column {
                    Text("You have ${viewModel.installedVersion}.", style = MaterialTheme.typography.bodyMedium)
                    if (s.release.notes.isNotBlank()) {
                        Text(
                            s.release.notes,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.update(s.release) }) { Text("Update") } },
            dismissButton = {
                Column {
                    TextButton(onClick = viewModel::dismiss) { Text("Later") }
                    TextButton(onClick = { viewModel.skip(s.release) }) { Text("Skip this version") }
                }
            },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading Yip ${s.release.version}") },
            text = {
                Column {
                    val p = s.progress
                    if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text(p?.let { "${(it * 100).toInt()}%" } ?: "Starting…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {},
        )
        is UpdateState.NeedsPermission -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("Allow Yip to install updates") },
            text = { Text("Android needs your permission once. Turn on \"Allow from this source\", then come back.") },
            confirmButton = { TextButton(onClick = { context.startActivity(viewModel.permissionIntent()) }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("Cancel") } },
        )
        is UpdateState.Failed -> {
            val release = s.release
            AlertDialog(
                onDismissRequest = viewModel::dismiss,
                title = { Text("Update") },
                text = { Text(s.message) },
                confirmButton = {
                    if (release != null) TextButton(onClick = { viewModel.update(release) }) { Text("Try again") }
                    else TextButton(onClick = viewModel::dismiss) { Text("OK") }
                },
                dismissButton = { if (release != null) TextButton(onClick = viewModel::dismiss) { Text("Close") } },
            )
        }
        UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("You're up to date") },
            text = { Text("Yip ${viewModel.installedVersion} is the latest version.") },
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("OK") } },
        )
        else -> Unit
    }
}
