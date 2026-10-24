package io.legado.app.data.entities

import java.util.Locale

private val bookTagSeparators = Regex("[,，、\\n\\r]")

fun String?.bookTags(): List<String> {
    if (this.isNullOrBlank()) return emptyList()
    return split(bookTagSeparators)
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .toList()
}

fun normalizeBookTags(value: String?): String? {
    return value.bookTags()
        .joinToString(",")
        .takeIf(String::isNotEmpty)
}

data class ShelfBookTags(
    val bookUrl: String,
    val kind: String?,
    val customTag: String?,
) {
    fun hasTag(tag: String): Boolean {
        val normalizedTag = tag.trim()
        if (normalizedTag.isEmpty()) return false
        return kind.bookTags().any { it.equals(normalizedTag, ignoreCase = true) } ||
            customTag.bookTags().any { it.equals(normalizedTag, ignoreCase = true) }
    }
}

data class BookTagStat(
    val name: String,
    val bookCount: Int,
)

fun Book.userTags(): List<String> = customTag.bookTags()

fun Book.sourceTags(): List<String> = kind.bookTags()

fun Book.displayTags(): List<String> = (getKindList() + userTags())
    .distinctBy { it.lowercase(Locale.ROOT) }

fun normalizeBookTags(tags: Iterable<String>): String? {
    return tags.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .joinToString(",")
        .takeIf(String::isNotEmpty)
}

fun aggregateBookTagStats(
    books: Iterable<ShelfBookTags>,
    sourceTags: Boolean,
): List<BookTagStat> {
    val names = linkedMapOf<String, String>()
    val bookUrls = linkedMapOf<String, MutableSet<String>>()
    books.forEach { book ->
        val tags = if (sourceTags) book.kind.bookTags() else book.customTag.bookTags()
        tags.forEach { tag ->
            val key = tag.lowercase(Locale.ROOT)
            names.putIfAbsent(key, tag)
            bookUrls.getOrPut(key) { linkedSetOf() }.add(book.bookUrl)
        }
    }
    return names.keys
        .map { key -> BookTagStat(names.getValue(key), bookUrls.getValue(key).size) }
        .sortedWith(compareByDescending<BookTagStat> { it.bookCount }.thenBy { it.name.lowercase(Locale.ROOT) })
}

fun replaceBookTag(value: String?, oldTag: String, newTag: String): String? {
    return normalizeBookTags(value.bookTags().map {
        if (it.equals(oldTag, ignoreCase = true)) newTag else it
    })
}

fun removeBookTag(value: String?, tag: String): String? {
    return normalizeBookTags(value.bookTags().filterNot {
        it.equals(tag, ignoreCase = true)
    })
}
