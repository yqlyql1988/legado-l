package io.legado.app.help

import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReadingNoteLocation
import org.junit.Assert.*
import org.junit.Test

class ReadingNoteAnchorTest {
    @Test fun `insertions move the quote without requiring the original position`() {
        assertEquals(ReadingNoteAnchor.Match(12, 23),
            ReadingNoteAnchor.find("new preface\na unique 原文", "a unique 原文", 0, trustPosition = false))
    }

    @Test fun `source changes never select an arbitrary repeated phrase`() {
        assertNull(ReadingNoteAnchor.find("same xx same", "same", 0, trustPosition = false))
        assertEquals(ReadingNoteAnchor.Match(8, 12),
            ReadingNoteAnchor.find("same xx same", "same", 0, prefix = "xx ", trustPosition = false))
        assertEquals(ReadingNoteAnchor.Match(0, 4),
            ReadingNoteAnchor.find("same xx same", "same", 0))
    }

    @Test fun `whitespace changes map back to original UTF16 positions`() {
        val text = "🌙\n　　这是 一段\n 唯一的标注 原文。"
        val match = requireNotNull(ReadingNoteAnchor.find(text, "这是一段唯一的标注原文。", 0))
        assertEquals(text.indexOf('这'), match.start)
        assertEquals(text.length, match.end)
        assertEquals("这是 一段\n 唯一的标注 原文。", text.substring(match.start, match.end))
    }

    @Test fun `deleted or substantially rewritten quotes stay unresolved`() {
        assertNull(ReadingNoteAnchor.find("作者已改写这里", "旧版的标注原文", 1))
        assertNull(ReadingNoteAnchor.find("哈哈 哈哈", "哈哈", 100, trustPosition = false))
        assertNull(ReadingNoteAnchor.find("no text", "", 0))
    }

    @Test fun `format normalization does not remove meaningful punctuation`() {
        assertNull(ReadingNoteAnchor.find("abcdefghi!", "abcdefghi?", 0))
    }

    @Test fun `the same quote in another book cannot move an existing note`() {
        val location = ReadingNoteLocation(bookUrl = "book-a", quote = "shared quote")
        val chapter = BookChapter(bookUrl = "book-b", url = "chapter")
        assertNull(ReadingNotes.captured(location, chapter, "shared quote", 0))
        assertNotNull(ReadingNotes.captured(location, chapter.copy(bookUrl = "book-a"), "shared quote", 0))
    }
}
