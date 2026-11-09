package lol.sinist3r.yip.feature.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import lol.sinist3r.yip.core.model.ChannelRewards
import lol.sinist3r.yip.core.model.CustomReward

sealed interface RewardsState {
    data object Loading : RewardsState
    data class Ready(val data: ChannelRewards, val busy: String? = null, val message: String? = null) : RewardsState
    data class Failed(val reason: String) : RewardsState
}

/** Channel points balance and custom rewards. Redeeming needs the twitch.tv connection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewardsSheet(state: RewardsState, onRedeem: (CustomReward, String?) -> Unit, onConnect: () -> Unit, onDismiss: () -> Unit) {
    var confirm by remember { mutableStateOf<CustomReward?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.heightIn(max = 640.dp).padding(bottom = 16.dp)) {
            when (state) {
                RewardsState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is RewardsState.Failed -> Text(state.reason, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                is RewardsState.Ready -> {
                    val d = state.data
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.pointsName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        d.balance?.let { Text("◆ ${"%,d".format(it)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
                    }
                    if (d.balance == null) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Connect twitch.tv to see your points and redeem", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = onConnect) { Text("Connect") }
                            }
                        }
                    }
                    state.message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                    if (!d.enabled || d.rewards.isEmpty()) {
                        Text("This channel has no rewards.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyVerticalGrid(GridCells.Adaptive(104.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(d.rewards, key = { it.id }) { r ->
                                val affordable = d.balance.let { b -> b == null || b >= r.cost }
                                RewardTile(r, enabled = r.available && affordable && state.busy == null, busy = state.busy == r.id) { confirm = r }
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text("Sub-only rewards need a subscription to this channel.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { r ->
        var text by remember(r.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(r.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.prompt?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (r.needsInput) OutlinedTextField(text, { text = it.take(200) }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                FilledTonalButton(enabled = !r.needsInput || text.isNotBlank(), onClick = { confirm = null; onRedeem(r, text.takeIf { r.needsInput }) }) {
                    Text("Redeem for ${"%,d".format(r.cost)}")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RewardTile(r: CustomReward, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    val bg = r.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: MaterialTheme.colorScheme.primaryContainer
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClickLabel = "Redeem ${r.title}", onClick = onClick).alpha(if (enabled) 1f else 0.45f).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.4f).clip(RoundedCornerShape(8.dp)).background(bg), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(24.dp)) else AsyncImage(r.imageUrl, null, Modifier.size(40.dp))
        }
        Text(r.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        val cost = if (r.cost >= 100_000) compact(r.cost.toLong()) else "%,d".format(r.cost)
        Text(if (r.subOnly) "◆ $cost · subs" else "◆ $cost", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
