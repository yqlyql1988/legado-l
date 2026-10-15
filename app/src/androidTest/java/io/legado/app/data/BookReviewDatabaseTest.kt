package io.legado.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookReview
import io.legado.app.data.entities.BookReviewDeletion
import io.legado.app.data.entities.archiveBookReviews
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookReviewDatabaseTest {
    private lateinit var db: AppDatabase
    private val initial = BookReview(id = "initial", bookName = "Book", bookAuthor = "Author",
        bookUrl = "old-source", rating = 9, content = "First impression", reviewDate = "2026-10-15")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun saveEditAndFollowUpKeepIndependentDatesAndScores() {
        val first = db.bookReviewDao.save(initial, now = 100)
        val follow = db.bookReviewDao.save(initial.copy(id = "follow", rating = null,
            reviewDate = "2026-10-16", content = "Second impression"), now = 200)
        assertFalse(first.isFollowUp)
        assertTrue(follow.isFollowUp)
        assertEquals(9, follow.rating)
        val edited = db.bookReviewDao.save(first.copy(content = "Edited", rating = 8), now = 300)
        assertEquals(100L, edited.createdAt)
        assertEquals(300L, edited.updatedAt)
        assertEquals(follow, db.bookReviewDao.forBook("Book", "Author").first())
        assertEquals("2026-10-15", edited.reviewDate)
        assertEquals(8, edited.rating)
    }

    @Test fun staleEditsDoNotOverwriteCurrentText() {
        val first = db.bookReviewDao.save(initial, now = 100)
        db.bookReviewDao.save(first.copy(content = "New text"), now = 200)
        assertThrows(IllegalStateException::class.java) {
            db.bookReviewDao.save(first.copy(content = "Stale text"), now = 300)
        }
        assertEquals("New text", db.bookReviewDao.get(first.id)?.content)
    }

    @Test fun removingAndChangingSourceDoNotRemoveReviews() {
        val book = Book(bookUrl = "old-source", name = "Book", author = "Author")
        db.bookDao.insert(book)
        db.bookReviewDao.save(initial)
        db.bookDao.delete(book)
        assertEquals(1, db.bookReviewDao.forBook("Book", "Author").size)
        db.bookDao.insert(book.copy(bookUrl = "new-source"))
        assertEquals(1, db.bookReviewDao.forBook("Book", "Author").size)
        assertTrue(db.bookReviewDao.forBook("Book", "Other author").isEmpty())
    }

    @Test fun renamingBookMovesAllReviewsEvenWhenRecordSyncIsDisabled() {
        val book = Book(bookUrl = "old-source", name = "Book", author = "Author")
        db.bookDao.insert(book)
        val first = db.bookReviewDao.save(initial, now = 100)
        db.saveEditedBook(book.copy(name = "Renamed", author = "New author"), syncMetadata = false)
        assertTrue(db.bookReviewDao.forBook("Book", "Author").isEmpty())
        assertEquals(first.id, db.bookReviewDao.forBook("Renamed", "New author").single().id)
        db.bookReviewDao.restore(listOf(first))
        assertEquals("Renamed", db.bookReviewDao.get(first.id)?.bookName)
    }

    @Test fun restoreIsIdempotentAndKeepsNewerLocalEdits() {
        val first = db.bookReviewDao.save(initial, now = 100)
        val edited = db.bookReviewDao.save(first.copy(content = "Latest text"), now = 200)
        db.bookReviewDao.restore(listOf(first))
        db.bookReviewDao.restore(listOf(first))
        assertEquals(listOf(edited), db.bookReviewDao.all())
        val other = initial.copy(id = "other", bookAuthor = "Other author", createdAt = 10, updatedAt = 10)
        db.bookReviewDao.restore(listOf(other))
        assertEquals(2, db.bookReviewDao.all().size)
    }

    @Test fun newDraftUsesTheBooksCurrentIdentityAfterRename() {
        val book = Book(bookUrl = "old-source", name = "Book", author = "Author")
        db.bookDao.insert(book)
        db.saveEditedBook(book.copy(name = "Renamed"), syncMetadata = false)
        assertEquals("Renamed", db.bookReviewDao.save(initial).bookName)
        assertTrue(db.bookReviewDao.forBook("Book", "Author").isEmpty())
    }

    @Test fun deletingInitialPromotesEarliestTextOnlyFollowUpAndKeepsItsDate() {
        val first = db.bookReviewDao.save(initial, now = 100)
        val newer = db.bookReviewDao.save(initial.copy(id = "newer", rating = 6,
            reviewDate = "2026-10-17"), now = 200)
        // A legacy backup may contain a text-only follow-up without its own score.
        val earlier = initial.copy(id = "earlier", rating = null, isFollowUp = true,
            content = "Text only", reviewDate = "2026-10-16", createdAt = 300, updatedAt = 300)
        db.bookReviewDao.insert(earlier)
        assertTrue(db.bookReviewDao.delete(first, now = 400))
        assertNull(db.bookReviewDao.get(first.id))
        val promoted = requireNotNull(db.bookReviewDao.get(earlier.id))
        assertFalse(promoted.isFollowUp)
        assertEquals(earlier.reviewDate, promoted.reviewDate)
        assertEquals(earlier.content, promoted.content)
        assertNull(promoted.rating)
        assertEquals(earlier.createdAt, promoted.createdAt)
        assertEquals(6, archiveBookReviews(db.bookReviewDao.all()).single().rating)
        db.bookReviewDao.save(promoted.copy(content = "Edited text only"), now = 500)
        assertEquals("Edited text only", db.bookReviewDao.get(earlier.id)?.content)
        assertTrue(db.bookReviewDao.get(newer.id)!!.isFollowUp)
    }

    @Test fun deletingLatestScoreFallsBackAndDeletingAllResetsArchiveAndNewInitial() {
        val first = db.bookReviewDao.save(initial, now = 100)
        val follow = db.bookReviewDao.save(initial.copy(id = "follow", rating = 6,
            reviewDate = "2026-10-16"), now = 200)
        db.bookReviewDao.delete(follow)
        assertEquals(9, archiveBookReviews(db.bookReviewDao.all()).single().rating)
        assertFalse(db.bookReviewDao.get(first.id)!!.isFollowUp)
        db.bookReviewDao.delete(first)
        assertTrue(archiveBookReviews(db.bookReviewDao.all()).isEmpty())
        assertFalse(db.bookReviewDao.delete(first))
        assertThrows(IllegalArgumentException::class.java) {
            db.bookReviewDao.save(initial.copy(id = "fresh", rating = null))
        }
        assertFalse(db.bookReviewDao.save(initial.copy(id = "fresh")).isFollowUp)
    }

    @Test fun staleEditorAndOldBackupCannotResurrectDeletedReviewOrUndoPromotion() {
        val first = db.bookReviewDao.save(initial, now = 100)
        val follow = db.bookReviewDao.save(initial.copy(id = "follow", rating = null,
            reviewDate = "2026-10-16"), now = 200)
        db.bookReviewDao.delete(first, now = 300)
        assertThrows(IllegalStateException::class.java) {
            db.bookReviewDao.save(first.copy(content = "Stale draft"), now = 400)
        }
        repeat(2) { db.bookReviewDao.restore(listOf(first, follow)) }
        assertEquals(1, db.bookReviewDao.all().size)
        assertFalse(db.bookReviewDao.get(follow.id)!!.isFollowUp)
        assertEquals(listOf(BookReviewDeletion(first.id)), db.bookReviewDao.deletions())
    }

    @Test fun restoringDeletionMarkersDeletesExistingRowsAndRepairsInitialAtomically() {
        val first = db.bookReviewDao.save(initial, now = 100)
        val follow = db.bookReviewDao.save(initial.copy(id = "follow", rating = null), now = 200)
        val other = db.bookReviewDao.save(initial.copy(id = "other", bookAuthor = "Other"), now = 300)
        repeat(2) {
            db.bookReviewDao.restore(listOf(first, follow), listOf(BookReviewDeletion(first.id)), now = 400)
        }
        assertNull(db.bookReviewDao.get(first.id))
        assertFalse(db.bookReviewDao.get(follow.id)!!.isFollowUp)
        assertEquals(other, db.bookReviewDao.get(other.id))
        assertThrows(IllegalArgumentException::class.java) {
            db.bookReviewDao.restore(listOf(initial.copy(id = "bad", rating = 12)),
                listOf(BookReviewDeletion(other.id)))
        }
        assertEquals(other, db.bookReviewDao.get(other.id))
        assertFalse(db.bookReviewDao.isDeleted(other.id))
    }

    @Test fun staleDeleteConfirmationDoesNotRemoveANewerEdit() {
        val first = db.bookReviewDao.save(initial, now = 100)
        val edited = db.bookReviewDao.save(first.copy(content = "New edit"), now = 200)
        assertThrows(IllegalStateException::class.java) { db.bookReviewDao.delete(first) }
        assertEquals(edited, db.bookReviewDao.get(first.id))
        assertTrue(db.bookReviewDao.deletions().isEmpty())
    }

    @Test fun newFollowUpWithoutRatingUsesLatestScoreAcrossLegacyUnratedReviews() {
        db.bookReviewDao.save(initial, now = 100)
        db.bookReviewDao.save(initial.copy(id = "rated", rating = 6,
            reviewDate = "2026-10-16"), now = 200)
        db.bookReviewDao.insert(initial.copy(id = "legacy", rating = null, isFollowUp = true,
            reviewDate = "2026-10-17"))
        val inherited = db.bookReviewDao.save(initial.copy(id = "new", rating = null,
            reviewDate = "2026-10-18"), now = 300)
        assertEquals(6, inherited.rating)
        assertTrue(inherited.isFollowUp)
        assertNull(db.bookReviewDao.get("legacy")!!.rating)
        assertEquals(10, db.bookReviewDao.save(initial.copy(id = "explicit", rating = 10)).rating)
    }

    @Test fun followUpRatingDoesNotCrossAuthorsOrInventMissingLegacyScores() {
        db.bookReviewDao.save(initial)
        assertThrows(IllegalArgumentException::class.java) {
            db.bookReviewDao.save(initial.copy(id = "other", bookAuthor = "Other", rating = null))
        }
        db.bookReviewDao.insert(initial.copy(id = "unrated", bookAuthor = "Other", rating = null))
        assertNull(db.bookReviewDao.save(initial.copy(id = "follow-other",
            bookAuthor = "Other", rating = null)).rating)
    }

    @Test fun editingOrRestoringLegacyFollowUpKeepsItsUnratedState() {
        db.bookReviewDao.save(initial, now = 100)
        val legacy = initial.copy(id = "legacy", rating = null, isFollowUp = true,
            createdAt = 200, updatedAt = 200)
        db.bookReviewDao.restore(listOf(legacy))
        val edited = db.bookReviewDao.save(legacy.copy(content = "Edited legacy text"), now = 300)
        assertNull(edited.rating)
        assertEquals("Edited legacy text", edited.content)
    }
}
