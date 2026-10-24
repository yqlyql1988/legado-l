package io.legado.app.data

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Test

class BookMetadataTagsTest {

    @Test
    fun `edited custom tags are applied while other book data remains intact`() {
        val current = Book(
            bookUrl = "book",
            customTag = "已有",
            kind = "书源分类",
            durChapterIndex = 12,
        )
        val edited = current.copy(customTag = "常读,收藏")

        current.applyEditedMetadata(edited)

        assertEquals("常读,收藏", current.customTag)
        assertEquals("书源分类", current.kind)
        assertEquals(12, current.durChapterIndex)
    }
}
