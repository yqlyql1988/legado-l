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

    @Test fun `legacy chapter opening bookmark drops only its saved title line`() {
        val title = "第075章 测试章节"
        val body = "　　这是章首的唯一原文。\n　　下一段也要保留。"
        val chapter = BookChapter(bookUrl = "book", url = "chapter", title = "第075章 新标题")
        val bookmark = io.legado.app.data.entities.Bookmark(
            chapterName = title, bookText = "$title\n$body", content = "保留备注")
        val legacy = ReadingNotes.legacy(bookmark, "book")
        assertNull(ReadingNoteAnchor.find(body, legacy.quote, 0))
        val resolved = requireNotNull(ReadingNotes.captured(legacy, chapter, body, 18))
        assertEquals(body, resolved.quote)
        assertEquals(18, resolved.chapterPos)
        assertEquals(18 + body.length, resolved.chapterPosEnd)
        assertEquals("$title\n$body", bookmark.bookText)
        assertEquals("保留备注", bookmark.content)
        assertEquals(body, ReadingNotes.anchorQuote(legacy))
    }

    @Test fun `title compatibility still verifies the body and rejects repeated passages`() {
        val location = ReadingNoteLocation(kind = "bookmark", bookUrl = "book",
            chapterName = "第1章 标题", quote = "第1章 标题\n重复正文", verified = false)
        val chapter = BookChapter(bookUrl = "book", url = "chapter")
        assertNull(ReadingNotes.captured(location, chapter, "重复正文\n重复正文", 0))
        assertNull(ReadingNotes.captured(location, chapter, "原文已被删除", 0))
        assertNull(ReadingNotes.captured(location.copy(quote = "无关标题\n重复正文"),
            chapter, "重复正文", 0))
    }

    @Test fun `body bookmarks and highlights retain title-like original text`() {
        val quote = "第1章 标题\n这是正文中的章节引用。"
        val location = ReadingNoteLocation(kind = "highlight", chapterName = "第1章 标题", quote = quote)
        assertEquals(quote, ReadingNotes.anchorQuote(location))
        assertEquals(quote, ReadingNotes.anchorQuote(location.copy(kind = "bookmark",
            chapterPos = 20, layoutTitleLength = 15)))
        assertEquals("普通正文", ReadingNotes.anchorQuote(location.copy(kind = "bookmark", quote = "普通正文")))
        assertEquals("第1章 标题", ReadingNotes.anchorQuote(location.copy(kind = "bookmark", quote = "第1章 标题")))
    }

    @Test fun `legacy split chapter numbers and CRLF titles leave the complete body intact`() {
        val body = "　　唯一的正文。\n　　第二段原文。"
        val location = ReadingNoteLocation(kind = "bookmark", bookUrl = "book",
            chapterName = "第075章 测试章节", quote = "第075章\r\n测试章节\r\n$body")
        val chapter = BookChapter(bookUrl = "book", url = "chapter")
        assertEquals(body, ReadingNotes.anchorQuote(location))
        assertNotNull(ReadingNotes.captured(location, chapter, body, 15))
    }
}
