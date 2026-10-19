package io.legado.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadHourlyDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration117PreservesRecordsDaysHistoryBookmarksAndAnnotations() {
        val name = "hourly-migration-${UUID.randomUUID()}"
        try {
            helper.createDatabase(name, 117).use {
                it.execSQL("INSERT INTO readRecord(deviceId,bookName,author,readTime,lastRead) VALUES('phone','Book','A',1000,100)")
                it.execSQL("INSERT INTO readRecordDaily VALUES('phone','Book','A','2026-10-18',200)")
                it.execSQL("INSERT INTO readRecordHistory VALUES('phone','Book','A',800)")
                it.execSQL("INSERT INTO readStatisticsState VALUES('phone',100)")
            }
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(*DatabaseMigrations.migrations).build()
            try {
                assertEquals(123, db.openHelper.writableDatabase.version)
                assertEquals(1000L, db.readRecordDao.all.single().readTime)
                assertEquals(200L, db.readRecordDao.allDaily().single().readTime)
                assertEquals(800L, db.readRecordDao.allHistory().single().readTime)
                assertEquals(100L, db.readRecordDao.statisticsStates().single().startedAt)
                assertTrue(db.readRecordDao.allHourly().isEmpty())
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun frequentSaverVisitsShareOneCounterAndQueuedWritesKeepTheirClock() {
        val dao = appDb.readRecordDao
        val name = "Hourly saver ${UUID.randomUUID()}"
        val day = LocalDate.now()
        val end = day.atTime(10, 0, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val record = ReadRecord(AppConst.androidId, name, "A", 0, end)
        try {
            val queued = record.copy().prepareSaveWithCover(null, 60_000)
            repeat(20) { index ->
                record.copy(lastRead = end + index * 1000).saveWithCover(null, 1000)
            }
            queued()
            val rows = dao.getHourly(record.deviceId, name, "A")
            assertEquals(2, rows.size)
            assertEquals(mapOf(9 to 30_000L, 10 to 50_000L), rows.associate { it.hour to it.readTime })
            assertEquals(80_000L, dao.getDaily(record.deviceId, name, "A").single().readTime)
            assertEquals(80_000L, dao.getRecord(record.deviceId, name, "A")!!.readTime)
            assertTrue(rows.all { it.date == day.toString() })
        } finally { dao.deleteByBook(name, "A") }
    }

    @Test fun joinsRenameAndBatchDeleteKeepBookIdentitiesAndOtherUserData() = withDatabase { db ->
        val dao = db.readRecordDao
        val records = listOf(ReadRecord("phone", "Same", "", 100, resolvedAuthor = "A"),
            ReadRecord("tablet", "Same", "A", 200), ReadRecord("phone", "Same", "B", 300),
            ReadRecord("phone", "Destination", "C", 400))
        dao.insert(*records.toTypedArray())
        records.forEach {
            dao.insertHourly(ReadRecordHourly(it.deviceId, it.bookName, it.author, "2026-10-18", 9, it.readTime))
            dao.insertDaily(ReadRecordDaily(it.deviceId, it.bookName, it.author, "2026-10-18", it.readTime))
            dao.insertHistory(ReadRecordHistory(it.deviceId, it.bookName, it.author, 0))
        }
        val book = Book(bookUrl = "hourly-test", name = "Same", author = "A")
        val bookmark = Bookmark(time = 1, bookName = book.name, bookAuthor = book.author, content = "Keep")
        val highlight = BookHighlight(time = 2, bookUrl = book.bookUrl,
            bookName = book.name, bookAuthor = book.author, note = "Keep")
        db.bookDao.insert(book)
        db.bookmarkDao.insert(bookmark)
        db.bookHighlightDao.insert(highlight)
        assertEquals(mapOf("A" to 300L, "B" to 300L, "C" to 400L),
            dao.hourlyShow().associate { it.author to it.readTime })
        dao.updateBookMetadata("Same", "A", "Destination", "C")
        assertEquals(700L, dao.hourlyShow().single { it.author == "C" }.readTime)
        assertEquals(2, dao.getRecords("Destination", "C").size)
        dao.deleteBooks(listOf(ReadRecordBook("Destination", "C"), ReadRecordBook("Destination", "C")))
        assertEquals("B", dao.all.single().author)
        assertEquals("B", dao.allDaily().single().author)
        assertEquals("B", dao.allHourly().single().author)
        assertEquals("B", dao.allHistory().single().author)
        assertEquals(book, db.bookDao.getBook(book.bookUrl))
        assertEquals(listOf(bookmark), db.bookmarkDao.all)
        assertEquals(listOf(highlight), db.bookHighlightDao.all)
        dao.clear()
        assertTrue(dao.allHourly().isEmpty())
        assertTrue(dao.allDaily().isEmpty())
        assertTrue(dao.allHistory().isEmpty())
    }

    @Test fun legacyCombinedAuthorMovesItsHourlyRowsWithoutDividingTime() = withDatabase { db ->
        val dao = db.readRecordDao
        val authors = "\u001Eauthors:[\"A\",\"B\"]"
        dao.insert(ReadRecord("phone", "Book", authors, 1000))
        dao.insertHourly(ReadRecordHourly("phone", "Book", authors, "2026-10-18", 9, 1000))
        dao.removeLegacyAuthor("Book", authors, "B")
        assertEquals("A", dao.allHourly().single().author)
        assertEquals(1000L, dao.allHourly().single().readTime)
    }

    @Test fun repeatedRestoreValidatesAndMergesHourlyWithoutErasingNewerCounters() = withDatabase { db ->
        val dao = db.readRecordDao
        val record = ReadRecord("phone", "Book", "A", 1000)
        val hourly = ReadRecordHourly("", "Book", "A", "2026-10-18", 9, 200)
        val invalid = listOf(hourly.copy(hour = 24), hourly.copy(date = "2026-02-30"),
            hourly.copy(readTime = -1))
        repeat(3) {
            dao.restoreStatistics(listOf(record), null, null, "phone", hourly = listOf(hourly) + invalid)
            assertEquals(1000L, dao.allTime)
            assertEquals(listOf(hourly.copy(deviceId = "phone")), dao.allHourly())
            assertEquals(200L, dao.allDaily().single().readTime)
        }
        dao.restoreStatistics(listOf(record), null, null, "phone")
        assertEquals(200L, dao.allHourly().single().readTime)
    }

    private fun withDatabase(block: (AppDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }
}
