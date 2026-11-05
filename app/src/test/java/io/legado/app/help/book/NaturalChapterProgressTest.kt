package io.legado.app.help.book

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NaturalChapterProgressTest {

    @Test
    fun `completed ranges merge and split around a chapter`() {
        val ranges = NaturalProgressRanges.parse("0-2,5-5")
        NaturalProgressRanges.add(ranges, 3)
        NaturalProgressRanges.add(ranges, 4)
        assertEquals("0-5", NaturalProgressRanges.encode(ranges))

        NaturalProgressRanges.remove(ranges, 3)
        assertEquals("0-2,4-5", NaturalProgressRanges.encode(ranges))
        assertEquals(true, NaturalProgressRanges.contains(ranges, 5))
        assertEquals(false, NaturalProgressRanges.contains(ranges, 3))
    }

    @Test
    fun `reading chapters keep their own progress and position`() {
        val chapters = NaturalProgressChapters.parse("5:630:100,9:250:50")
        assertEquals(630, chapters[5]?.perMille)
        assertEquals(100, chapters[5]?.position)
        assertEquals(250, chapters[9]?.perMille)
        assertEquals("5:630:100,9:250:50", NaturalProgressChapters.encode(chapters))
        assertEquals(0, NaturalProgressChapters.parse("bad,1:0:5,2:1000:5").size)
    }

    @Test
    fun `chapter list shows every chapter's own natural progress`() {
        val book = Book(
            readConfig = Book.ReadConfig(
                naturalProgressCompletedRanges = "0-2",
                naturalProgressPartialChapters = "5:630:100,9:250:50",
                naturalProgressInitialized = true,
            )
        )

        assertEquals(1f, book.naturalChapterProgress(2), 0f)
        assertEquals(0.63f, book.naturalChapterProgress(5), 0f)
        assertEquals(0.25f, book.naturalChapterProgress(9), 0f)
        assertEquals(0f, book.naturalChapterProgress(3), 0f)
        assertEquals(100, book.naturalChapterProgressPosition(5))
        assertEquals(50, book.naturalChapterProgressPosition(9))
        assertNull(book.naturalChapterProgressPosition(2))
    }

    @Test
    fun `old book config without natural progress stays empty`() {
        val book = Book()
        assertEquals(0f, book.naturalChapterProgress(3), 0f)
        assertNull(book.naturalChapterProgressPosition(3))
        assertNull(book.naturalProgressSnapshot())
    }
}
