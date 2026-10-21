package io.legado.app.help

/** Quote selectors use UTF-16 positions, as do the reader and the legacy backup files. */
object ReadingNoteAnchor {
    data class Match(val start: Int, val end: Int)

    /**
     * Finds passages between a saved prefix and suffix. This is only a candidate
     * finder for changed text; callers must keep ambiguous results unresolved.
     */
    fun contextMatches(
        text: String,
        prefix: String,
        suffix: String,
        maxDistance: Int = 1200
    ): List<Match> {
        if (text.isEmpty() || prefix.isBlank() || suffix.isBlank()) return emptyList()
        val normalized = normalizedText(text)
        val normalizedPrefix = normalize(prefix)
        val normalizedSuffix = normalize(suffix)
        if (normalizedPrefix.length < 8 || normalizedSuffix.length < 8) return emptyList()
        val prefixHits = occurrences(normalized.text, normalizedPrefix)
        val suffixHits = occurrences(normalized.text, normalizedSuffix)
        if (prefixHits.isEmpty() || suffixHits.isEmpty()) return emptyList()
        return prefixHits.flatMap { prefixHit ->
            val contentStart = prefixHit + normalizedPrefix.length
            val nextPrefix = prefixHits.firstOrNull { it > prefixHit }
            suffixHits.filter { suffixHit ->
                suffixHit >= contentStart &&
                    suffixHit - contentStart <= maxDistance &&
                    (nextPrefix == null || suffixHit < nextPrefix)
            }.mapNotNull { suffixHit ->
                val rawStart = normalized.offsets.getOrNull(contentStart) ?: return@mapNotNull null
                val rawEnd = normalized.offsets.getOrNull(suffixHit)
                    ?: text.length
                if (rawEnd <= rawStart) null else Match(rawStart, rawEnd)
            }
        }.distinct()
    }

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

    private data class NormalizedText(val text: String, val offsets: IntArray)

    private fun normalizedText(text: String): NormalizedText {
        val offsets = ArrayList<Int>()
        val normalized = buildString {
            text.forEachIndexed { index, char ->
                if (!char.isWhitespace() && char != '\u00a0') {
                    append(char)
                    offsets.add(index)
                }
            }
        }
        return NormalizedText(normalized, offsets.toIntArray())
    }

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
