package io.legado.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.HighlightTag
import io.legado.app.data.entities.HighlightTagLink
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightTags
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HighlightTagDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private val first = BookHighlight(time = 101, bookUrl = "book-a", note = "Note A", style = "{}")
    private val second = BookHighlight(time = 102, bookUrl = "book-b", note = "Note B", style = "{}")

    @get:Rule val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Before fun before() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.bookHighlightDao.insert(first, second)
        db.highlightTagDao.saveTags(HighlightTags.defaults())
    }
    @After fun after() = db.close()

    @Test fun toggleAndGlobalDeletionKeepAnnotationsAndNotesInBothBooks() {
        val tag = db.highlightTagDao.all()[2]
        db.highlightTagDao.toggle(first.time, tag.id)
        db.highlightTagDao.toggle(second.time, tag.id)
        assertEquals(listOf(tag.id), db.highlightTagDao.idsForHighlight(first.time))
        db.highlightTagDao.toggle(first.time, tag.id)
        assertTrue(db.highlightTagDao.idsForHighlight(first.time).isEmpty())
        db.highlightTagDao.saveTags(db.highlightTagDao.all().filter { it.id != tag.id })
        assertTrue(db.highlightTagDao.links().isEmpty())
        assertEquals(first, db.bookHighlightDao.getByBook("book-a").single())
        assertEquals(second, db.bookHighlightDao.getByBook("book-b").single())
    }

    @Test fun restoringOlderAnnotationPreservesLabelLinksAndDeletingAnnotationCascadesOnlyItsLinks() {
        val tag = db.highlightTagDao.all().first()
        db.highlightTagDao.toggle(first.time, tag.id)
        db.highlightTagDao.toggle(second.time, tag.id)
        db.bookHighlightDao.insert(first.copy(note = "Restored note"))
        assertEquals(listOf(tag.id), db.highlightTagDao.idsForHighlight(first.time))
        db.bookHighlightDao.delete(first)
        assertTrue(db.highlightTagDao.idsForHighlight(first.time).isEmpty())
        assertEquals(listOf(tag.id), db.highlightTagDao.idsForHighlight(second.time))
        assertEquals(4, db.highlightTagDao.all().size)
    }

    @Test fun reorderAndRenamePreserveStableAssociationsAndEmptySettingsRemainEmpty() {
        val tag = db.highlightTagDao.all().last()
        db.highlightTagDao.toggle(first.time, tag.id)
        val reordered = db.highlightTagDao.all().reversed().map {
            if (it.id == tag.id) it.copy(name = "Changed") else it
        }
        db.highlightTagDao.saveTags(reordered)
        assertEquals(tag.id, db.highlightTagDao.all().first().id)
        assertEquals("Changed", db.highlightTagDao.all().first().name)
        assertEquals(listOf(tag.id), db.highlightTagDao.idsForHighlight(first.time))
        db.highlightTagDao.saveTags(emptyList())
        assertTrue(db.highlightTagDao.all().isEmpty())
        assertTrue(db.highlightTagDao.links().isEmpty())
        assertEquals(2, db.bookHighlightDao.all.size)
    }

    @Test fun repeatedRestoreDoesNotDuplicateLinksOrAttachOrphans() {
        val tags = HighlightTags.defaults().reversed()
        val links = listOf(HighlightTagLink(first.time, tags.first().id),
            HighlightTagLink(first.time, "missing"), HighlightTagLink(999, tags.first().id))
        repeat(2) { db.highlightTagDao.restore(tags, links, listOf(first.time)) }
        assertEquals(listOf(links.first()), db.highlightTagDao.links())
        db.highlightTagDao.toggle(999, tags.first().id)
        db.highlightTagDao.toggle(first.time, "missing")
        assertEquals(1, db.highlightTagDao.links().size)
    }

    @Test fun bookScopeRejectsForeignLinksAndBookOrderDoesNotChangeGlobalDefaults() {
        val dao = db.highlightTagDao
        val local = HighlightTag(id = "local-a", name = "本书线索", bookUrl = first.bookUrl)
        dao.saveTag(local)
        dao.toggle(first.time, local.id)
        dao.toggle(second.time, local.id)
        assertEquals(listOf(local.id), dao.idsForHighlight(first.time))
        assertTrue(dao.idsForHighlight(second.time).isEmpty())
        val global = HighlightTags.defaults()
        dao.saveOrder(first.bookUrl, listOf(local.id, global.last().id, global.first().id))
        dao.saveGlobalOrder(global.reversed().map { it.id })
        assertEquals(local.id, dao.forBook(first.bookUrl).first().id)
        assertEquals(global.last().id, dao.forBook(second.bookUrl).first().id)
        assertFalse(dao.forBook(second.bookUrl).any { it.id == local.id })
        dao.deleteTags(listOf(local))
        assertTrue(dao.idsForHighlight(first.time).isEmpty())
        assertEquals("Note A", db.bookHighlightDao.getByBook(first.bookUrl).single().note)
        assertEquals(first.style, db.bookHighlightDao.getByBook(first.bookUrl).single().style)
    }

    @Test fun legacyGlobalBackupKeepsNewBookLabelsLinksAndOrder() {
        val dao = db.highlightTagDao
        val local = HighlightTag(id = "local", name = "嫌疑人", bookUrl = first.bookUrl)
        dao.saveTag(local)
        dao.toggle(first.time, local.id)
        dao.saveOrder(first.bookUrl, listOf(local.id) + HighlightTags.defaults().map { it.id })
        val orders = dao.orders()
        dao.restore(HighlightTags.defaults(), emptyList(), listOf(first.time))
        assertTrue(dao.all().contains(local))
        assertEquals(listOf(local.id), dao.idsForHighlight(first.time))
        assertEquals(orders, dao.orders())
        // A new backup also validates scope rather than accepting foreign book associations.
        dao.restore(dao.all(), listOf(HighlightTagLink(second.time, local.id)),
            listOf(second.time), orders)
        assertTrue(dao.idsForHighlight(second.time).isEmpty())
    }

    @Test fun upgrade114KeepsTagIdsAssociationsAndTreatsExistingTagsAsShared() {
        val name = "highlight-tag-scope-migration"
        context.deleteDatabase(name)
        migrations.createDatabase(name, 114).apply {
            execSQL("""INSERT INTO highlights
                (time,bookUrl,chapterUrl,bookName,bookAuthor,chapterIndex,chapterPos,chapterPosEnd,
                layoutTitleLength,chapterName,bookText,style,note)
                VALUES (123,'book','chapter','Book','Author',2,5,8,0,'Chapter','abc','{"fill":19}','Old note')""")
            execSQL("""INSERT INTO highlight_tags (id,name,style,sortOrder) VALUES ('old','伏笔','{}',0)""")
            execSQL("""INSERT INTO highlight_tag_links VALUES (123,'old')""")
            close()
        }
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*DatabaseMigrations.migrations).build()
        try {
            assertEquals("", upgraded.highlightTagDao.all().single().bookUrl)
            assertEquals(listOf("old"), upgraded.highlightTagDao.idsForHighlight(123))
            assertTrue(upgraded.highlightTagDao.orders().isEmpty())
            assertEquals("Old note", upgraded.bookHighlightDao.all.single().note)
            assertEquals(19, upgraded.bookHighlightDao.all.single().styleObj().fill)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

    @Test fun restoringLargeAllBookBackupsKeepsEveryAssociationAndNote() {
        val annotations = (1..1500).map { index ->
            first.copy(time = 10000L + index, bookUrl = "book-$index", note = "Note $index")
        }
        db.bookHighlightDao.insert(*annotations.toTypedArray())
        val tags = HighlightTags.defaults()
        val links = annotations.map { HighlightTagLink(it.time, tags.first().id) }
        repeat(2) { db.highlightTagDao.restore(tags, links, annotations.map { it.time }) }
        assertEquals(links.toSet(), db.highlightTagDao.links().toSet())
        assertEquals(1502, db.bookHighlightDao.all.size)
        assertEquals("Note 1500", db.bookHighlightDao.getByBook("book-1500").single().note)
    }

    @Test fun upgrade113PreservesBaseStylesNotesBookmarksAndDeviceRecords() {
        val name = "highlight-tag-migration"
        context.deleteDatabase(name)
        migrations.createDatabase(name, 113).apply {
            execSQL("""INSERT INTO highlights
                (time,bookUrl,chapterUrl,bookName,bookAuthor,chapterIndex,chapterPos,chapterPosEnd,
                layoutTitleLength,chapterName,bookText,style,note)
                VALUES (123,'book','chapter','Book','Author',2,5,8,0,'Chapter','abc','{"fill":19}','Old note')""")
            execSQL("""INSERT INTO bookmarks
                (time,bookName,bookAuthor,chapterIndex,chapterPos,chapterName,bookText,content)
                VALUES (1,'Book','Author',2,5,'Chapter','abc','Bookmark')""")
            execSQL("""INSERT INTO readRecord(deviceId,bookName,author,readTime,lastRead)
                VALUES ('device-a','Book','Author',200,100),('device-b','Book','Author',300,200)""")
            close()
        }
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*DatabaseMigrations.migrations).build()
        try {
            val highlight = upgraded.bookHighlightDao.all.single()
            assertEquals("Old note", highlight.note)
            assertEquals(19, highlight.styleObj().fill)
            assertEquals(4, upgraded.highlightTagDao.all().size)
            assertTrue(upgraded.highlightTagDao.links().isEmpty())
            upgraded.openHelper.readableDatabase.query("SELECT count(*) FROM bookmarks").use {
                it.moveToFirst(); assertEquals(1, it.getInt(0))
            }
            upgraded.openHelper.readableDatabase.query("SELECT count(*),sum(readTime) FROM readRecord").use {
                it.moveToFirst(); assertEquals(2, it.getInt(0)); assertEquals(500, it.getInt(1))
            }
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
