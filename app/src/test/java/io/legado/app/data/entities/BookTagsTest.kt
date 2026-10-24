package io.legado.app.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
