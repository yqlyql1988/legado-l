package io.legado.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkTagDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private val bookmark = Bookmark(time = 101, bookName = "Book", bookAuthor = "Author",
        chapterIndex = 3, chapterPos = 17, content = "Bookmark note")
    private val highlight = BookHighlight(time = 101, bookUrl = "book", bookName = "Book",
        bookAuthor = "Author", note = "Highlight note", style = """{"fill":19}""")
    private val tag = HighlightTag("clue", "伏笔", sortOrder = 7, common = true,
        bookmarkCommon = false, bookmarkSortOrder = 2)
    @get:Rule val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory())
    @Before fun before() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.bookmarkDao.insert(bookmark)
        db.bookHighlightDao.insert(highlight)
        db.highlightTagDao.saveTag(tag)
    }
    @After fun after() = db.close()

    @Test fun identicalTimesBelongToIndependentRecordsAndUpdatesDoNotDropLinks() {
        db.bookmarkTagDao.saveLinks(101, "book", listOf("clue"))
        db.highlightTagDao.toggle(101, "clue")
        db.bookmarkDao.insert(bookmark.copy(content = "Changed bookmark"))
        assertEquals(listOf("clue"), db.bookmarkTagDao.idsForBookmark(101))
        assertEquals(listOf("clue"), db.highlightTagDao.idsForHighlight(101))
        db.bookmarkDao.delete(bookmark)
        assertTrue(db.bookmarkTagDao.links().isEmpty())
        assertEquals(listOf("clue"), db.highlightTagDao.idsForHighlight(101))
        assertEquals("Highlight note", db.bookHighlightDao.all.single().note)
    }

    @Test fun scenesHaveIndependentCommonFlagsAndPerBookVisibilityAndOrder() {
        assertEquals(listOf("clue"), db.highlightTagDao.forBook("book").map { it.id })
        assertTrue(db.bookmarkTagDao.forBook("book").isEmpty())
        db.bookmarkTagDao.saveBookLabels("book", listOf(BookmarkTagOrder("book", "clue", 0)))
        assertEquals(listOf("clue"), db.bookmarkTagDao.forBook("book").map { it.id })
        db.bookmarkTagDao.saveLinks(101, "book", listOf("clue"))
        db.bookmarkTagDao.saveBookLabels("book", listOf(BookmarkTagOrder("book", "clue", 0, true)))
        assertTrue(db.bookmarkTagDao.forBook("book").isEmpty())
        assertEquals(listOf("clue"), db.bookmarkTagDao.idsForBookmark(101))
        assertFalse(db.highlightTagDao.ordersForBook("book").any { it.hidden })
        assertEquals(7, db.highlightTagDao.all().single().sortOrder)
    }

    @Test fun restoreIsIdempotentRejectsOrphansAndAnExplicitEmptyBackupClearsLinks() {
        val links = listOf(BookmarkTagLink(101, "clue"), BookmarkTagLink(101, "missing"),
            BookmarkTagLink(999, "clue"))
        repeat(2) { db.bookmarkTagDao.restore(links, listOf(101, 999),
            listOf(BookmarkTagOrder("book", "clue", 0))) }
        assertEquals(listOf(links.first()), db.bookmarkTagDao.links())
        assertEquals(1, db.bookmarkTagDao.orders().size)
        db.bookmarkTagDao.restore(emptyList(), listOf(101), emptyList())
        assertTrue(db.bookmarkTagDao.links().isEmpty())
        assertTrue(db.bookmarkTagDao.orders().isEmpty())
        assertEquals(bookmark, db.bookmarkDao.all.single())
    }

    @Test fun sharedRenameAndDeleteUpdateBothScenesWhileKeepingNotesAndBaseStyles() {
        db.bookmarkTagDao.saveLinks(101, "book", listOf("clue"))
        db.highlightTagDao.toggle(101, "clue")
        db.highlightTagDao.saveTag(tag.copy(name = "重要伏笔"))
        assertEquals("重要伏笔", db.bookmarkTagDao.definitions().single().name)
        db.highlightTagDao.saveTags(emptyList())
        assertTrue(db.bookmarkTagDao.links().isEmpty())
        assertTrue(db.highlightTagDao.links().isEmpty())
        assertEquals(bookmark, db.bookmarkDao.all.single())
        assertEquals(19, db.bookHighlightDao.all.single().styleObj().fill)
    }

    @Test fun upgrading121KeepsBookmarksAnnotationsDeviceTimesAndProgress() {
        val name = "bookmark-tag-migration"
        context.deleteDatabase(name)
        migrations.createDatabase(name, 121).apply {
            execSQL("""INSERT INTO bookmarks
                (time,bookName,bookAuthor,chapterIndex,chapterPos,chapterName,bookText,content)
                VALUES(1,'Book','Author',3,17,'Chapter','Quote','Bookmark note')""")
            execSQL("""INSERT INTO highlight_tags(id,name,style,sortOrder,bookUrl,note,common)
                VALUES('old','Old tag','{}',4,'','Description',1)""")
            execSQL("""INSERT INTO readRecord(deviceId,bookName,author,readTime,lastRead,lastChapterPos)
                VALUES('device-a','Book','Author',200,100,17),('device-b','Book','Author',300,200,31)""")
            close()
        }
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*DatabaseMigrations.migrations).build()
        try {
            val old = upgraded.highlightTagDao.all().single()
            assertTrue(old.common)
            assertFalse(old.bookmarkCommon)
            assertEquals(4, old.sortOrder)
            assertEquals("Description", old.note)
            assertEquals(17, upgraded.bookmarkDao.all.single().chapterPos)
            assertTrue(upgraded.bookmarkTagDao.links().isEmpty())
            upgraded.openHelper.readableDatabase.query("SELECT sum(readTime),sum(lastChapterPos) FROM readRecord").use {
                it.moveToFirst()
                assertEquals(500, it.getInt(0))
                assertEquals(48, it.getInt(1))
            }
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

    @Test fun upgrading122RetainsSourceLocationsAndAddsIndependentBookmarkSettings() {
        val name = "bookmark-tags-from-location-schema"
        context.deleteDatabase(name)
        migrations.createDatabase(name, 122).use { old ->
            old.execSQL("""INSERT INTO highlight_tags(id,name,style,sortOrder,bookUrl,note,common)
                VALUES('old','Old tag','{}',4,'','Description',1)""")
            old.execSQL("""INSERT INTO reading_note_locations
                (kind,noteTime,bookUrl,chapterUrl,chapterIndex,chapterName,chapterPos,chapterPosEnd,
                 layoutTitleLength,quote,prefix,suffix,verified,updatedAt)
                VALUES('bookmark',1,'book','chapter',3,'Chapter',17,22,0,'Quote','Before','After',1,100)""")
            old.execSQL("""INSERT INTO reading_note_sources(bookUrl,workId,activeBookUrl,updatedAt)
                VALUES('book','work','another-source',100)""")
        }
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*DatabaseMigrations.migrations).build()
        try {
            assertEquals(123, upgraded.openHelper.writableDatabase.version)
            val location = upgraded.readingNoteDao.locations().single()
            assertEquals("Quote", location.quote)
            assertEquals(17, location.chapterPos)
            assertTrue(location.verified)
            assertEquals("another-source", upgraded.readingNoteDao.sources().single().activeBookUrl)
            val tag = upgraded.highlightTagDao.all().single()
            assertTrue(tag.common)
            assertFalse(tag.bookmarkCommon)
            assertEquals(4, tag.sortOrder)
            assertEquals(0, tag.bookmarkSortOrder)
            assertTrue(upgraded.bookmarkTagDao.links().isEmpty())
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
