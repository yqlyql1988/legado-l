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

data class BookTagOverviewStat(
    val name: String,
    val totalBookCount: Int,
    val sourceBookCount: Int,
    val userBookCount: Int,
)

enum class BookTagSort {
    TOTAL,
    USER,
}

enum class BookTagFilter {
    ALL,
    USER,
    SOURCE;

    fun includes(tag: BookTagOverviewStat): Boolean = when (this) {
        ALL -> true
        USER -> tag.userBookCount > 0
        SOURCE -> tag.sourceBookCount > 0
    }
}

fun sortBookTagOverview(
    tags: List<BookTagOverviewStat>,
    sort: BookTagSort,
): List<BookTagOverviewStat> = tags.sortedWith(
    compareByDescending<BookTagOverviewStat> {
        if (sort == BookTagSort.USER) it.userBookCount else it.totalBookCount
    }.thenBy { it.name.lowercase(Locale.ROOT) }
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

fun aggregateBookTagOverview(
    books: Iterable<ShelfBookTags>,
    sort: BookTagSort = BookTagSort.TOTAL,
): List<BookTagOverviewStat> {
    data class Counts(
        var totalBooks: Int = 0,
        var sourceBooks: Int = 0,
        var userBooks: Int = 0,
    )

    val names = linkedMapOf<String, String>()
    val counts = linkedMapOf<String, Counts>()
    books.forEach { book ->
        val sourceTags = book.kind.bookTags()
        val sourceKeys = sourceTags.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
        sourceTags.forEach { tag ->
            val key = tag.lowercase(Locale.ROOT)
            names.putIfAbsent(key, tag)
            counts.getOrPut(key) { Counts() }.apply {
                totalBooks++
                sourceBooks++
            }
        }
        book.customTag.bookTags().forEach { tag ->
            val key = tag.lowercase(Locale.ROOT)
            names.putIfAbsent(key, tag)
            counts.getOrPut(key) { Counts() }.apply {
                userBooks++
                if (key !in sourceKeys) totalBooks++
            }
        }
    }
    return sortBookTagOverview(counts.map { (key, tagCounts) ->
        BookTagOverviewStat(
            name = names.getValue(key),
            totalBookCount = tagCounts.totalBooks,
            sourceBookCount = tagCounts.sourceBooks,
            userBookCount = tagCounts.userBooks,
        )
    }, sort)
}

fun addBookTag(value: String?, tag: String): String? {
    return normalizeBookTags(value.bookTags() + tag)
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
