package lol.sinist3r.yip.core.common

import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun Int.compactCount(locale: Locale = Locale.getDefault()): String = toLong().compactCount(locale)

fun Long.compactCount(locale: Locale = Locale.getDefault()): String = when {
    this >= 1_000_000 -> String.format(locale, "%.1fM", this / 1_000_000.0).replace(".0M", "M")
    this >= 10_000 -> String.format(locale, "%.0fK", this / 1_000.0)
    this >= 1_000 -> String.format(locale, "%.1fK", this / 1_000.0).replace(".0K", "K")
    else -> toString()
}

fun Duration.clock(): String {
    val total = inWholeSeconds.coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun Long.secondsClock(): String = seconds.clock()
