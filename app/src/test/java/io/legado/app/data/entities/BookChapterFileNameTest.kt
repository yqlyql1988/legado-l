package io.legado.app.data.entities

import io.legado.app.utils.MD5Utils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BookChapterFileNameTest {

    @Test
    fun fileNameUsesIndexAndTitleDigest() {
        val chapter = BookChapter(title = "第一章 开始", index = 12)
        assertEquals("00012-${MD5Utils.md5Encode16(chapter.title)}.nb", chapter.getFileName())
    }

    @Test
    fun changedTitleOrIndexProducesDifferentFileName() {
        val chapter = BookChapter(title = "第一章", index = 1)
        assertNotEquals(chapter.getFileName(), chapter.copy(title = "第一章 新标题").getFileName())
        assertNotEquals(chapter.getFileName(), chapter.copy(index = 2).getFileName())
        assertEquals(chapter.getFileName(), chapter.copy().getFileName())
    }

    @Test
    fun companionFileNameMatchesChapterFileName() {
        val chapter = BookChapter(title = "Chapter", index = 3)
        assertEquals(
            chapter.getFileName(),
            BookChapter.fileName(chapter.index, MD5Utils.md5Encode16(chapter.title)),
        )
    }
}
