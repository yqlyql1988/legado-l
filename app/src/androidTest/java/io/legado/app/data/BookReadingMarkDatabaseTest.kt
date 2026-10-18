package io.legado.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookReadingMarkDatabaseTest {
    private lateinit var db: AppDatabase
    private val book = Book(bookUrl = "reading-mark-book", name = "Book", author = "Author",
        durChapterIndex = 8, durChapterPos = 345)
    private val initial = BookReadingMark(book.name, book.author, BookReadingMark.ABANDONED,
        "2026-10-18", ReadingMarkChapter(book.bookUrl, "c8", "Chapter 8", 8, 345), "以后再看",
        listOf(ReadingMarkChapter(book.bookUrl, "c2", "Chapter 2", 2)))

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java).allowMainThreadQueries().build()
        db.bookDao.insert(book)
    }

    @After fun tearDown() = db.close()

    @Test fun saveAndCompletePreservePositionSkippedChaptersAndBookProgress() {
        val saved = db.bookReadingMarkDao.save(initial, 100)
        val complete = db.bookReadingMarkDao.save(saved.copy(status = BookReadingMark.FINISHED), 200)
        assertEquals(saved.skipped, complete.skipped)
        assertEquals(345, complete.position?.pos)
        assertEquals(8, db.bookDao.getBook(book.bookUrl)?.durChapterIndex)
        assertEquals(345, db.bookDao.getBook(book.bookUrl)?.durChapterPos)
    }

    @Test fun removingBookAndReplacingSourcePreservePersonalMarks() {
        val saved = db.bookReadingMarkDao.save(initial)
        db.bookDao.delete(book)
        assertEquals(saved, db.bookReadingMarkDao.get(book.name, book.author))
        db.bookDao.insert(book.copy(bookUrl = "other-source"))
        assertEquals(saved, db.bookReadingMarkDao.get(book.name, book.author))
        assertNull(db.bookReadingMarkDao.get(book.name, "Other author"))
    }

    @Test fun staleEditorCannotOverwriteNewerStatusOrSkippedChapters() {
        val saved = db.bookReadingMarkDao.save(initial, 100)
        val newest = db.bookReadingMarkDao.save(saved.copy(reason = "新原因"), 200)
        assertThrows(IllegalStateException::class.java) { db.bookReadingMarkDao.save(saved, 300) }
        assertEquals(newest, db.bookReadingMarkDao.get(book.name, book.author))
    }

    @Test fun repeatedRestoreKeepsNewerEditsAndEmptyTombstonesPreventResurrection() {
        val saved = db.bookReadingMarkDao.save(initial, 100)
        val cleared = db.bookReadingMarkDao.save(
            BookReadingMark(book.name, book.author, updatedAt = saved.updatedAt), 200)
        repeat(2) { db.bookReadingMarkDao.restore(listOf(saved)) }
        assertEquals(cleared, db.bookReadingMarkDao.get(book.name, book.author))
        assertTrue(db.bookReadingMarkDao.get(book.name, book.author)!!.skipped.isEmpty())
        db.bookReadingMarkDao.restore(listOf(saved.copy(bookAuthor = "Other")))
        assertEquals(2, db.bookReadingMarkDao.all().size)
    }

    @Test fun metadataRenameMovesMarksEvenWhenHistorySyncIsDisabledAndKeepsOtherAnnotations() {
        val saved = db.bookReadingMarkDao.save(initial, 100)
        val bookmark = Bookmark(time = 123, bookName = book.name, bookAuthor = book.author,
            chapterIndex = 2, chapterPos = 8, content = "Original bookmark")
        db.bookmarkDao.insert(bookmark)
        db.saveEditedBook(book.copy(name = "Renamed"), syncMetadata = false)
        val moved = requireNotNull(db.bookReadingMarkDao.get("Renamed", book.author))
        assertEquals(saved.skipped, moved.skipped)
        assertEquals(saved.position, moved.position)
        assertEquals(BookReadingMark.UNMARKED, db.bookReadingMarkDao.get(book.name, book.author)?.status)
        db.bookReadingMarkDao.restore(listOf(saved))
        assertEquals(BookReadingMark.UNMARKED, db.bookReadingMarkDao.get(book.name, book.author)?.status)
        db.openHelper.readableDatabase.query("select content from bookmarks where time = 123").use {
            assertTrue(it.moveToFirst())
            assertEquals("Original bookmark", it.getString(0))
        }
    }

    @Test fun renameCollisionKeepsLatestStatusAndBothSkippedSets() {
        db.bookReadingMarkDao.save(initial, 100)
        db.bookReadingMarkDao.save(initial.copy(bookName = "Renamed", status = BookReadingMark.FINISHED,
            skipped = listOf(ReadingMarkChapter("other", "c5", "Chapter 5", 5))), 200)
        db.saveEditedBook(book.copy(name = "Renamed"), syncMetadata = true)
        val merged = requireNotNull(db.bookReadingMarkDao.get("Renamed", book.author))
        assertEquals(BookReadingMark.FINISHED, merged.status)
        assertEquals(2, merged.skipped.size)
    }
}
