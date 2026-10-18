package io.legado.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadingMarkRollbackTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun preFeature118UpgradesWithoutChangingExistingData() = verify(118)

    @Test fun installedFeature119OpensWithoutDowngradeOrDataLoss() = verify(119)

    private fun verify(version: Int) {
        val name = "rollback-${UUID.randomUUID()}"
        try {
            val before = helper.createDatabase(name, version).let { db ->
                db.execSQL("""insert into books (bookUrl, name, author, durChapterIndex, durChapterPos)
                    values ('rollback-book', 'Book', 'Author', 8, 345)""")
                db.execSQL("""insert into bookmarks
                    (time, bookName, bookAuthor, chapterIndex, chapterPos, chapterName, bookText, content)
                    values (123, 'Book', 'Author', 2, 8, 'Chapter', 'Text', 'Note')""")
                db.execSQL("""insert into highlights
                    (time, bookUrl, chapterUrl, bookName, bookAuthor, chapterIndex, chapterPos,
                    chapterPosEnd, chapterName, bookText, style, note)
                    values (124, 'rollback-book', 'c2', 'Book', 'Author', 2, 8, 18, 'Chapter', 'Text', '', 'Highlight')""")
                db.execSQL("""insert into book_reviews
                    (id, bookName, bookAuthor, bookUrl, rating, content, reviewDate, isFollowUp, createdAt, updatedAt)
                    values ('review', 'Book', 'Author', 'rollback-book', 9, 'Review', '2026-10-18', 0, 100, 100)""")
                for (device in listOf("phone", "tablet")) {
                    db.execSQL("""insert into readRecord
                        (deviceId, bookName, author, readTime, lastRead, lastChapterTitle, lastChapterIndex, lastChapterPos)
                        values ('$device', 'Book', 'Author', 300, 400, 'Chapter 9', 8, 345)""")
                    db.execSQL("""insert into readRecordDaily (deviceId, bookName, author, date, readTime)
                        values ('$device', 'Book', 'Author', '2026-10-18', 300)""")
                    db.execSQL("""insert into readRecordHourly (deviceId, bookName, author, date, hour, readTime)
                        values ('$device', 'Book', 'Author', '2026-10-18', 12, 300)""")
                    db.execSQL("""insert into readRecordHistory (deviceId, bookName, author, readTime)
                        values ('$device', 'Book', 'Author', 200)""")
                    db.execSQL("insert into readStatisticsState (deviceId, startedAt) values ('$device', 100)")
                }
                if (version == 119) {
                    // Preserve the withdrawn feature's raw archive without exposing it in the app.
                    db.execSQL("""insert into book_reading_marks
                        (bookName, bookAuthor, status, date, position, reason, skipped, updatedAt)
                        values ('Book', 'Author', 'finished', '2026-10-18', '{"index":8,"pos":345}',
                        'Original reason', '[{"index":2}]', 100)""")
                }
                val snapshot = snapshot(db)
                db.close()
                snapshot
            }
            repeat(2) {
                val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
                    .addMigrations(*DatabaseMigrations.migrations).build()
                try {
                    val db = room.openHelper.readableDatabase
                    assertEquals(119, db.version)
                    val after = snapshot(db)
                    assertEquals(before.filterKeys { it != "book_reading_marks" },
                        after.filterKeys { it != "book_reading_marks" })
                    if (version == 119) assertEquals(before["book_reading_marks"], after["book_reading_marks"])
                    else assertTrue(after.getValue("book_reading_marks").isEmpty())
                    assertEquals(345, room.bookDao.getBook("rollback-book")?.durChapterPos)
                    assertEquals(9, room.bookReviewDao.get("review")?.rating)
                    assertEquals(600, room.readRecordDao.allTime)
                } finally { room.close() }
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun snapshot(db: SupportSQLiteDatabase): Map<String, List<List<String?>>> {
        val names = db.query("""select name from sqlite_master where type = 'table'
            and name not like 'sqlite_%' and name not in ('room_master_table', 'android_metadata') order by name""").use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        return names.associateWith { table ->
            db.query("select * from `$table` order by rowid").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                        if (cursor.isNull(index)) null else cursor.getString(index)
                    })
                }
            }
        }
    }
}
