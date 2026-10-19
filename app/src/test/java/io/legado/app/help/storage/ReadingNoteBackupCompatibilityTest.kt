package io.legado.app.help.storage

import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadingNoteLocation
import io.legado.app.help.ReadingNotes
import io.legado.app.utils.GSON
import org.junit.Assert.*
import org.junit.Test

class ReadingNoteBackupCompatibilityTest {
    @Test fun `legacy records do not gain mandatory fields or change file format`() {
        val bookmarkJson = """{"time":1,"bookName":"书","bookAuthor":"作者","chapterIndex":2,"chapterPos":33,"chapterName":"旧章名","bookText":"原文","content":"备注"}"""
        val highlightJson = """{"time":2,"bookName":"书","bookAuthor":"作者","bookUrl":"old-book","chapterUrl":"old-chapter","chapterIndex":2,"chapterPos":33,"chapterPosEnd":35,"chapterName":"旧章名","bookText":"原文","note":"笔记","style":""}"""
        val bookmark = GSON.fromJson(bookmarkJson, Bookmark::class.java)
        val highlight = GSON.fromJson(highlightJson, BookHighlight::class.java)
        assertEquals(1L, bookmark.time)
        assertEquals("笔记", highlight.note)
        assertEquals("old-chapter", ReadingNotes.legacy(highlight).chapterUrl)
        assertEquals("原文", ReadingNotes.legacy(bookmark, "book").quote)
        assertFalse(GSON.toJson(bookmark).contains("prefix"))
        assertFalse(GSON.toJson(highlight).contains("workId"))
    }

    @Test fun `annotation selection carries optional location files without selecting bookshelf or history`() {
        val names = selectedBackupFileNames { it == BackupConfig.annotationContentKey }
        assertTrue(ReadingNotes.LOCATION_FILE in names)
        assertTrue(ReadingNotes.SOURCE_FILE in names)
        assertTrue("bookmark.json" in names && "highlight.json" in names)
        assertFalse("bookshelf.json" in names || "readRecord.json" in names)
    }

    @Test fun `the optional location extension round trips context and the original source`() {
        val original = ReadingNoteLocation(noteTime = 2, bookUrl = "old-book", chapterUrl = "old-chapter",
            chapterIndex = 7, quote = "原文", prefix = "前文", suffix = "后文", verified = true)
        assertEquals(original, GSON.fromJson(GSON.toJson(original), ReadingNoteLocation::class.java))
    }
}
