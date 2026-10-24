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

fun Book.userTags(): List<String> = customTag.bookTags()

fun Book.displayTags(): List<String> = getKindList() + userTags()
