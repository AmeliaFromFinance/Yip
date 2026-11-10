package dev.yipchat.core.data

import dev.yipchat.core.data.chat.visibleItems
import dev.yipchat.core.model.ChatItem
import kotlin.time.Clock
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDelayTest {
    private val now = Clock.System.now()
    private val items = listOf(ChatItem.System("history", now, "a"), ChatItem.System("old", now, "b"), ChatItem.System("new", now, "c"))
    private val arrived = mapOf("old" to 1_000L, "new" to 9_000L) // "history" isn't delayed

    @Test fun holdsMessagesUntilTheStreamCatchesUp() =
        assertEquals(listOf("history", "old"), visibleItems(items, arrived, delayMs = 5_000, now = 10_000).map { it.id })

    @Test fun releasesOnceDue() =
        assertEquals(listOf("history", "old", "new"), visibleItems(items, arrived, delayMs = 5_000, now = 14_000).map { it.id })

    @Test fun noDelayShowsAll() = assertEquals(3, visibleItems(items, arrived, delayMs = 0, now = 0).size)
}
