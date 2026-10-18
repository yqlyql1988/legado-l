package io.legado.app.data.entities

import io.legado.app.utils.GSON
import org.junit.Assert.*
import org.junit.Test

class BookReadingMarkTest {
    private val chapters = (0..9).map {
        BookChapter(bookUrl = "source-a", url = "chapter-$it", title = "第${it + 1}章", index = it)
    }
    private val mark = BookReadingMark("Book", "Author")

    @Test fun manualStatusDefaultsToUnmarkedAndDoesNotInventHistory() {
        assertEquals(BookReadingMark.UNMARKED, mark.validated().status)
        assertTrue(mark.date.isEmpty())
        assertNull(mark.position)
        assertTrue(mark.skipped.isEmpty())
    }

    @Test fun overlappingRangesDoNotDoubleCountAndPartialRereadOnlyClearsSelectedChapters() {
        val first = mark.withSkipped(chapters, chapters.subList(1, 5))
        val overlapping = first.withSkipped(chapters, chapters.subList(3, 7))
        assertEquals((1..6).toSet(), overlapping.skipped.map { it.index }.toSet())
        assertEquals(6, overlapping.skipped.size)
        val reread = overlapping.withSkipped(chapters, chapters.subList(2, 4), remove = true)
        assertEquals(setOf(1, 4, 5, 6), reread.skipped.map { it.index }.toSet())
    }

    @Test fun completionKeepsAllSkippedChaptersAndCapturedAbandonPositionIsImmutable() {
        val abandoned = mark.withSkipped(chapters, chapters.subList(1, 4)).copy(
            status = BookReadingMark.ABANDONED, date = "2026-10-18",
            position = chapters[7].readingMarkChapter(128), reason = "剧情重复")
        val finished = abandoned.copy(status = BookReadingMark.FINISHED)
        assertEquals(abandoned.skipped, finished.validated().skipped)
        assertEquals(128, abandoned.position?.pos)
        assertEquals(7, abandoned.position?.index)
    }

    @Test fun sourceSwitchUsesUniqueTitleAndNeverUsesAnUnverifiedIndex() {
        val original = chapters[4].readingMarkChapter(44)
        val changed = chapters.map { it.copy(bookUrl = "source-b", url = "new-${it.url}", index = it.index + 2) }
        assertEquals(6, ReadingMarkResolver(changed).resolve(original)?.index)
        assertNull(ReadingMarkResolver(changed.map { it.copy(title = "Different ${it.index}") }).resolve(original))
        assertNull(ReadingMarkResolver(changed + changed[4].copy(url = "duplicate", index = 99)).resolve(original))
    }

    @Test fun volumeHeadingsAreExcludedAndUnresolvedOriginalsSurviveEditing() {
        val volume = chapters[1].copy(isVolume = true)
        val unknown = ReadingMarkChapter("removed-source", "old", "Old chapter", 99)
        val saved = mark.copy(skipped = listOf(unknown)).withSkipped(chapters + volume, listOf(volume, chapters[3]))
        assertEquals(listOf(unknown, chapters[3].readingMarkChapter()), saved.skipped)
        assertNull(ReadingMarkResolver(listOf(volume)).resolve(volume.readingMarkChapter()))
    }

    @Test fun jsonRoundTripPreservesExactOffsetAndSkippedSnapshots() {
        val saved = mark.withSkipped(chapters, chapters.subList(2, 5)).copy(
            status = BookReadingMark.ABANDONED, date = "2026-10-18",
            position = chapters[8].readingMarkChapter(345), reason = "以后再看", updatedAt = 100)
        assertEquals(saved, GSON.fromJson(GSON.toJson(saved), BookReadingMark::class.java).validated())
        val converters = ReadingMarkConverters()
        assertEquals(saved.position, converters.positionFromJson(converters.positionToJson(saved.position)))
        assertEquals(saved.skipped, converters.skippedFromJson(converters.skippedToJson(saved.skipped)))
    }

    @Test fun invalidStatusesDatesAndOffsetsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { mark.copy(status = "automatic").validated() }
        assertThrows(IllegalArgumentException::class.java) { mark.copy(status = BookReadingMark.FINISHED).validated() }
        assertThrows(Exception::class.java) { mark.copy(date = "2026-02-30").validated() }
        assertThrows(IllegalArgumentException::class.java) {
            mark.copy(position = chapters[0].readingMarkChapter(-1)).validated()
        }
    }
}
