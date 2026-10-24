package io.legado.app.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookTagsTest {

    @Test
    fun `book tags normalize delimiters whitespace and duplicates`() {
        assertEquals(
            "已读,收藏,历史",
            normalizeBookTags(" 已读，收藏\n历史,已读、收藏 "),
        )
    }

    @Test
    fun `book tags deduplicate case insensitive names`() {
        assertEquals("SciFi,收藏", normalizeBookTags("SciFi,scifi,收藏"))
    }

    @Test
    fun `empty book tags become null`() {
        assertNull(normalizeBookTags(" \n，、 "))
    }

    @Test
    fun `book display tags keep source fields before user tags`() {
        val book = Book(
            kind = "都市,仙侠",
            wordCount = "30万字",
            customTag = "已读,收藏",
        )

        assertEquals(
            listOf("30万字", "都市", "仙侠", "已读", "收藏"),
            book.displayTags(),
        )
    }

    @Test
    fun `source tags exclude word count and normalize source delimiters`() {
        val book = Book(
            kind = "都市，仙侠、悬疑\n历史",
            wordCount = "30万字",
        )

        assertEquals(listOf("都市", "仙侠", "悬疑", "历史"), book.sourceTags())
    }

    @Test
    fun `display tags deduplicate source and user labels without case`() {
        val book = Book(
            kind = "都市,SCI-FI",
            customTag = "都市,sci-fi,已读",
        )

        assertEquals(listOf("都市", "SCI-FI", "已读"), book.displayTags())
    }

    @Test
    fun `tag statistics count each book once and keep source and user lists separate`() {
        val books = listOf(
            ShelfBookTags("book-1", "都市,都市", "已读,已读"),
            ShelfBookTags("book-1", "科幻", "收藏"),
            ShelfBookTags("book-2", "都市", "已读"),
        )

        assertEquals(
            listOf(BookTagStat("都市", 2), BookTagStat("科幻", 1)),
            aggregateBookTagStats(books, sourceTags = true),
        )
        assertEquals(
            listOf(BookTagStat("已读", 2), BookTagStat("收藏", 1)),
            aggregateBookTagStats(books, sourceTags = false),
        )
    }

    @Test
    fun `shelf tag matching trims tokens and searches both tag fields exactly`() {
        val book = ShelfBookTags("book-1", "都市, 科幻", "已读， 收藏")

        assertTrue(book.hasTag("科幻"))
        assertTrue(book.hasTag(" 收藏 "))
        assertFalse(book.hasTag("科"))
        assertFalse(book.hasTag(""))
    }

    @Test
    fun `rename and remove user tags normalize duplicates`() {
        assertEquals("已读,收藏", replaceBookTag("已读,待读,收藏", "待读", "已读"))
        assertEquals("已读", removeBookTag("已读,收藏,收藏", "收藏"))
    }
}
