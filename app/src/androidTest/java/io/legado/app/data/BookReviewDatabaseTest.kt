package io.legado.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookReview
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
}
