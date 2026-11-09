package lol.sinist3r.yip.feature.watch

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import lol.sinist3r.yip.core.model.ChannelEvents
import lol.sinist3r.yip.core.model.ChannelPoll
import lol.sinist3r.yip.core.model.HypeTrain
import lol.sinist3r.yip.core.model.Prediction
import lol.sinist3r.yip.core.model.PredictionStatus

/** What the cards can do; null means view only (no twitch.tv connection). */
class EventActions(
    val votedPolls: Set<String>,
    val balance: Int?,
    val onVote: (ChannelPoll, lol.sinist3r.yip.core.model.PollChoice, Int) -> Unit,
    val onPredict: (Prediction, lol.sinist3r.yip.core.model.PredictionOutcome, Int) -> Unit,
)

/** Compact cards above chat. Tap to expand, × to hide that event. */
@Composable
fun EventBanners(events: ChannelEvents, modifier: Modifier = Modifier, actions: EventActions? = null) {
    val hidden = remember { mutableStateListOf<String>() }
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        events.hypeTrain?.takeIf { "ht:${it.id}" !in hidden }?.let { HypeTrainCard(it, now) { hidden += "ht:${it.id}" } }
        events.prediction?.takeIf { "pr:${it.id}" !in hidden }?.let { PredictionCard(it, now, actions) { hidden += "pr:${it.id}" } }
        events.poll?.takeIf { "po:${it.id}" !in hidden }?.let { PollCard(it, now, actions) { hidden += "po:${it.id}" } }
    }
}

@Composable
private fun EventCard(icon: ImageVector, title: String, trailing: String?, onHide: () -> Unit, details: (@Composable () -> Unit)? = null, summary: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.clickable(enabled = details != null, onClickLabel = if (open) "Show less" else "Show more") { open = !open }.padding(start = 12.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.padding(end = 8.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = onHide) { Icon(Icons.Outlined.Close, "Hide") }
            }
            Box(Modifier.padding(end = 12.dp)) { if (open && details != null) details() else summary() }
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        color = color,
        trackColor = color.copy(alpha = 0.18f),
        drawStopIndicator = {},
        gapSize = 0.dp,
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
    )
}

@Composable
private fun HypeTrainCard(h: HypeTrain, now: Long, onHide: () -> Unit) {
    EventCard(Icons.Outlined.LocalFireDepartment, "Hype Train · level ${h.level}", h.endsAtMillis?.let { timeLeft(it, now) }, onHide) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Bar(h.progress / h.goal.coerceAtLeast(1).toFloat())
            Text("${pct(h.progress.toLong(), h.goal.toLong())}% to level ${h.level + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PollCard(p: ChannelPoll, now: Long, actions: EventActions?, onHide: () -> Unit) {
    val total = p.totalVotes.coerceAtLeast(1)
    val leader = p.choices.maxByOrNull { it.votes }
    val status = if (!p.active) "Ended" else p.endsAtMillis?.let { timeLeft(it, now) }
    EventCard(
        Icons.Outlined.BarChart, p.title, status, onHide,
        details = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val voted = actions != null && p.id in actions.votedPolls
                val canVote = actions != null && p.active && !voted
                p.choices.forEach { c ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = canVote, onClickLabel = "Vote for ${c.title}") { actions?.onVote(p, c, 0) },
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row { Text(c.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text("${pct(c.votes.toLong(), total.toLong())}%", style = MaterialTheme.typography.labelLarge) }
                        Bar(c.votes / total.toFloat())
                        // Extra votes cost channel points, when the streamer allows it.
                        if (voted && p.active && p.pointsVoteCost != null) {
                            androidx.compose.material3.TextButton(onClick = { actions?.onVote(p, c, 1) }) { Text("+1 vote for ◆ ${"%,d".format(p.pointsVoteCost)}") }
                        }
                    }
                }
                Text(
                    "${p.totalVotes} votes · " + when {
                        actions == null -> "connect twitch.tv to vote"
                        voted -> "you voted"
                        p.active -> "tap a choice to vote"
                        else -> "voting closed"
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        leader?.let { Text("Leading: ${it.title} (${pct(it.votes.toLong(), total.toLong())}%) · ${p.totalVotes} votes", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun PredictionCard(p: Prediction, now: Long, actions: EventActions?, onHide: () -> Unit) {
    var predictOn by remember(p.id) { mutableStateOf<lol.sinist3r.yip.core.model.PredictionOutcome?>(null) }
    val total = p.totalPoints.coerceAtLeast(1)
    val status = when (p.status) {
        PredictionStatus.ACTIVE -> p.locksAtMillis?.let { timeLeft(it, now) } ?: "Open"
        PredictionStatus.LOCKED, PredictionStatus.RESOLVE_PENDING -> "Locked"
        else -> null
    }
    EventCard(
        Icons.Outlined.Savings, p.title, status, onHide,
        details = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.outcomes.forEach { o ->
                    val c = outcomeColor(o.color)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row {
                            Text(o.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c)
                            Text("${pct(o.points, total)}% · ${compact(o.points)} pts · ${o.users} people", style = MaterialTheme.typography.labelMedium)
                        }
                        Bar(o.points / total.toFloat(), c)
                        val mine = p.myOutcomeId == o.id
                        if (mine) Text("Your pick · ◆ ${"%,d".format(p.myPoints ?: 0)}", style = MaterialTheme.typography.labelMedium, color = c)
                        // Once you've picked, you can only add to the same side.
                        if (actions != null && p.status == PredictionStatus.ACTIVE && (p.myOutcomeId == null || mine)) {
                            androidx.compose.material3.TextButton(onClick = { predictOn = o }) { Text(if (mine) "Add points" else "Predict ${o.title}") }
                        }
                    }
                }
                Text(
                    if (actions == null) "Connect twitch.tv to predict" else actions.balance?.let { "You have ◆ ${"%,d".format(it)}" } ?: "Uses your channel points",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Text(p.outcomes.joinToString("  vs  ") { "${it.title} ${pct(it.points, total)}%" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    predictOn?.let { o ->
        var amount by remember(o.id) { mutableStateOf("") }
        val bal = actions?.balance
        val value = amount.toIntOrNull() ?: 0
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { predictOn = null },
            title = { Text("Predict ${o.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    bal?.let { Text("You have ◆ ${"%,d".format(it)}", style = MaterialTheme.typography.bodyMedium) }
                    androidx.compose.material3.OutlinedTextField(
                        amount, { amount = it.filter(Char::isDigit).take(9) }, label = { Text("Points") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(100, 1_000, 10_000).filter { bal == null || it <= bal }.forEach { n ->
                            androidx.compose.material3.AssistChip(onClick = { amount = n.toString() }, label = { Text("%,d".format(n)) })
                        }
                        bal?.takeIf { it > 0 }?.let { b -> androidx.compose.material3.AssistChip(onClick = { amount = b.toString() }, label = { Text("All") }) }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.FilledTonalButton(enabled = value > 0 && (bal == null || value <= bal), onClick = { predictOn = null; actions?.onPredict(p, o, value) }) {
                    Text("Predict ◆ ${"%,d".format(value)}")
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { predictOn = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun outcomeColor(c: String): Color = when (c.uppercase()) {
    "BLUE" -> Color(0xFF387AFF)
    "PINK" -> Color(0xFFF5009B)
    else -> MaterialTheme.colorScheme.primary
}

internal fun timeLeft(endsAt: Long, now: Long): String {
    val s = ((endsAt - now) / 1000).coerceAtLeast(0)
    return if (s == 0L) "Ending" else "%d:%02d left".format(s / 60, s % 60)
}

internal fun pct(part: Long, total: Long): Int = if (total <= 0) 0 else Math.round(part * 100.0 / total).toInt()

internal fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}
