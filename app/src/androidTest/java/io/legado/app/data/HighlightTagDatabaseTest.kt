package io.legado.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.HighlightTag
import io.legado.app.data.entities.HighlightTagLink
import io.legado.app.data.entities.HighlightTagOrder
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

    @Test fun commonFlagMakesBookLabelReusableWithoutChangingItsIdentity() {
        val dao = db.highlightTagDao
        val local = HighlightTag(id = "share", name = "好看", bookUrl = first.bookUrl, note = "标签说明")
        dao.saveTag(local)
        dao.toggle(first.time, local.id)
        dao.saveTag(local.copy(common = true))
        assertEquals(local.copy(common = true), dao.all().first { it.id == local.id })
        assertTrue(dao.forBook(second.bookUrl).any { it.id == local.id })
        dao.toggle(second.time, local.id)
        assertEquals(listOf(local.id), dao.idsForHighlight(second.time))
        dao.saveTag(local)
        assertEquals(listOf(local.id), dao.idsForHighlight(first.time))
        assertEquals(listOf(local.id), dao.idsForHighlight(second.time))
        assertTrue(dao.forBook(second.bookUrl).any { it.id == local.id })
        assertFalse(dao.forBook("unused").any { it.id == local.id })
        assertEquals(listOf(first, second), db.bookHighlightDao.all.sortedBy { it.time })
    }

    @Test fun hidingCommonLabelKeepsCommonFlagLinksNotesAndEffectivePriority() {
        val dao = db.highlightTagDao
        val tags = dao.all()
        tags.take(2).forEach { dao.toggle(first.time, it.id) }
        val before = HighlightTags.effectiveStyle(first, dao.forBook(first.bookUrl), dao.idsForHighlight(first.time))
        val entries = dao.forBook(first.bookUrl).mapIndexed { index, tag ->
            HighlightTagOrder(first.bookUrl, tag.id, index, tag.id == tags.first().id)
        }
        dao.saveBookLabels(first.bookUrl, entries)
        assertTrue(dao.all().first().common)
        assertFalse(dao.forBook(first.bookUrl).any { it.id == tags.first().id })
        assertTrue(dao.forBook(second.bookUrl).any { it.id == tags.first().id })
        assertEquals(tags.take(2).map { it.id }.toSet(), dao.idsForHighlight(first.time).toSet())
        assertEquals(before, HighlightTags.effectiveStyle(first, dao.forBook(first.bookUrl, true),
            dao.idsForHighlight(first.time)))
        dao.saveGlobalOrder(tags.reversed().map { it.id })
        assertTrue(dao.ordersForBook(first.bookUrl).first { it.tagId == tags.first().id }.hidden)
        dao.saveOrder(first.bookUrl, dao.forBook(first.bookUrl).map { it.id })
        assertTrue(dao.ordersForBook(first.bookUrl).any { it.hidden && it.tagId == tags.first().id })
        assertEquals(first, db.bookHighlightDao.getByBook(first.bookUrl).single())
    }

    @Test fun anyLibraryLabelCanBeAddedToAnotherBookAndHiddenLabelCanBeReadded() {
        val dao = db.highlightTagDao
        val local = HighlightTag(id = "character", name = "张三", bookUrl = first.bookUrl)
        dao.saveTag(local)
        assertFalse(dao.canAttach(second.time, local.id))
        dao.saveBookLabels(second.bookUrl, listOf(HighlightTagOrder(second.bookUrl, local.id, 0)))
        assertTrue(dao.canAttach(second.time, local.id))
        dao.toggle(second.time, local.id)
        dao.saveBookLabels(second.bookUrl, listOf(HighlightTagOrder(second.bookUrl, local.id, 0, true)))
        assertFalse(dao.canAttach(second.time, local.id))
        assertEquals(listOf(local.id), dao.idsForHighlight(second.time))
        // A selected hidden tag can still be unselected from the annotation's popup.
        dao.toggle(second.time, local.id)
        assertTrue(dao.idsForHighlight(second.time).isEmpty())
        dao.saveBookLabels(second.bookUrl, listOf(HighlightTagOrder(second.bookUrl, local.id, 0)))
        assertEquals(local.id, dao.forBook(second.bookUrl).first().id)
        assertFalse(dao.all().first { it.id == local.id }.common)
    }

    @Test fun sameNameLabelsStayIndependentUntilThisBookExplicitlyChoosesAnother() {
        val dao = db.highlightTagDao
        val one = HighlightTag(id = "name-a", name = "好看", bookUrl = first.bookUrl, note = "A",
            style = """{"bold":true}""")
        val two = HighlightTag(id = "name-b", name = "好看", bookUrl = second.bookUrl, note = "B",
            style = """{"italic":true}""")
        dao.saveTag(one)
        dao.saveTag(two)
        dao.toggle(first.time, one.id)
        dao.toggle(second.time, two.id)
        dao.saveTag(one.copy(common = true))
        assertEquals(two.id, HighlightTags.choices(dao.forBook(second.bookUrl), second.bookUrl)
            .single { it.name == "好看" }.id)
        assertEquals(listOf(two.id), dao.idsForHighlight(second.time))
        dao.saveBookLabels(second.bookUrl, listOf(HighlightTagOrder(second.bookUrl, one.id, 0),
            HighlightTagOrder(second.bookUrl, two.id, 1, true)))
        dao.useLibraryLabel(second.bookUrl, two.id, one.id)
        assertEquals(listOf(one.id), dao.idsForHighlight(second.time))
        assertEquals(listOf(one.id), dao.idsForHighlight(first.time))
        assertEquals(two, dao.all().first { it.id == two.id })
        assertEquals(second, db.bookHighlightDao.getByBook(second.bookUrl).single())
    }

    @Test fun libraryDeletionReportsUsageAndKeepsAnnotationsAndBaseStyles() {
        val dao = db.highlightTagDao
        val tag = dao.all().first()
        dao.toggle(first.time, tag.id)
        dao.toggle(second.time, tag.id)
        assertEquals(2, dao.usage(tag.id).books)
        assertEquals(2, dao.usage(tag.id).highlights)
        dao.saveDefinitions(dao.all().filterNot { it.id == tag.id })
        assertTrue(dao.links().isEmpty())
        assertEquals(listOf(first, second), db.bookHighlightDao.all.sortedBy { it.time })
        assertEquals(first.styleObj(), HighlightTags.effectiveStyle(first, dao.forBook(first.bookUrl), emptyList()))
    }

    @Test fun backupDataRoundTripsCommonFlagHiddenChoicesAndCrossBookLinks() {
        val dao = db.highlightTagDao
        val local = HighlightTag(id = "backup-character", name = "张三", bookUrl = first.bookUrl)
        dao.saveTag(local)
        dao.saveBookLabels(second.bookUrl, listOf(HighlightTagOrder(second.bookUrl, local.id, 0, true)))
        dao.upsertLinks(listOf(HighlightTagLink(second.time, local.id)))
        val tags = dao.all()
        val links = dao.links()
        val orders = dao.orders()
        val gson = io.legado.app.utils.GSON
        dao.replaceDefinitions(emptyList())
        repeat(2) {
            dao.restore(
                gson.fromJson(gson.toJson(tags), Array<HighlightTag>::class.java).toList(),
                gson.fromJson(gson.toJson(links), Array<HighlightTagLink>::class.java).toList(),
                listOf(first.time, second.time),
                gson.fromJson(gson.toJson(orders), Array<HighlightTagOrder>::class.java).toList())
        }
        assertEquals(tags, dao.all())
        assertEquals(links, dao.links())
        assertEquals(orders, dao.orders())
        assertFalse(dao.forBook(second.bookUrl).any { it.id == local.id })
        assertTrue(dao.forBook(second.bookUrl, true).any { it.id == local.id })
    }

    @Test fun upgrade119KeepsLegacyIdsLinksOrdersAndAssignsOnlySharedLabelsAsCommon() {
        val name = "tag-library-migration"
        context.deleteDatabase(name)
        migrations.createDatabase(name, 119).apply {
            execSQL("""INSERT INTO highlight_tags (id,name,style,sortOrder,bookUrl,note)
                VALUES ('shared','雷','{}',0,'','共享说明'),('local','张三','{}',1,'book','人物说明')""")
            execSQL("INSERT INTO highlight_tag_orders VALUES ('book','local',0),('book','shared',1)")
            execSQL("""INSERT INTO highlights
                (time,bookUrl,chapterUrl,bookName,bookAuthor,chapterIndex,chapterPos,chapterPosEnd,
                layoutTitleLength,chapterName,bookText,style,note)
                VALUES (123,'book','chapter','Book','Author',2,5,8,0,'Chapter','abc','{"fill":19}','旧笔记')""")
            execSQL("INSERT INTO highlight_tag_links VALUES (123,'local'),(123,'shared')")
            close()
        }
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*DatabaseMigrations.migrations).build()
        try {
            val dao = upgraded.highlightTagDao
            assertTrue(dao.all().first { it.id == "shared" }.common)
            assertFalse(dao.all().first { it.id == "local" }.common)
            assertEquals(listOf("local", "shared"), dao.forBook("book").map { it.id })
            assertTrue(dao.orders().none { it.hidden })
            assertEquals(setOf("local", "shared"), dao.idsForHighlight(123).toSet())
            assertEquals("人物说明", dao.all().first { it.id == "local" }.note)
            assertEquals("旧笔记", upgraded.bookHighlightDao.all.single().note)
            assertEquals(19, upgraded.bookHighlightDao.all.single().styleObj().fill)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

    @Test fun freshSchemaSeedsDefaultLabelsAsCommon() {
        db.highlightTagDao.replaceDefinitions(emptyList())
        HighlightTags.seedDefaults(db.openHelper.writableDatabase)
        assertEquals(HighlightTags.defaults(), db.highlightTagDao.all())
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

    @Test fun upgrade116AddsEmptyTagNotesAndKeepsScopesLinksOrdersAndReadingData() {
        val name = "highlight-tag-note-migration"
        context.deleteDatabase(name)
        migrations.createDatabase(name, 116).apply {
            execSQL("""INSERT INTO highlights
                (time,bookUrl,chapterUrl,bookName,bookAuthor,chapterIndex,chapterPos,chapterPosEnd,
                layoutTitleLength,chapterName,bookText,style,note)
                VALUES (123,'book','chapter','Book','Author',2,5,8,0,'Chapter','abc','{"fill":19}','Old note')""")
            execSQL("""INSERT INTO highlight_tags (id,name,style,sortOrder,bookUrl)
                VALUES ('shared','金句','{"bold":true}',4,''),('local','伏笔','{}',1,'book')""")
            execSQL("INSERT INTO highlight_tag_links VALUES (123,'shared'),(123,'local')")
            execSQL("INSERT INTO highlight_tag_orders VALUES ('book','local',0),('book','shared',1)")
            execSQL("""INSERT INTO bookmarks
                (time,bookName,bookAuthor,chapterIndex,chapterPos,chapterName,bookText,content)
                VALUES (1,'Book','Author',2,5,'Chapter','abc','Bookmark')""")
            execSQL("""INSERT INTO readRecord(deviceId,bookName,author,readTime,lastRead,lastChapterIndex,lastChapterPos)
                VALUES ('device-a','Book','Author',200,100,2,5),('device-b','Book','Author',300,200,3,9)""")
            execSQL("""INSERT INTO readRecordDaily(deviceId,bookName,author,date,readTime)
                VALUES ('device-a','Book','Author','2026-10-17',120)""")
            close()
        }
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*DatabaseMigrations.migrations).build()
        try {
            assertTrue(upgraded.highlightTagDao.all().all { it.note.isEmpty() })
            assertEquals(listOf("local", "shared"), upgraded.highlightTagDao.forBook("book").map { it.id })
            assertEquals(setOf("local", "shared"), upgraded.highlightTagDao.idsForHighlight(123).toSet())
            val local = upgraded.highlightTagDao.all().first { it.id == "local" }
            upgraded.highlightTagDao.saveTag(local.copy(note = "升级后填写\n多行备注"))
            assertEquals("升级后填写\n多行备注", upgraded.highlightTagDao.forBook("book").first().note)
            assertEquals("Old note", upgraded.bookHighlightDao.all.single().note)
            assertEquals(19, upgraded.bookHighlightDao.all.single().styleObj().fill)
            upgraded.openHelper.readableDatabase.query("SELECT count(*) FROM bookmarks").use {
                it.moveToFirst(); assertEquals(1, it.getInt(0))
            }
            upgraded.openHelper.readableDatabase.query(
                "SELECT count(*),sum(readTime),sum(lastChapterPos) FROM readRecord").use {
                it.moveToFirst()
                assertEquals(2, it.getInt(0)); assertEquals(500, it.getInt(1)); assertEquals(14, it.getInt(2))
            }
            upgraded.openHelper.readableDatabase.query("SELECT sum(readTime) FROM readRecordDaily").use {
                it.moveToFirst(); assertEquals(120, it.getInt(0))
            }
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

    @Test fun restoringLegacyTagsDefaultsMissingOrNullNotesAndKeepsStableLinks() {
        val tags = io.legado.app.utils.GSON.fromJson(
            """[{"id":"old","name":"伏笔","style":"{}","sortOrder":0},
                {"id":"null","name":"金句","style":"{}","sortOrder":1,"note":null}]""",
            Array<HighlightTag>::class.java).toList()
        repeat(2) {
            db.highlightTagDao.restore(tags, listOf(HighlightTagLink(first.time, "old")),
                listOf(first.time), emptyList())
        }
        assertTrue(db.highlightTagDao.all().all { it.note.isEmpty() })
        assertEquals(listOf("old"), db.highlightTagDao.idsForHighlight(first.time))
        assertEquals(first, db.bookHighlightDao.all.first { it.time == first.time })
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
