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

/** Compact cards above chat. Tap to expand, × to hide that event. View only: voting happens on twitch.tv. */
@Composable
fun EventBanners(events: ChannelEvents, modifier: Modifier = Modifier) {
    val hidden = remember { mutableStateListOf<String>() }
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        events.hypeTrain?.takeIf { "ht:${it.id}" !in hidden }?.let { HypeTrainCard(it, now) { hidden += "ht:${it.id}" } }
        events.prediction?.takeIf { "pr:${it.id}" !in hidden }?.let { PredictionCard(it, now) { hidden += "pr:${it.id}" } }
        events.poll?.takeIf { "po:${it.id}" !in hidden }?.let { PollCard(it, now) { hidden += "po:${it.id}" } }
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
private fun PollCard(p: ChannelPoll, now: Long, onHide: () -> Unit) {
    val total = p.totalVotes.coerceAtLeast(1)
    val leader = p.choices.maxByOrNull { it.votes }
    val status = if (!p.active) "Ended" else p.endsAtMillis?.let { timeLeft(it, now) }
    EventCard(
        Icons.Outlined.BarChart, p.title, status, onHide,
        details = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.choices.forEach { c ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row { Text(c.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text("${pct(c.votes.toLong(), total.toLong())}%", style = MaterialTheme.typography.labelLarge) }
                        Bar(c.votes / total.toFloat())
                    }
                }
                Text("${p.totalVotes} votes · vote on twitch.tv", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) {
        leader?.let { Text("Leading: ${it.title} (${pct(it.votes.toLong(), total.toLong())}%) · ${p.totalVotes} votes", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun PredictionCard(p: Prediction, now: Long, onHide: () -> Unit) {
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
                    }
                }
                Text("Predict on twitch.tv", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) {
        Text(p.outcomes.joinToString("  vs  ") { "${it.title} ${pct(it.points, total)}%" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
