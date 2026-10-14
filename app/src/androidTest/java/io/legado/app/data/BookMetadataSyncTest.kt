package io.legado.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.prepareSaveWithCover
import io.legado.app.data.entities.readRecordMetadataRoutes
import io.legado.app.help.globalExecutor
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BookMetadataSyncTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withDatabase(block: (AppDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun bookmark(book: Book, time: Long = 1) = Bookmark(
        time = time, bookName = book.name, bookAuthor = book.author,
        chapterIndex = 7, chapterPos = 29, chapterName = "Chapter", bookText = "Text", content = "Note",
    )

    @Test
    fun titleAuthorAndCombinedEditsMoveBookmarksAndEveryDevicesHistory() {
        listOf("New" to "A", "Old" to "B", "New" to "B").forEach { (name, author) ->
            withDatabase { db ->
                val book = Book(bookUrl = "book", name = "Old", author = "A")
                val mark = bookmark(book)
                val records = listOf(
                    ReadRecord("phone", "Old", "A", 100, 20, "Chapter", 7, 29, "/cover"),
                    ReadRecord("tablet", "Old", "A", 250, 10, "Earlier", 3, 12),
                )
                val unrelated = ReadRecord("phone", "Old", "Another", 30)
                val unrelatedMark = mark.copy(time = 2, bookAuthor = "Another")
                db.bookDao.insert(book)
                db.bookmarkDao.insert(mark, unrelatedMark)
                db.readRecordDao.insert(*(records + unrelated).toTypedArray())
                db.saveEditedBook(book.copy(name = name, author = author), true)
                assertEquals(listOf(mark.copy(bookName = name, bookAuthor = author)),
                    db.bookmarkDao.getByBook(name, author))
                assertEquals(unrelatedMark, db.bookmarkDao.getByBook("Old", "Another").single())
                records.forEach {
                    assertEquals(it.copy(bookName = name, author = author),
                        db.readRecordDao.getRecord(it.deviceId, name, author))
                }
                assertEquals(unrelated, db.readRecordDao.getRecord("phone", "Old", "Another"))
                assertEquals(380L, db.readRecordDao.allTime)
            }
        }
    }

    @Test
    fun nonsyncedAndUnchangedEditsLeaveHistoryAndBookmarksIntact() = withDatabase { db ->
        val book = Book(bookUrl = "book", name = "Old", author = "A")
        val mark = bookmark(book)
        val record = ReadRecord("phone", "Old", "A", 100, 20)
        val highlight = BookHighlight(time = 1, bookUrl = book.bookUrl, bookName = "Old", bookAuthor = "A")
        db.bookDao.insert(book)
        db.bookmarkDao.insert(mark)
        db.readRecordDao.insert(record)
        db.bookHighlightDao.insert(highlight)
        db.saveEditedBook(book.copy(customIntro = "New intro"), true)
        assertEquals(listOf(mark), db.bookmarkDao.all)
        assertEquals(listOf(record), db.readRecordDao.all)
        db.saveEditedBook(book.copy(name = "New", author = "B"), false)
        assertEquals(listOf(mark), db.bookmarkDao.all)
        assertEquals(listOf(record), db.readRecordDao.all)
        assertEquals("New", db.bookDao.getBook("book")?.name)
        assertEquals("New", db.bookHighlightDao.getByBook("book").single().bookName)
    }

    @Test
    fun progressUpdatedWhileTheEditPageWasOpenIsPreserved() = withDatabase { db ->
        val opened = Book(bookUrl = "book", name = "Old", author = "A",
            durChapterIndex = 1, durChapterPos = 10, durChapterTitle = "Earlier")
        val latest = opened.copy(durChapterIndex = 8, durChapterPos = 45, durChapterTitle = "Latest")
        db.bookDao.insert(latest)
        db.saveEditedBook(opened.copy(name = "New"), true)
        val saved = checkNotNull(db.bookDao.getBook("book"))
        assertEquals("New", saved.name)
        assertEquals(8, saved.durChapterIndex)
        assertEquals(45, saved.durChapterPos)
        assertEquals("Latest", saved.durChapterTitle)
    }

    @Test
    fun existingDestinationMergesDurationsAndKeepsTheLatestSnapshot() = withDatabase { db ->
        val book = Book(bookUrl = "book", name = "Old", author = "A")
        val source = ReadRecord("phone", "Old", "A", 100, 10, "Old chapter", 2, 12, "/old")
        val destination = ReadRecord("phone", "New", "B", 200, 30, "Latest chapter", 8, 37, "/new")
        val resolvedSource = source.copy(deviceId = "tablet", author = "", resolvedAuthor = "A", readTime = 40)
        val directSource = source.copy(deviceId = "tablet", readTime = 60, lastRead = 40)
        val resolvedDestination = destination.copy(deviceId = "tablet", author = "", resolvedAuthor = "B", readTime = 80)
        db.bookDao.insert(book)
        db.readRecordDao.insert(source, destination, resolvedSource, directSource, resolvedDestination)
        db.saveEditedBook(book.copy(name = "New", author = "B"), true)
        assertEquals(destination.copy(readTime = 300), db.readRecordDao.getRecord("phone", "New", "B"))
        val tablet = checkNotNull(db.readRecordDao.getRecord("tablet", "New", "B"))
        assertEquals(180L, tablet.readTime)
        assertEquals(40L, tablet.lastRead)
        assertEquals(2, tablet.lastChapterIndex)
        assertEquals(12, tablet.lastChapterPos)
        assertNull(tablet.resolvedAuthor)
        assertEquals(2, db.readRecordDao.all.size)
        assertEquals(480L, db.readRecordDao.allTime)
    }

    @Test
    fun unknownAndCombinedAuthorsAreNotAssignedToAKnownAuthor() = withDatabase { db ->
        val book = Book(bookUrl = "book", name = "Old", author = "A")
        val direct = ReadRecord("phone", "Old", "A", 100)
        val resolved = direct.copy(author = "", resolvedAuthor = "A", readTime = 50)
        val unknown = direct.copy(deviceId = "unknown", author = "", readTime = 70)
        val combined = direct.copy(deviceId = "legacy", author = "\u001Eauthors:[\"A\",\"B\"]", readTime = 90)
        val other = direct.copy(deviceId = "other", author = "", resolvedAuthor = "B", readTime = 30)
        db.bookDao.insert(book)
        db.readRecordDao.insert(direct, resolved, unknown, combined, other)
        db.saveEditedBook(book.copy(name = "New", author = "C"), true)
        assertEquals(150L, db.readRecordDao.getReadTime("phone", "New", "C"))
        assertEquals(setOf(unknown, combined, other),
            db.readRecordDao.all.filter { it.bookName == "Old" }.toSet())
        assertEquals(340L, db.readRecordDao.allTime)
    }

    @Test
    fun aBookWithAnUnknownAuthorCanRenameItsOwnUnknownHistory() = withDatabase { db ->
        val book = Book(bookUrl = "book", name = "Old", author = "")
        val unknown = ReadRecord("phone", "Old", "", 70)
        val resolvedOther = unknown.copy(deviceId = "other", resolvedAuthor = "Other", readTime = 30)
        db.bookDao.insert(book)
        db.readRecordDao.insert(unknown, resolvedOther)
        db.saveEditedBook(book.copy(name = "New", author = "A"), true)
        assertEquals(unknown.copy(bookName = "New", author = "A"), db.readRecordDao.getRecord("phone", "New", "A"))
        assertEquals(resolvedOther, db.readRecordDao.getRecord("other", "Old", ""))
    }

    @Test
    fun authorOnlyEditsToBlankDoNotCountResolvedRowsTwice() = withDatabase { db ->
        val book = Book(bookUrl = "book", name = "Old", author = "A")
        db.bookDao.insert(book)
        db.readRecordDao.insert(
            ReadRecord("phone", "Old", "A", 100),
            ReadRecord("phone", "Old", "", 50, resolvedAuthor = "A"),
        )
        db.saveEditedBook(book.copy(author = ""), true)
        assertEquals(150L, db.readRecordDao.getReadTime("phone", "Old", ""))
        assertEquals(1, db.readRecordDao.all.size)
    }

    @Test
    fun duplicateBooksRejectTheEditWithoutMovingAnyRelatedData() = withDatabase { db ->
        val book = Book(bookUrl = "book", name = "Old", author = "A")
        val existing = book.copy(bookUrl = "other", name = "New", author = "B")
        val mark = bookmark(book)
        val record = ReadRecord("phone", "Old", "A", 100)
        db.bookDao.insert(book, existing)
        db.bookmarkDao.insert(mark)
        db.readRecordDao.insert(record)
        try {
            db.saveEditedBook(book.copy(name = "New", author = "B"), true)
            fail("Duplicate title/author must fail")
        } catch (_: SQLiteConstraintException) {
            assertEquals(book, db.bookDao.getBook("book"))
            assertEquals(listOf(mark), db.bookmarkDao.all)
            assertEquals(listOf(record), db.readRecordDao.all)
        }
    }

    @Test
    fun aMigrationFailureRollsBackTheBookBookmarksHighlightsAndHistory() = withDatabase { db ->
        val book = Book(bookUrl = "book", name = "Old", author = "A")
        val mark = bookmark(book)
        val highlight = BookHighlight(time = 1, bookUrl = book.bookUrl, bookName = "Old", bookAuthor = "A")
        val records = listOf(
            ReadRecord("phone", "Old", "A", Long.MAX_VALUE),
            ReadRecord("phone", "New", "B", 1),
        )
        db.bookDao.insert(book)
        db.bookmarkDao.insert(mark)
        db.bookHighlightDao.insert(highlight)
        db.readRecordDao.insert(*records.toTypedArray())
        try {
            db.saveEditedBook(book.copy(name = "New", author = "B"), true)
            fail("Duration overflow must roll back the complete edit")
        } catch (_: ArithmeticException) {
            assertEquals(book, db.bookDao.getBook("book"))
            assertEquals(listOf(mark), db.bookmarkDao.all)
            assertEquals(listOf(highlight), db.bookHighlightDao.all)
            assertEquals(records.toSet(), db.readRecordDao.all.toSet())
        }
    }

    @Test
    fun pendingRealHistoryWritesFollowSyncAndStaySeparateWhenNotSynced() {
        for (sync in listOf(true, false)) {
            val id = UUID.randomUUID().toString()
            val book = Book(bookUrl = id, name = "Old-$id", author = "A")
            val edited = book.copy(name = "New-$id", author = "B")
            val record = ReadRecord("test-device", book.name, book.author, 100, 10)
            globalExecutor.submit {
                try {
                    appDb.bookDao.insert(book)
                    appDb.readRecordDao.insert(record)
                    val pending = record.copy(lastRead = 20).prepareSaveWithCover(book.copy(), 40)
                    appDb.saveEditedBook(edited, sync)
                    readRecordMetadataRoutes.renamed(id, book.name, book.author, edited.name, edited.author, sync)
                    pending()
                    val expected = if (sync) edited else book
                    assertEquals(140L, appDb.readRecordDao.getReadTime("test-device", expected.name, expected.author))
                    val absent = if (sync) book else edited
                    assertNull(appDb.readRecordDao.getRecord("test-device", absent.name, absent.author))
                } finally {
                    appDb.bookDao.delete(edited)
                    appDb.readRecordDao.deleteByBook(book.name, book.author)
                    appDb.readRecordDao.deleteByBook(edited.name, edited.author)
                }
            }.get(15, TimeUnit.SECONDS)
        }
    }

    @Test
    fun updatingPausedReadersDoesNotCountTimeSpentOnTheEditPage() {
        val enabled = AppConfig.enableReadRecord
        AppConfig.enableReadRecord = true
        try {
            for (model in listOf(ReadBook, ReadManga)) {
                val field = model.javaClass.getDeclaredField("readRecord").apply { isAccessible = true }
                val previousRecord = field.get(model)
                val previousBook = if (model === ReadBook) ReadBook.book else ReadManga.book
                val previousStart = if (model === ReadBook) ReadBook.readStartTime else ReadManga.readStartTime
                val id = UUID.randomUUID().toString()
                val book = Book(bookUrl = id, name = "Paused-$id", author = "A")
                val record = ReadRecord("test-device", book.name, book.author, 100)
                try {
                    globalExecutor.submit {
                        appDb.readRecordDao.insert(record)
                        field.set(model, record.copy())
                        if (model === ReadBook) {
                            ReadBook.book = book
                            ReadBook.readStartTime = System.currentTimeMillis() - 60_000
                            ReadBook.updateEditedMetadata(book.copy(name = "New-$id"))
                        } else {
                            ReadManga.book = book
                            ReadManga.readStartTime = System.currentTimeMillis() - 60_000
                            ReadManga.updateEditedMetadata(book.copy(name = "New-$id"))
                        }
                    }.get(15, TimeUnit.SECONDS)
                    // Any incorrectly queued interval must finish before checking the stored total.
                    globalExecutor.submit {
                        assertEquals(100L, appDb.readRecordDao.getReadTime("test-device", book.name, book.author))
                    }.get(15, TimeUnit.SECONDS)
                } finally {
                    globalExecutor.submit {
                        field.set(model, previousRecord)
                        if (model === ReadBook) {
                            ReadBook.book = previousBook
                            ReadBook.readStartTime = previousStart
                            previousBook?.let(ReadBook::loadHighlights)
                        } else {
                            ReadManga.book = previousBook
                            ReadManga.readStartTime = previousStart
                        }
                        appDb.readRecordDao.deleteByBook(book.name, book.author)
                        appDb.readRecordDao.deleteByBook("New-$id", book.author)
                    }.get(15, TimeUnit.SECONDS)
                }
            }
        } finally {
            AppConfig.enableReadRecord = enabled
        }
    }
}
