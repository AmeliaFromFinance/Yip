package dev.yipchat.core.common

import java.util.concurrent.ConcurrentHashMap

/** Channel ids whose chat is on screen right now. Pings skip these. */
object VisibleChats {
    private val counts = ConcurrentHashMap<String, Int>()

    fun show(channelId: String) { counts.merge(channelId, 1, Int::plus) }
    fun hide(channelId: String) { counts.computeIfPresent(channelId) { _, n -> (n - 1).takeIf { it > 0 } } }
    fun isVisible(channelId: String): Boolean = counts.containsKey(channelId)
}
