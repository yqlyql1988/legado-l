package io.legado.app.ui.book.toc

import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReadingNoteLocation
import io.legado.app.help.ReadingNoteChapter
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteChapterOrderTest {

    private val index = ReadingNoteChapter.index(
        listOf(
            BookChapter(url = "u1", title = "第1章 开始", index = 0),
            BookChapter(url = "u2", title = "第2章 新标题", index = 1),
            BookChapter(url = "u3", title = "第3章 结束", index = 2),
        )
    )

    @Test
    fun `url wins over a stale stored index`() {
        val location = ReadingNoteLocation(
            chapterUrl = "u3",
            chapterIndex = 99,
            chapterName = "第1章 开始",
        )

        assertEquals(2, resolvedChapterIndex(index, location, 99))
    }

    @Test
    fun `renamed chapter is resolved by chapter number`() {
        val location = ReadingNoteLocation(
            chapterIndex = 99,
            chapterName = "第2章 旧标题",
        )

        assertEquals(1, resolvedChapterIndex(index, location, 99))
    }

    @Test
    fun `missing chapter keeps the stored index instead of the end`() {
        val location = ReadingNoteLocation(
            chapterIndex = 42,
            chapterName = "第99章 已删除",
        )

        assertEquals(42, resolvedChapterIndex(index, location, 42))
    }

    @Test
    fun `missing index falls back to the stored index`() {
        assertEquals(7, resolvedChapterIndex(null, ReadingNoteLocation(), 7))
    }
}
