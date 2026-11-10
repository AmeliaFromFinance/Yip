package dev.yipchat.core.data

import dev.yipchat.core.data.chat.KeywordMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordMatcherTest {
    @Test fun wholeWordsAnyCase() {
        val m = KeywordMatcher(listOf("cat", "good game"))
        assertTrue(m.matches("look at that CAT!"))
        assertTrue(m.matches("Good Game everyone"))
        assertFalse(m.matches("which category is this"))
        assertFalse(m.matches("concatenate"))
    }

    @Test fun slashesMeanPattern() {
        val m = KeywordMatcher(listOf("/^!(so|shoutout)\\b/"))
        assertTrue(m.matches("!so eepyAmelia"))
        assertFalse(m.matches("say !so later"))
    }

    @Test fun badPatternIsReportedAndIgnored() {
        assertEquals("That pattern isn't valid", KeywordMatcher.problem("/(unclosed/"))
        assertNull(KeywordMatcher.problem("plain words"))
        assertFalse(KeywordMatcher(listOf("/(unclosed/")).matches("(unclosed"))
    }
}
