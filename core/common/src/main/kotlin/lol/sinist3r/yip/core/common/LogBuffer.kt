package lol.sinist3r.yip.core.common

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

/** In-memory ring buffer of recent log lines for the in-app log viewer. Never written to disk. */
object LogBuffer : Timber.Tree() {
    private const val MAX = 800
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val tokenPattern = Regex("""(?i)(oauth |bearer |access_token=|token=|sig=)[^\s&"]+""")

    @Volatile var verbose = false

    override fun isLoggable(tag: String?, priority: Int) = verbose || priority >= Log.INFO

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) { Log.ERROR -> "E"; Log.WARN -> "W"; Log.INFO -> "I"; Log.DEBUG -> "D"; else -> "V" }
        val text = "${fmt.format(Date())} $level/${tag ?: "yip"}: $message${t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""}"
        val safe = tokenPattern.replace(text) { it.groupValues[1] + "***" }
        synchronized(lines) {
            lines.addLast(safe)
            while (lines.size > MAX) lines.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
    fun clear() = synchronized(lines) { lines.clear() }
}
