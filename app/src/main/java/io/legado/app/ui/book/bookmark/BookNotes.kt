package io.legado.app.ui.book.bookmark

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.BookReview
import io.legado.app.data.entities.BookReviewArchive
import io.legado.app.data.entities.bookReviewOrder
import io.legado.app.data.entities.HighlightTag
import io.legado.app.data.entities.HighlightTagLink
import io.legado.app.data.entities.HighlightTagOrder
import io.legado.app.help.HighlightTags

internal enum class NoteType { ALL, BOOKMARK, HIGHLIGHT, REVIEW }

internal data class BookNote(
    val bookmark: Bookmark? = null,
    val highlight: BookHighlight? = null,
    val tags: List<HighlightTag> = emptyList()
) {
    init { require((bookmark == null) != (highlight == null)) }
    val type get() = if (bookmark != null) NoteType.BOOKMARK else NoteType.HIGHLIGHT
    val time get() = bookmark?.time ?: highlight!!.time
    // Primary keys belong to different tables.
    val id get() = "${type.name}:$time"
    val bookName get() = bookmark?.bookName ?: highlight!!.bookName
    val author get() = bookmark?.bookAuthor ?: highlight!!.bookAuthor
    val chapterIndex get() = bookmark?.chapterIndex ?: highlight!!.chapterIndex
    val chapterName get() = bookmark?.chapterName ?: highlight!!.chapterName
    val position get() = bookmark?.chapterPos ?: highlight!!.chapterPos
    val quote get() = bookmark?.bookText ?: highlight!!.bookText
    val note get() = bookmark?.content ?: highlight!!.note
    fun matches(query: String): Boolean = query.isBlank() ||
        listOf(bookName, author, chapterName, quote, note).any { it.contains(query, true) } ||
        tags.any { it.name.contains(query, true) }
}

internal data class NoteBook(
    val id: String, val bookUrl: String, val name: String, val author: String,
    val book: Book?, val notes: List<BookNote>, val ambiguous: Boolean = false,
    val reviews: List<BookReview> = emptyList()
) {
    val bookmarkCount get() = notes.count { it.bookmark != null }
    val highlightCount get() = notes.size - bookmarkCount
    val latestTime get() = maxOf(notes.maxOfOrNull { it.time } ?: 0L,
        reviews.maxOfOrNull { it.updatedAt } ?: 0L)
    val rating get() = reviews.takeIf { it.isNotEmpty() }?.let { BookReviewArchive(it).rating }
    fun asBook() = book ?: Book(bookUrl = bookUrl, name = name, author = author,
        coverUrl = reviews.firstOrNull()?.coverUrl)
    fun matches(query: String): Boolean = query.isBlank() ||
        name.contains(query.trim(), true) || author.contains(query.trim(), true) ||
        notes.any { it.matches(query.trim()) } || matchingReviews(query).isNotEmpty()
    fun matchingReviews(query: String): List<BookReview> = reviews.filter {
        query.isBlank() || listOf(it.bookName, it.bookAuthor, it.content, it.reviewDate)
            .any { text -> text.contains(query.trim(), true) }
    }
}

/** Keep source-bound highlights separate; never guess between same-name books. */
internal fun archiveBookNotes(
    bookmarks: List<Bookmark>, highlights: List<BookHighlight>, books: List<Book>,
    tags: List<HighlightTag>, links: List<HighlightTagLink>,
    orders: List<HighlightTagOrder> = emptyList(), reviews: List<BookReview> = emptyList()
): List<NoteBook> {
    fun metadata(name: String, author: String) = "metadata:${name.length}:$name$author"
    val booksByUrl = books.associateBy { it.bookUrl }
    val booksByName = books.groupBy { it.name to it.author }
    val highlightsByName = highlights.filter { it.bookUrl.isNotBlank() }
        .groupBy { it.bookName to it.bookAuthor }
    fun legacyOwners(name: String, author: String): List<String> =
        (booksByName[name to author].orEmpty().map { it.bookUrl } +
            highlightsByName[name to author].orEmpty().map { it.bookUrl })
            .filter(String::isNotBlank).distinct()
    val orderedTags = highlights.map { it.bookUrl }.distinct().associateWith { url ->
        HighlightTags.forBook(tags, url, orders.filter { it.bookUrl == url }, includeHidden = true)
    }
    val linksByTime = links.groupBy { it.highlightTime }
    val entries = highlights.map { highlight ->
        val ids = linksByTime[highlight.time].orEmpty().mapTo(hashSetOf()) { it.tagId }
        val attached = orderedTags[highlight.bookUrl].orEmpty().filter { it.id in ids }
        BookNote(highlight = highlight, tags = attached)
    } + bookmarks.map { BookNote(bookmark = it) }
    val noteBooks = entries.groupBy { entry ->
        val url = entry.highlight?.bookUrl?.takeIf(String::isNotBlank)
            ?: legacyOwners(entry.bookName, entry.author).singleOrNull()
        url?.let { "url:$it" } ?: metadata(entry.bookName, entry.author)
    }.map { (id, notes) ->
        val url = id.takeIf { it.startsWith("url:") }?.removePrefix("url:").orEmpty()
        val book = booksByUrl[url]
        NoteBook(id, url, book?.name ?: notes.first().bookName,
            book?.author ?: notes.first().author, book, notes,
            url.isEmpty() && legacyOwners(notes.first().bookName, notes.first().author).size > 1)
    }
    // Reviews keep their existing work-wide identity across source changes. Notes keep theirs.
    val reviewsByBook = reviews.groupBy { it.bookName to it.bookAuthor }
        .mapValues { (_, values) -> values.sortedWith(bookReviewOrder) }
    val existingNames = noteBooks.mapTo(hashSetOf()) { it.name to it.author }
    val reviewOnly = reviewsByBook.filterKeys { it !in existingNames }.map { (name, values) ->
        val shelf = booksByName[name].orEmpty()
        val owner = shelf.firstOrNull { it.bookUrl == values.first().bookUrl } ?: shelf.singleOrNull()
        val url = owner?.bookUrl ?: values.first().bookUrl
        NoteBook(if (owner != null) "url:$url" else metadata(name.first, name.second),
            url, name.first, name.second, owner, emptyList(), reviews = values)
    }
    return (noteBooks.map { it.copy(reviews = reviewsByBook[it.name to it.author].orEmpty()) } +
        reviewOnly).sortedWith(compareByDescending<NoteBook> { it.latestTime }.thenBy { it.id })
}

internal fun filterBookNotes(
    notes: List<BookNote>, type: NoteType, tagId: String?, query: String,
    recentFirst: Boolean, chapterIndexes: Map<String, Int> = emptyMap()
): List<BookNote> = notes.filter { entry ->
    (type == NoteType.ALL || entry.type == type) && entry.matches(query.trim()) &&
        (tagId == null || entry.highlight != null &&
            if (tagId.isEmpty()) entry.tags.isEmpty() else entry.tags.any { it.id == tagId })
}.sortedWith(if (recentFirst) compareByDescending<BookNote> { it.time }.thenBy { it.id }
else compareBy<BookNote>(
    { it.highlight?.chapterUrl?.takeIf(String::isNotBlank)?.let { url ->
        chapterIndexes[url] ?: Int.MAX_VALUE
    } ?: it.chapterIndex },
    { it.highlight?.let { h -> (h.chapterPos - h.layoutTitleLength.coerceAtLeast(0)).coerceAtLeast(0) }
        ?: it.position }, { it.time }, { it.id }
))

internal fun notesMarkdown(notes: List<BookNote>): String = buildString {
    notes.groupBy { it.bookName to it.author }.forEach { (book, entries) ->
        append("## ${book.first} · ${book.second}\n\n")
        entries.forEach { entry ->
            append("### ${entry.chapterName}\n\n")
            append(if (entry.bookmark != null) "书签\n\n" else "标注\n\n")
            if (entry.quote.isNotBlank()) {
                append(entry.quote.lineSequence().joinToString("\n") { "> $it" })
                append("\n\n")
            }
            if (entry.note.isNotBlank()) append("${entry.note}\n\n")
            if (entry.tags.isNotEmpty()) append("标签：${entry.tags.joinToString("、") { it.name }}\n\n")
        }
    }
}
