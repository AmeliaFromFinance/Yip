package lol.sinist3r.yip.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import lol.sinist3r.yip.core.playback.DataUsage
import lol.sinist3r.yip.core.playback.DayUsage

@androidx.annotation.OptIn(UnstableApi::class)
@HiltViewModel
class DataUsageViewModel @Inject constructor(private val usage: DataUsage) : ViewModel() {
    val days = usage.days
    val supported get() = usage.supported
    fun reset() = usage.reset()
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun DataUsageScreen(onBack: () -> Unit, viewModel: DataUsageViewModel = hiltViewModel()) {
    val days by viewModel.days.collectAsStateWithLifecycle()
    var range by rememberSaveable { mutableIntStateOf(1) }
    val ranges = listOf(1 to "Today", 7 to "7 days", 30 to "30 days")
    val total = remember(days, range) { DataUsage.sum(days, ranges[range].first) }
    androidx.compose.material3.Scaffold(topBar = {
        TopAppBar(
            title = { Text("Data usage") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { if (days.isNotEmpty()) TextButton(onClick = viewModel::reset) { Text("Reset") } },
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!viewModel.supported) {
                item { Text("This phone doesn't report app data use.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                return@LazyColumn
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ranges.forEachIndexed { i, (_, label) ->
                        SegmentedButton(selected = range == i, onClick = { range = i }, shape = SegmentedButtonDefaults.itemShape(i, ranges.size)) { Text(label) }
                    }
                }
            }
            item { Totals(total) }
            val channels = total.channels.entries.sortedByDescending { it.value }
            if (channels.isNotEmpty()) {
                item { Text("By channel", style = MaterialTheme.typography.titleSmall) }
                items(channels.take(20), key = { it.key }) { (login, bytes) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row { Text(login, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Text(size(bytes), style = MaterialTheme.typography.bodyMedium) }
                        LinearProgressIndicator(
                            progress = { (bytes.toFloat() / channels.first().value).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), drawStopIndicator = {},
                        )
                    }
                }
            }
            item {
                Text(
                    "Counted while Yip is running, from Android's per-app total. Channels count only while that stream or video is playing.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Totals(d: DayUsage) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Stat("Mobile data", d.mobile, Modifier.weight(1f))
            Stat("Wi-Fi", d.wifi, Modifier.weight(1f))
            Stat("Total", d.total, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, bytes: Long, modifier: Modifier) {
    Column(modifier) {
        Text(size(bytes), style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}
