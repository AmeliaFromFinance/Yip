package lol.sinist3r.yip.core.designsystem.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import lol.sinist3r.yip.core.model.BedtimeSettings

/** Whether bedtime is active right now; re-checked every minute. */
@Composable
fun rememberBedtimeActive(bedtime: BedtimeSettings): Boolean {
    var active by remember(bedtime) { mutableStateOf(bedtime.activeNow()) }
    LaunchedEffect(bedtime) {
        if (!bedtime.enabled) return@LaunchedEffect
        while (true) {
            // Wake just after the next minute boundary.
            delay(60_000L - System.currentTimeMillis() % 60_000L + 200L)
            active = bedtime.activeNow()
        }
    }
    return active
}
