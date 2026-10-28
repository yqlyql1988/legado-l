package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TextReadProgressTrackerTest {

    private fun chapter(index: Int, content: String, titleLength: Int = 0) =
        TextReadChapterSnapshot(index, content, titleLength)

    @Test
    fun forwardMovementCountsBodyCharactersAndSkipsWhitespace() {
        val tracker = TextReadProgressTracker()
        val current = chapter(0, "title\nA B\nC", titleLength = 6)

        tracker.start(0, 6, current)

        assertEquals(3L, tracker.update(0, current.content.length, current))
    }

    @Test
    fun backwardMovementStartsANewBaseline() {
        val tracker = TextReadProgressTracker()
        val current = chapter(0, "ABC", titleLength = 0)

        tracker.start(0, 0, current)
        assertEquals(2L, tracker.update(0, 2, current))
        assertEquals(0L, tracker.update(0, 1, current))
        assertEquals(2L, tracker.update(0, 3, current))
    }

    @Test
    fun seekDoesNotCountTheJumpButCountsLaterReading() {
        val tracker = TextReadProgressTracker()
        val current = chapter(0, "ABCDE")

        tracker.start(0, 0, current)
        tracker.markSeek()

        assertEquals(0L, tracker.update(0, 4, current))
        assertEquals(1L, tracker.update(0, 5, current))
    }

    @Test
    fun quickForwardThenBackwardDiscardsUnconfirmedWords() {
        val tracker = TextReadProgressTracker()
        val current = chapter(0, "ABCDEFGHIJ")

        tracker.start(0, 0, current)

        assertEquals(0L, tracker.update(0, 6, current, elapsed = 200))
        assertEquals(0L, tracker.update(0, 3, current, elapsed = 200))
        assertEquals(0L, tracker.update(0, 4, current, elapsed = 200))
        assertEquals(2L, tracker.update(
            0, 5, current, elapsed = TextReadProgressTracker.wordConfirmationMillis,
        ))
    }

    @Test
    fun stoppingAtTheForwardPositionConfirmsPendingWords() {
        val tracker = TextReadProgressTracker()
        val current = chapter(0, "ABCDEFGHIJ")

        tracker.start(0, 0, current)

        assertEquals(0L, tracker.update(0, 3, current, elapsed = 200))
        assertEquals(
            3L,
            tracker.update(0, 3, current, elapsed = TextReadProgressTracker.wordConfirmationMillis),
        )
    }

    @Test
    fun normalNextChapterCountsRemainingAndNewBody() {
        val tracker = TextReadProgressTracker()
        val first = chapter(0, "title\nAB", titleLength = 6)
        val second = chapter(1, "next\nC", titleLength = 5)

        tracker.start(0, 6, first)

        assertEquals(3L, tracker.update(1, 6, second))
    }
}
