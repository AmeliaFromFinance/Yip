package lol.sinist3r.yip.core.data.chat

/**
 * Highlight keywords. Plain entries match whole words, ignoring case ("cat" doesn't match
 * "category"). Entries wrapped in slashes are regular expressions: /^!(so|shoutout)/
 */
class KeywordMatcher(entries: List<String>) {
    private val patterns: List<Regex> = entries.mapNotNull(::compile)

    fun matches(text: String): Boolean = patterns.any { it.containsMatchIn(text) }

    companion object {
        fun isPattern(entry: String) = entry.length > 2 && entry.startsWith('/') && entry.endsWith('/')

        /** Null when the entry is a valid keyword, otherwise why it isn't. */
        fun problem(entry: String): String? = if (isPattern(entry) && compile(entry) == null) "That pattern isn't valid" else null

        private fun compile(entry: String): Regex? {
            val e = entry.trim()
            if (e.isEmpty()) return null
            return runCatching {
                if (isPattern(e)) Regex(e.substring(1, e.length - 1), RegexOption.IGNORE_CASE)
                else Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(e) + "(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
            }.getOrNull()
        }
    }
}
