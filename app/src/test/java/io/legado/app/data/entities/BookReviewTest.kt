package io.legado.app.data.entities

import org.junit.Assert.*
import org.junit.Test

class BookReviewTest {
    private fun review(id: String = "initial", date: String = "2026-10-15",
        rating: Int? = 9, followUp: Boolean = false, created: Long = 10) =
        BookReview(id = id, bookName = "Book", bookAuthor = "Author", rating = rating,
            content = "My review", reviewDate = date, isFollowUp = followUp,
            createdAt = created, updatedAt = created)

    @Test fun initialReviewStoresHalfStarsAndCalendarDate() {
        val initial = review().validated()
        assertEquals(4.5, initial.rating!! / 2.0, 0.0)
        assertEquals("2026-10-15", initial.reviewDate)
    }

    @Test fun promotedInitialMayHaveNoScoreButAlwaysRequiresContent() {
        assertNull(review(rating = null).validated().rating)
        assertThrows(IllegalArgumentException::class.java) { review().copy(content = " \n").validated() }
    }

    @Test fun followUpCanKeepThePreviousScore() {
        assertNull(review(rating = null, followUp = true).validated().rating)
    }

    @Test fun invalidScoresAndDatesAreRejected() {
        listOf(-1, 0, 11).forEach { rating ->
            assertThrows(IllegalArgumentException::class.java) { review(rating = rating).validated() }
        }
        listOf("2026-02-30", "2026-1-1", "").forEach { date ->
            assertThrows(Exception::class.java) { review(date = date).validated() }
        }
    }

    @Test fun differentAuthorsHaveSeparateArchives() {
        val archives = archiveBookReviews(listOf(review(), review("other").copy(bookAuthor = "Other")))
        assertEquals(2, archives.size)
    }

    @Test fun changingSourcesKeepsOneArchive() {
        val archives = archiveBookReviews(listOf(review().copy(bookUrl = "old"),
            review("follow", followUp = true).copy(bookUrl = "new")))
        assertEquals(1, archives.size)
        assertEquals(2, archives.single().reviews.size)
    }

    @Test fun ratingUsesLatestExplicitScoreRatherThanAverage() {
        val archive = archiveBookReviews(listOf(review(rating = 10),
            review("second", date = "2026-10-16", rating = 6, followUp = true),
            review("third", date = "2026-10-17", rating = null, followUp = true))).single()
        assertEquals("third", archive.latest.id)
        assertEquals(6, archive.rating)
    }

    @Test fun historicalReviewsSortByChosenDateThenCreationNotEditTime() {
        val earlier = review("earlier", date = "2026-10-14").copy(updatedAt = 1000)
        val first = review("first", created = 100)
        val second = review("second", created = 200)
        assertEquals(listOf("second", "first", "earlier"),
            archiveBookReviews(listOf(earlier, second, first)).single().reviews.map { it.id })
    }

    @Test fun archiveOrderTracksTheMostRecentReview() {
        val recent = review("recent", date = "2026-10-16").copy(bookName = "Second book")
        assertEquals("Second book", archiveBookReviews(listOf(review(), recent)).first().latest.bookName)
    }

    @Test fun repeatedRestoreDoesNotDuplicateReviews() {
        val backup = listOf(review(), review("follow", followUp = true))
        assertEquals(backup, mergeBookReviews(mergeBookReviews(emptyList(), backup), backup))
    }

    @Test fun olderBackupCannotOverwriteNewerEditOrRenamedIdentity() {
        val old = review()
        val edited = old.copy(content = "Edited", bookName = "Renamed", updatedAt = 30)
        assertEquals(listOf(edited), mergeBookReviews(listOf(edited), listOf(old)))
        assertEquals(listOf(edited), mergeBookReviews(listOf(old), listOf(edited)))
    }

    @Test fun invalidRestoreIsRejectedBeforeMergingAnyEntries() {
        val current = listOf(review())
        assertThrows(IllegalArgumentException::class.java) {
            mergeBookReviews(current, listOf(review("new"), review("bad", rating = 12)))
        }
        assertEquals("initial", current.single().id)
    }

    @Test fun promotionChoosesEarliestDateThenCreationThenIdWithoutInventingARating() {
        val follow = review("follow", date = "2026-10-16", followUp = true)
        val later = review("later", date = "2026-10-17", followUp = true)
        val first = review("first", date = "2026-10-16", rating = null, followUp = true, created = 1)
        val sameTime = first.copy(id = "second")
        val promoted = normalizeBookReviewRoles(listOf(later, follow, sameTime, first), now = 50)
        assertEquals(listOf("first"), promoted.filterNot { it.isFollowUp }.map { it.id })
        assertNull(promoted.first { it.id == "first" }.rating)
        assertEquals(50L, promoted.first { it.id == "first" }.updatedAt)
        assertEquals(later, promoted.first { it.id == "later" })
        assertEquals(promoted, normalizeBookReviewRoles(promoted, now = 60))
    }

    @Test fun backdatedFollowUpDoesNotReplaceSurvivingInitial() {
        val initial = review()
        val follow = review("backdated", date = "2026-10-14", followUp = true)
        assertEquals(listOf(initial, follow), normalizeBookReviewRoles(listOf(initial, follow), 50))
    }

    @Test fun deletionAlwaysWinsOverOldOrNewerBackupAndKeepsOtherBooks() {
        val removed = review().copy(updatedAt = 1000)
        val other = review("other").copy(bookAuthor = "Other author")
        assertEquals(listOf(other), mergeBookReviews(listOf(removed, other),
            listOf(removed.copy(updatedAt = 2000)), setOf(removed.id)))
        assertTrue(archiveBookReviews(mergeBookReviews(listOf(removed),
            listOf(removed), setOf(removed.id))).isEmpty())
    }
}
