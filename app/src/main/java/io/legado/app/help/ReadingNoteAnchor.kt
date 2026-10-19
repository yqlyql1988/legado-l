package io.legado.app.help

/** Quote selectors use UTF-16 positions, as do the reader and the legacy backup files. */
object ReadingNoteAnchor {
    data class Match(val start: Int, val end: Int)

    fun find(
        text: String,
        quote: String,
        position: Int,
        prefix: String = "",
        suffix: String = "",
        trustPosition: Boolean = true
    ): Match? {
        if (quote.isBlank() || text.isEmpty()) return null
        val exact = occurrences(text, quote)
        if (exact.size == 1) return Match(exact.single(), exact.single() + quote.length)
        if (exact.isNotEmpty()) {
            val contextual = exact.filter {
                (prefix.isNotEmpty() || suffix.isNotEmpty()) &&
                    (prefix.isEmpty() || text.take(it).endsWith(prefix)) &&
                    (suffix.isEmpty() || text.drop(it + quote.length).startsWith(suffix))
            }
            if (contextual.size == 1) return Match(contextual.single(), contextual.single() + quote.length)
            if (trustPosition && position in exact && prefix.isEmpty() && suffix.isEmpty()) {
                return Match(position, position + quote.length)
            }
            return null
        }
        // Formatting-only changes may add spaces or paragraph indentation. Preserve punctuation.
        val normalizedQuote = normalize(quote)
        if (normalizedQuote.length < 8) return null
        val offsets = ArrayList<Int>()
        val normalizedText = buildString {
            text.forEachIndexed { index, char ->
                if (!char.isWhitespace() && char != '\u00a0') {
                    append(char)
                    offsets.add(index)
                }
            }
        }
        val matches = occurrences(normalizedText, normalizedQuote)
        val candidates = if (matches.size <= 1) matches else matches.filter {
            (prefix.isNotEmpty() || suffix.isNotEmpty()) &&
                (prefix.isEmpty() || normalizedText.take(it).endsWith(normalize(prefix))) &&
                (suffix.isEmpty() || normalizedText.drop(it + normalizedQuote.length).startsWith(normalize(suffix)))
        }
        val hit = candidates.singleOrNull() ?: return null
        return Match(offsets[hit], offsets[hit + normalizedQuote.length - 1] + 1)
    }

    private fun normalize(text: String) = text.filterNot { it.isWhitespace() || it == '\u00a0' }

    private fun occurrences(text: String, quote: String): List<Int> {
        val result = ArrayList<Int>()
        var position = text.indexOf(quote)
        while (position >= 0) {
            result.add(position)
            position = text.indexOf(quote, position + 1)
        }
        return result
    }
}
