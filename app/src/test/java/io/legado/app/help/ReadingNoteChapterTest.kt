package io.legado.app.help

import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReadingNoteLocation
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ReadingNoteChapterTest {
    private fun vip(query: String) =
        "data:vipContent;base64," + Base64.getEncoder().encodeToString(query.toByteArray()) + ",{\"js\":\"changed\"}"

    @Test fun `local chapter table is rebuilt when it is empty or stale`() {
        assertTrue(ReadingNoteChapter.shouldRefreshLocalChapters(true, emptyList(), false))
        assertTrue(ReadingNoteChapter.shouldRefreshLocalChapters(
            true, listOf(BookChapter(title = "第五章")), true
        ))
        assertFalse(ReadingNoteChapter.shouldRefreshLocalChapters(
            false, emptyList(), true
        ))
        assertFalse(ReadingNoteChapter.shouldRefreshLocalChapters(
            true, listOf(BookChapter(title = "第五章")), false
        ))
    }

    @Test fun `old and new requests identify the same platform chapter`() {
        val old = vip("token=expired-fixture&novelId=42&chapterId=28")
        val current = vip("novelId=42&chapterId=28")
        assertTrue(ReadingNoteChapter.sameUrl(old, current))
        assertEquals(9, ReadingNoteChapter.exactIndex(old, mapOf(current to 9)))
        assertFalse(ReadingNoteChapter.sameUrl(old, vip("novelId=43&chapterId=28")))
        assertFalse(ReadingNoteChapter.sameUrl(old, vip("novelId=42&chapterId=29")))
    }

    @Test fun `unrecognized sources never lose identifying request parameters`() {
        assertNull(ReadingNoteChapter.stableIdentity("https://example.test/chapter?token=one&id=2"))
        assertFalse(ReadingNoteChapter.sameUrl("https://example.test/chapter?token=one",
            "https://example.test/chapter?token=two"))
        assertNull(ReadingNoteChapter.stableIdentity("https://jjwxc.net.example.test/androidapi/chapterContent?novelId=42&chapterId=28"))
        assertNull(ReadingNoteChapter.stableIdentity(vip("novelId=42&chapterId=28&chapterId=29")))
        assertNull(ReadingNoteChapter.stableIdentity("data:vipContent;base64,broken"))
    }

    @Test fun `any source can use chapter metadata as candidates when URLs change`() {
        val original = ReadingNoteLocation(chapterUrl = "https://old.test/28?token=old",
            chapterName = "第二十八章 原来的标题", chapterIndex = 27)
        val changed = BookChapter(url = "https://new.test/read/abc", title = "第28章 修改后的标题", index = 32)
        assertEquals(changed, ReadingNoteChapter.candidate(original, listOf(changed)))
    }

    @Test fun `a stable identity survives simultaneous chapter rename and reorder`() {
        val original = ReadingNoteLocation(chapterUrl = vip("token=old&novelId=42&chapterId=28"),
            chapterName = "原来的章名", chapterIndex = 27)
        val changed = BookChapter(url = vip("novelId=42&chapterId=28"), title = "完全不同的新章名", index = 32)
        assertEquals(changed, ReadingNoteChapter.candidate(original, listOf(changed)))
    }

    @Test fun `duplicate titles are not resolved by choosing the nearest index`() {
        val source = ReadingNoteLocation(chapterName = "番外", chapterIndex = 2)
        assertNull(ReadingNoteChapter.candidate(source, listOf(
            BookChapter(url = "one", title = "番外", index = 1),
            BookChapter(url = "two", title = "番外", index = 2))))
    }

    @Test fun `a unique chapter number can disambiguate repeated titles`() {
        val source = ReadingNoteLocation(chapterName = "第一章 番外", chapterIndex = 2)
        val expected = BookChapter(url = "one", title = "第1章 番外", index = 8)
        assertEquals(expected, ReadingNoteChapter.candidate(source, listOf(
            expected, BookChapter(url = "two", title = "第2章 番外", index = 2))))
    }

    @Test fun `chapter index keeps stable platform identity`() {
        val original = ReadingNoteLocation(
            chapterUrl = vip("token=old&novelId=42&chapterId=28"),
            chapterName = "旧章名", chapterIndex = 27,
        )
        val changed = BookChapter(
            url = vip("novelId=42&chapterId=28"), title = "新章名", index = 32,
        )
        val index = ReadingNoteChapter.index(listOf(changed))
        assertEquals(changed, index.candidate(original))
        assertEquals(ReadingNoteChapter.CandidateState.FOUND, index.candidateState(original))
    }

    @Test fun `chapter index resolves the same candidates as the list lookup`() {
        val chapters = listOf(
            BookChapter(url = "u1", title = "第一章 开端", index = 0),
            BookChapter(url = "u2", title = "第二章 发展", index = 1),
            BookChapter(url = "u3", title = "番外", index = 2),
            BookChapter(url = "u4", title = "番外", index = 3),
            BookChapter(url = "u5", title = "第5章 结局", index = 4),
            BookChapter(url = "u6", title = "第五章 另一个结局", index = 5),
            BookChapter(url = "volume", title = "第一卷", index = 6, isVolume = true),
        )
        val index = ReadingNoteChapter.index(chapters)
        val locations = listOf(
            ReadingNoteLocation(chapterUrl = "u2"),
            ReadingNoteLocation(chapterUrl = "u3"),
            ReadingNoteLocation(chapterUrl = "missing"),
            ReadingNoteLocation(chapterName = "番外"),
            ReadingNoteLocation(chapterName = "第2章 任意"),
            ReadingNoteLocation(chapterName = "第五章"),
            ReadingNoteLocation(chapterName = "第一卷"),
            ReadingNoteLocation(chapterName = "不存在", chapterIndex = 3),
            ReadingNoteLocation(chapterName = "不存在", chapterIndex = 99),
            ReadingNoteLocation(),
        )
        locations.forEach { location ->
            assertEquals(
                ReadingNoteChapter.candidate(location, chapters),
                index.candidate(location),
            )
            assertEquals(
                ReadingNoteChapter.candidateState(location, chapters),
                index.candidateState(location),
            )
        }
    }

    @Test fun `chapter index keeps volumes and repeated urls out of candidates`() {
        val repeated = BookChapter(url = "same", title = "重复地址", index = 0)
        val chapters = listOf(
            repeated,
            BookChapter(url = "same", title = "重复地址续", index = 1),
            BookChapter(url = "volume", title = "第二卷", index = 2, isVolume = true),
        )
        val index = ReadingNoteChapter.index(chapters)
        val location = ReadingNoteLocation(chapterUrl = "same")
        assertEquals(ReadingNoteChapter.CandidateState.AMBIGUOUS, index.candidateState(location))
        assertNull(index.candidate(location))
        assertEquals(
            ReadingNoteChapter.CandidateState.NOT_FOUND,
            index.candidateState(ReadingNoteLocation(chapterName = "第二卷", chapterIndex = 2)),
        )
    }
}
