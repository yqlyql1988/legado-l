package io.legado.app.ui.book.bookmark

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.BookReview
import io.legado.app.data.entities.HighlightTag
import io.legado.app.data.entities.HighlightTagLink
import io.legado.app.data.entities.HighlightTagOrder
import io.legado.app.data.entities.ReadingNoteSource
import org.junit.Assert.*
import org.junit.Test

class BookNotesTest {
    private val book = Book(bookUrl = "source-a", name = "长夜书店", author = "林间")
    private fun bookmark(time: Long = 1, chapter: Int = 2, position: Int = 20) =
        Bookmark(time, book.name, book.author, chapter, position, "雨夜归人", "原文", "书签备注")
    private fun highlight(time: Long = 2, url: String = book.bookUrl, chapter: Int = 1) =
        BookHighlight(time = time, bookUrl = url, bookName = book.name, bookAuthor = book.author,
            chapterIndex = chapter, chapterPos = 30, chapterPosEnd = 32, chapterName = "窗边旧信",
            bookText = "摘录", note = "个人笔记")
    private fun archive(
        bookmarks: List<Bookmark> = listOf(bookmark()), highlights: List<BookHighlight> = listOf(highlight()),
        books: List<Book> = listOf(book), tags: List<HighlightTag> = emptyList(),
        links: List<HighlightTagLink> = emptyList(), orders: List<HighlightTagOrder> = emptyList(),
        reviews: List<BookReview> = emptyList()
    ) = archiveBookNotes(bookmarks, highlights, books, tags, links, orders, reviews)

    private fun review(id: String = "review", date: String = "2026-10-18", score: Int? = 9) =
        BookReview(id = id, bookName = book.name, bookAuthor = book.author, bookUrl = book.bookUrl,
            content = "重读后更喜欢安静的段落", reviewDate = date, rating = score,
            createdAt = 10, updatedAt = 20)

    @Test fun reviewOnlyBooksAppearWithoutShelfAndKeepCoverAndHistory() {
        val saved = review().copy(coverUrl = "saved-cover")
        val result = archive(emptyList(), emptyList(), emptyList(), reviews = listOf(saved)).single()
        assertNull(result.book)
        assertEquals(saved.coverUrl, result.asBook().coverUrl)
        assertEquals(listOf(saved), result.reviews)
        assertEquals(0, result.notes.size)
    }
    @Test fun reviewOnlyBookUsesCurrentSourceAfterChangingSource() {
        val current = book.copy(bookUrl = "new-source")
        val result = archive(emptyList(), emptyList(), listOf(current),
            reviews = listOf(review())).single()
        assertEquals(current, result.book)
        assertEquals(current.bookUrl, result.bookUrl)
    }
    @Test fun reviewsAttachToExistingNotesWithoutCreatingDuplicateBooks() {
        val result = archive(reviews = listOf(review())).single()
        assertEquals(2, result.notes.size)
        assertEquals(1, result.reviews.size)
        assertEquals(9, result.rating)
    }
    @Test fun reviewsFollowExistingWorkIdentityWithoutCombiningSourceBoundNotes() {
        val saved = review()
        val results = archive(highlights = listOf(highlight(2), highlight(3, "source-b")),
            books = listOf(book, book.copy(bookUrl = "source-b")), reviews = listOf(saved))
        assertEquals(3, results.size)
        assertTrue(results.all { it.reviews == listOf(saved) })
        assertEquals(1, results.flatMap { it.reviews }.distinctBy { it.id }.size)
        assertEquals(3, results.flatMap { it.notes }.size)
    }
    @Test fun reviewsFromOtherAuthorsRemainSeparate() {
        val results = archive(reviews = listOf(review().copy(bookAuthor = "另一作者")))
        assertEquals(2, results.size)
        assertTrue(results.single { it.author == book.author }.reviews.isEmpty())
        assertEquals(1, results.single { it.author == "另一作者" }.reviews.size)
    }
    @Test fun historySortAndInheritedRatingKeepExistingReviewSemantics() {
        val earlier = review("first", "2026-10-01", 8)
        val later = review("follow", "2026-10-18", null).copy(isFollowUp = true)
        val result = archive(reviews = listOf(earlier, later)).single()
        assertEquals(listOf("follow", "first"), result.reviews.map { it.id })
        assertEquals(8, result.rating)
        assertTrue(result.matches(" 重读后 "))
        assertEquals(listOf(later), result.matchingReviews("2026-10-18"))
        assertFalse(result.matches("没有的文字"))
    }
    @Test fun latestActivityIncludesReviewEditsAndDeletingReviewsKeepsNotes() {
        val withReview = archive(reviews = listOf(review().copy(updatedAt = 500))).single()
        assertEquals(500L, withReview.latestTime)
        assertEquals(2L, archive(reviews = emptyList()).single().latestTime)
        assertEquals(withReview.notes, archive(reviews = emptyList()).single().notes)
    }

    @Test fun combinesLegacyBookmarkWithUnambiguousSourceBoundHighlights() {
        val result = archive().single()
        assertEquals(book.bookUrl, result.bookUrl)
        assertEquals(book, result.book)
        assertEquals(1, result.bookmarkCount)
        assertEquals(1, result.highlightCount)
        assertEquals(2, result.notes.size)
    }

    @Test fun legacyHighlightFollowsUniqueSameNameAndAuthorBook() {
        val current = book.copy(bookUrl = "source-new")
        val result = archiveBookNotes(
            emptyList(), listOf(highlight(url = "source-old")), listOf(current),
            emptyList(), emptyList()
        ).single()
        assertEquals(current.bookUrl, result.bookUrl)
        assertEquals(current, result.book)
    }

    @Test fun explicitSourceMappingWinsWhenBookMetadataChanged() {
        val current = book.copy(bookUrl = "source-new", name = "改名后的书")
        val old = highlight(url = "source-old")
        val result = archiveBookNotes(
            emptyList(), listOf(old), listOf(current), emptyList(), emptyList(),
            sources = listOf(ReadingNoteSource("source-old", "work", current.bookUrl))
        ).single()
        assertEquals(current.bookUrl, result.bookUrl)
        assertEquals(current, result.book)
    }
    @Test fun sameTimestampAcrossTablesRemainsTwoSelectableRecords() {
        val notes = archive(highlights = listOf(highlight(time = 1))).single().notes
        assertEquals(2, notes.map { it.id }.distinct().size)
        assertEquals(setOf(NoteType.BOOKMARK, NoteType.HIGHLIGHT), notes.map { it.type }.toSet())
    }
    @Test fun sameNameDifferentSourcesNeverShareHighlightsOrGuessLegacyBookmarkOwner() {
        val results = archive(highlights = listOf(highlight(2), highlight(3, "source-b")),
            books = listOf(book, book.copy(bookUrl = "source-b")))
        assertEquals(3, results.size)
        assertEquals(2, results.count { it.notes.single().highlight != null })
        assertNull(results.single { it.bookmarkCount == 1 }.book)
    }
    @Test fun sameNameDifferentAuthorsRemainSeparate() {
        val results = archive(bookmarks = listOf(bookmark(), bookmark(3).copy(bookAuthor = "另一作者")))
        assertEquals(2, results.size)
        assertEquals(setOf("林间", "另一作者"), results.map { it.author }.toSet())
    }
    @Test fun missingBookRetainsAllNotesAndAllowsMetadataArchive() {
        val results = archive(books = emptyList())
        assertEquals(2, results.single().notes.size)
        assertNull(results.single().book)
        assertEquals("书签备注", results.single().notes.single { it.bookmark != null }.note)
    }
    @Test fun noShelfAndNoSourceStillKeepsBookmark() {
        val result = archive(highlights = emptyList(), books = emptyList()).single()
        assertEquals("", result.bookUrl)
        assertEquals("长夜书店", result.asBook().name)
    }
    @Test fun archiveSortUsesNewestRecordInsteadOfLastReadTime() {
        val second = book.copy(bookUrl = "second", name = "山海来信", durChapterTime = 99999)
        val results = archive(highlights = listOf(highlight(100), highlight(3, "second").copy(bookName = second.name)),
            books = listOf(book, second))
        assertEquals(book.bookUrl, results.first().bookUrl)
    }
    @Test fun classificationAndSecondaryLabelFilterComposeWithNoteSearch() {
        val tags = listOf(HighlightTag("first", "喜欢"), HighlightTag("second", "伏笔"))
        val notes = archive(tags = tags,
            links = tags.map { HighlightTagLink(2, it.id) }).single().notes
        val result = filterBookNotes(notes, NoteType.HIGHLIGHT, "second", "个人笔记", false)
        assertEquals(listOf(2L), result.map { it.time })
        assertTrue(filterBookNotes(notes, NoteType.BOOKMARK, "second", "", false).isEmpty())
    }
    @Test fun allAndUntaggedIncludeBothNoteTypesWithoutLabels() {
        val notes = archive().single().notes
        assertEquals(2, filterBookNotes(notes, NoteType.ALL, null, "", false).size)
        assertEquals(2, filterBookNotes(notes, NoteType.ALL, "", "", false).size)
        assertNotNull(filterBookNotes(notes, NoteType.BOOKMARK, "", "", false).single().bookmark)
    }
    @Test fun bookmarkLabelsShareDefinitionsButKeepOwnOrderAndParticipateInSearchFilterAndExport() {
        val tags = listOf(HighlightTag("clue", "伏笔", common = true, bookmarkCommon = false),
            HighlightTag("later", "待回看", bookmarkSortOrder = 2))
        val notes = archiveBookNotes(listOf(bookmark()), listOf(highlight()), listOf(book), tags,
            listOf(HighlightTagLink(2, "clue")), bookmarkLinks = listOf(
                io.legado.app.data.entities.BookmarkTagLink(1, "clue"),
                io.legado.app.data.entities.BookmarkTagLink(1, "later")),
            bookmarkOrders = listOf(
                io.legado.app.data.entities.BookmarkTagOrder(book.bookUrl, "later", 0),
                io.legado.app.data.entities.BookmarkTagOrder(book.bookUrl, "clue", 1)))
            .single().notes
        val saved = notes.single { it.bookmark != null }
        assertEquals(listOf("later", "clue"), saved.tags.map { it.id })
        assertFalse(saved.tags.last().common)
        assertEquals(2, filterBookNotes(notes, NoteType.ALL, "clue", "", false).size)
        assertEquals(saved, filterBookNotes(notes, NoteType.BOOKMARK, "clue", "待回看", false).single())
        assertTrue(filterBookNotes(notes, NoteType.ALL, "", "", false).isEmpty())
        assertTrue(notesMarkdown(listOf(saved)).contains("标签：待回看、伏笔"))
    }
    @Test fun searchIncludesQuoteRemarkChapterBookAuthorAndLabelName() {
        val notes = archive(tags = listOf(HighlightTag("label", "重要伏笔")),
            links = listOf(HighlightTagLink(2, "label"))).single().notes
        listOf("原文", "书签备注", "雨夜归人", "林间", "长夜书店", "重要伏笔").forEach {
            assertTrue(it, filterBookNotes(notes, NoteType.ALL, null, " $it ", false).isNotEmpty())
        }
    }
    @Test fun sortingResolvesCurrentChapterUrlAndNormalizesTitleOffset() {
        val highlights = listOf(highlight(2).copy(chapterUrl = "moved", chapterIndex = 99,
            chapterPos = 15, layoutTitleLength = 10), highlight(3).copy(chapterPos = 6, layoutTitleLength = 0))
        val notes = archive(bookmarks = emptyList(), highlights = highlights).single().notes
        assertEquals(listOf(2L, 3L), filterBookNotes(notes, NoteType.ALL, null, "", false,
            mapOf("moved" to 1)).map { it.time })
    }
    @Test fun removedChapterMovesToEndWithoutLosingTheRecord() {
        val notes = archive(highlights = listOf(highlight().copy(chapterUrl = "removed"))).single().notes
        assertNotNull(filterBookNotes(notes, NoteType.ALL, null, "", false).last().highlight)
    }
    @Test fun recentSortIgnoresChapterPositionAndBreaksTiesDeterministically() {
        val notes = archive(highlights = listOf(highlight(50))).single().notes
        assertEquals(listOf(50L, 1L), filterBookNotes(notes, NoteType.ALL, null, "", true).map { it.time })
    }
    @Test fun labelsRespectBookScopeAndPerBookOrderAndIgnoreStaleLinks() {
        val tags = listOf(HighlightTag("one", "通用", sortOrder = 0),
            HighlightTag("two", "本书", sortOrder = 1, bookUrl = book.bookUrl),
            HighlightTag("foreign", "别本书", bookUrl = "other"))
        val result = archive(tags = tags,
            links = listOf("one", "two", "foreign", "deleted").map { HighlightTagLink(2, it) },
            orders = listOf(HighlightTagOrder(book.bookUrl, "two", 0),
                HighlightTagOrder(book.bookUrl, "one", 1))).single().notes.single { it.highlight != null }
        assertEquals(listOf("two", "one"), result.tags.map { it.id })
    }
    @Test fun markdownKeepsFullMultilineTextNotesLabelsAndBooksSharingAuthorOrName() {
        val first = BookNote(bookmark = bookmark().copy(bookText = "第一行\n第二行"))
        val second = BookNote(highlight = highlight().copy(bookName = "山海来信"),
            tags = listOf(HighlightTag(name = "伏笔")))
        val third = BookNote(bookmark = bookmark(3).copy(bookAuthor = "另一作者"))
        val markdown = notesMarkdown(listOf(first, second, third))
        listOf("## 长夜书店 · 林间", "## 山海来信 · 林间", "## 长夜书店 · 另一作者",
            "> 第一行\n> 第二行", "书签备注", "个人笔记", "标签：伏笔").forEach {
            assertTrue(it, markdown.contains(it))
        }
    }
}
