package dev.yipchat.core.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Process
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DayUsage(val mobile: Long = 0, val wifi: Long = 0, val channels: Map<String, Long> = emptyMap()) {
    val total get() = mobile + wifi
}

/**
 * Yip's own data use, per day. Android only gives an app-wide byte count, so it's sampled every 30s and
 * each slice goes to the network in use (mobile = metered) and to whatever channel is playing.
 */
@UnstableApi
@Singleton
class DataUsage @Inject constructor(@ApplicationContext private val context: Context, private val playback: PlaybackController) {
    private val prefs = context.getSharedPreferences("data_usage", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _days = MutableStateFlow(load())
    /** Keyed by ISO date (yyyy-MM-dd), last [KEEP_DAYS] days. */
    val days: StateFlow<Map<String, DayUsage>> = _days.asStateFlow()

    val supported get() = TrafficStats.getUidRxBytes(Process.myUid()) != TrafficStats.UNSUPPORTED.toLong()

    fun start() {
        if (job?.isActive == true || !supported) return
        job = scope.launch {
            var last = bytes()
            while (true) {
                delay(SAMPLE_MS)
                val now = bytes()
                val delta = now - last
                last = now
                if (delta > 0) add(delta)
            }
        }
    }

    fun reset() {
        _days.value = emptyMap()
        prefs.edit { remove(KEY) }
    }

    private fun bytes(): Long = Process.myUid().let { TrafficStats.getUidRxBytes(it) + TrafficStats.getUidTxBytes(it) }

    private fun mobile(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun add(delta: Long) {
        val today = LocalDate.now().toString()
        val st = playback.state.value
        val channel = st.source?.channelLogin?.takeIf { st.playing }
        val onMobile = mobile()
        val day = _days.value[today] ?: DayUsage()
        val next = day.copy(
            mobile = day.mobile + if (onMobile) delta else 0,
            wifi = day.wifi + if (onMobile) 0 else delta,
            channels = if (channel == null) day.channels else day.channels + (channel to (day.channels[channel] ?: 0) + delta),
        )
        val cutoff = LocalDate.now().minusDays(KEEP_DAYS.toLong()).toString()
        val all = (_days.value + (today to next)).filterKeys { it > cutoff }
        _days.value = all
        prefs.edit { putString(KEY, json.encodeToString(all)) }
    }

    private fun load(): Map<String, DayUsage> = runCatching { prefs.getString(KEY, null)?.let { json.decodeFromString<Map<String, DayUsage>>(it) } }.getOrNull().orEmpty()

    companion object {
        private const val KEY = "days"
        private const val SAMPLE_MS = 30_000L
        const val KEEP_DAYS = 30

        /** Totals over the last [days] days (today included). */
        fun sum(all: Map<String, DayUsage>, days: Int): DayUsage {
            val cutoff = LocalDate.now().minusDays(days.toLong()).toString()
            return all.filterKeys { it > cutoff }.values.fold(DayUsage()) { acc, d ->
                DayUsage(acc.mobile + d.mobile, acc.wifi + d.wifi, (acc.channels.keys + d.channels.keys).associateWith { (acc.channels[it] ?: 0) + (d.channels[it] ?: 0) })
            }
        }
    }
}
