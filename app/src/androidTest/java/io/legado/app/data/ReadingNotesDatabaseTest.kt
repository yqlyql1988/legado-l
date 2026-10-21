package io.legado.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.*
import io.legado.app.help.ReadingNotes
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadingNotesDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migration121RetainsLegacyNotesAndReadingData() {
        val name = "notes-migration-${UUID.randomUUID()}"
        try {
            helper.createDatabase(name, 121).use { db ->
                db.execSQL("""INSERT INTO bookmarks VALUES(1,'Book','Author',2,33,'Old chapter','Quote','Memo')""")
                db.execSQL("""INSERT INTO highlights(time,bookUrl,chapterUrl,bookName,bookAuthor,
                    chapterIndex,chapterPos,chapterPosEnd,layoutTitleLength,chapterName,bookText,style,note)
                    VALUES(2,'book','chapter','Book','Author',2,33,38,5,'Old chapter','Quote','','Note')""")
                db.execSQL("INSERT INTO readRecord(deviceId,bookName,author,readTime,lastRead) VALUES('phone','Book','Author',1000,100)")
            }
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(*DatabaseMigrations.migrations).build()
            try {
                assertEquals(123, db.openHelper.writableDatabase.version)
                assertEquals("Memo", db.bookmarkDao.all.single().content)
                assertEquals("Note", db.bookHighlightDao.all.single().note)
                assertEquals(33, db.bookHighlightDao.all.single().chapterPos)
                assertEquals(1000L, db.readRecordDao.all.single().readTime)
                assertTrue(db.readingNoteDao.locations().isEmpty())
                assertTrue(db.readingNoteDao.sources().isEmpty())
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun genericSourceChangeAndReturnKeepOriginalsAndLabelLinks() = withNotes { a, b, highlight, bookmark ->
        val chaptersA = listOf(BookChapter(bookUrl = a.bookUrl, url = "old/chapter", index = 0,
            title = "第一章 原章名"))
        val chaptersB = listOf(BookChapter(bookUrl = b.bookUrl, url = "new/chapter?key=changed", index = 1,
            title = "第1章 修订章名"))
        appDb.bookChapterDao.insert(*chaptersA.toTypedArray())
        val original = ReadingNotes.legacy(highlight).copy(prefix = "before ", suffix = " after", updatedAt = 10)
        val originalBookmark = ReadingNotes.legacy(bookmark, a.bookUrl).copy(
            chapterUrl = chaptersA.single().url, prefix = "before ", suffix = " after", updatedAt = 10)
        appDb.readingNoteDao.saveLocations(listOf(original, originalBookmark))
        val tag = HighlightTag(id = "notes-${UUID.randomUUID()}", name = "Local fixture", bookUrl = a.bookUrl)
        val link = HighlightTagLink(highlight.time, tag.id)
        appDb.highlightTagDao.upsertTags(listOf(tag))
        appDb.highlightTagDao.upsertLinks(listOf(link))
        try {
            replaceBookAfterSourceChange(a, b, chaptersB, clearActiveReader = false)
            assertEquals(1, appDb.bookHighlightDao.getByBook(b.bookUrl).size)
            assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(b.name, b.author))
            val candidate = requireNotNull(ReadingNotes.target(
                ReadingNotes.legacy(appDb.bookHighlightDao.getByBook(b.bookUrl).single()), chaptersB))
            assertFalse(candidate.verified)
            assertEquals(1, candidate.chapterIndex)
            val body = "new preface before Quote after"
            val resolved = requireNotNull(ReadingNotes.captured(candidate, chaptersB.single(), body, 3))
            assertTrue(ReadingNotes.saveLocation(resolved, b.name, b.author))
            val migrated = appDb.bookHighlightDao.getByBook(b.bookUrl).single()
            assertEquals(body.indexOf("Quote") + 3, migrated.chapterPos)
            assertEquals(highlight.time, migrated.time)
            assertEquals(highlight.bookText, migrated.bookText)
            assertEquals(highlight.note, migrated.note)
            assertEquals(highlight.style, migrated.style)
            assertEquals(listOf(tag.id), appDb.highlightTagDao.idsForHighlight(highlight.time))
            assertTrue(appDb.highlightTagDao.forBook(b.bookUrl).any { it.id == tag.id })
            val bookmarkTarget = requireNotNull(ReadingNotes.target(ReadingNotes.legacy(bookmark, b.bookUrl), chaptersB))
            val relocatedBookmark = requireNotNull(ReadingNotes.captured(bookmarkTarget, chaptersB.single(), body, 3))
            assertTrue(ReadingNotes.saveLocation(relocatedBookmark, b.name, b.author))

            replaceBookAfterSourceChange(b, a, chaptersA, clearActiveReader = false)
            val returned = appDb.bookHighlightDao.getByBook(a.bookUrl).single()
            assertEquals(highlight, returned)
            val returnedBookmark = appDb.bookmarkDao.getByBook(a.name, a.author).single()
            assertEquals(bookmark, returnedBookmark)
            assertEquals(2, appDb.readingNoteDao.locationsForNote("highlight", highlight.time).size)
            assertEquals(listOf(tag.id), appDb.highlightTagDao.idsForHighlight(highlight.time))
        } finally {
            appDb.highlightTagDao.deleteTags(listOf(tag))
            appDb.highlightTagDao.clearOrder(a.bookUrl)
            appDb.highlightTagDao.clearOrder(b.bookUrl)
        }
    }

    @Test fun lateLayoutCannotOverwriteManualRelocationOrRecreateDeletedData() = withNotes { a, _, highlight, _ ->
        val initial = ReadingNotes.legacy(highlight).copy(updatedAt = 10)
        val manual = initial.copy(chapterPos = 99, chapterPosEnd = 104, quote = "New text", updatedAt = 30)
        assertTrue(ReadingNotes.saveLocation(manual, a.name, a.author))
        assertFalse(ReadingNotes.saveLocation(initial, a.name, a.author))
        assertEquals(99, appDb.bookHighlightDao.getByBook(a.bookUrl).single().chapterPos)
        assertEquals(highlight.bookText, appDb.bookHighlightDao.getByBook(a.bookUrl).single().bookText)
        appDb.bookHighlightDao.delete(highlight)
        appDb.readingNoteDao.deleteLocations("highlight", listOf(highlight.time))
        assertFalse(ReadingNotes.saveLocation(initial.copy(updatedAt = 40), a.name, a.author))
        assertTrue(appDb.readingNoteDao.locationsForNote("highlight", highlight.time).isEmpty())
    }

    @Test fun staleHighlightCanFollowUniqueSameNameAndAuthorSource() = withNotes { a, b, highlight, _ ->
        appDb.bookDao.insert(b)
        try {
            val relocated = ReadingNotes.legacy(highlight).copy(
                bookUrl = b.bookUrl,
                chapterUrl = "new/chapter",
                chapterIndex = 1,
                chapterName = "新章节",
                chapterPos = 44,
                chapterPosEnd = 49,
                quote = "New text",
                verified = true,
                updatedAt = 20,
            )
            assertTrue(ReadingNotes.saveLocation(relocated, b.name, b.author))
            val saved = appDb.bookHighlightDao.all.single()
            assertEquals(b.bookUrl, saved.bookUrl)
            assertEquals(44, saved.chapterPos)
            assertEquals(relocated, appDb.readingNoteDao.locationsForNote(
                "highlight", highlight.time
            ).single { it.bookUrl == b.bookUrl })
        } finally {
            appDb.readingNoteDao.deleteLocations("highlight", listOf(highlight.time))
            appDb.bookDao.delete(b)
        }
    }

    @Test fun oldBackupPreservesNewerContextAndRehomesExplicitlyLinkedSources() = withNotes { a, b, highlight, bookmark ->
        val chapters = listOf(BookChapter(bookUrl = b.bookUrl, url = "b/chapter", index = 0,
            title = "第一章 原章名"))
        replaceBookAfterSourceChange(a, b, chapters, clearActiveReader = false)
        val candidate = requireNotNull(ReadingNotes.target(
            ReadingNotes.legacy(appDb.bookHighlightDao.getByBook(b.bookUrl).single()), chapters))
        val current = candidate.copy(prefix = "new prefix", chapterPos = 44, chapterPosEnd = 49,
            verified = true, updatedAt = candidate.updatedAt + 10)
        assertTrue(ReadingNotes.saveLocation(current, b.name, b.author))
        repeat(2) {
            appDb.bookHighlightDao.insert(highlight)
            appDb.runInTransaction { ReadingNotes.restore(null, null, listOf(highlight), listOf(bookmark)) }
            assertTrue(appDb.bookHighlightDao.getByBook(a.bookUrl).isEmpty())
            assertEquals(44, appDb.bookHighlightDao.getByBook(b.bookUrl).single().chapterPos)
            assertEquals(current, ReadingNotes.location(current))
        }
        appDb.runInTransaction {
            ReadingNotes.restore(listOf(current.copy(updatedAt = 1, prefix = "stale"),
                current.copy(bookUrl = "invalid", chapterPos = -1)), null, listOf(highlight), listOf(bookmark))
        }
        assertEquals(current, ReadingNotes.location(current))
        assertFalse(appDb.readingNoteDao.locationsForNote("highlight", highlight.time).any { it.bookUrl == "invalid" })
    }

    private fun withNotes(test: (Book, Book, BookHighlight, Bookmark) -> Unit) {
        val id = UUID.randomUUID().toString()
        val a = Book(bookUrl = "notes-a-$id", name = "Notes $id", author = "Fixture", totalChapterNum = 2)
        val b = a.copy(bookUrl = "notes-b-$id")
        val highlight = BookHighlight(bookUrl = a.bookUrl, bookName = a.name, bookAuthor = a.author,
            chapterUrl = "old/chapter", chapterIndex = 0, chapterName = "第一章 原章名",
            chapterPos = 7, chapterPosEnd = 12, layoutTitleLength = 0, bookText = "Quote",
            note = "Keep note", style = """{"fill":123}""")
        val bookmark = Bookmark(time = highlight.time + 1, bookName = a.name, bookAuthor = a.author,
            chapterIndex = 0, chapterPos = 7, chapterName = highlight.chapterName, bookText = "Quote", content = "Keep memo")
        try {
            appDb.bookDao.insert(a)
            appDb.bookHighlightDao.insert(highlight)
            appDb.bookmarkDao.insert(bookmark)
            test(a, b, highlight, bookmark)
        } finally {
            appDb.bookHighlightDao.delete(highlight)
            appDb.bookmarkDao.delete(bookmark)
            appDb.readingNoteDao.deleteLocations("highlight", listOf(highlight.time))
            appDb.readingNoteDao.deleteLocations("bookmark", listOf(bookmark.time))
            appDb.readingNoteDao.deleteSources(listOf(a.bookUrl, b.bookUrl))
            appDb.bookDao.delete(a, b)
            appDb.readRecordDao.deleteByBook(a.name, a.author)
        }
    }
}
