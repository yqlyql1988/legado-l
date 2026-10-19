package io.legado.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordBook
import io.legado.app.data.entities.ReadRecordDaily
import io.legado.app.data.entities.ReadRecordHistory
import io.legado.app.data.entities.ReadRecordHourly
import io.legado.app.help.book.ReadDayDuration
import io.legado.app.help.book.ReadHourDuration
import io.legado.app.help.book.ReadRecordDeleteScope
import io.legado.app.help.book.ReadRecordTimeRange
import io.legado.app.utils.GSON
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadRecordDeletionDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory())
    private val books = listOf(ReadRecordBook("Book", "A"))
    private fun scope(range: ReadRecordTimeRange, hour: Int = 0) =
        ReadRecordDeleteScope(range, LocalDate.of(2026, 1, 18), hour)

    private fun withDatabase(test: (AppDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try { test(db) } finally { db.close() }
    }

    private fun seed(db: AppDatabase) {
        val dao = db.readRecordDao
        dao.insert(ReadRecord("phone", "Book", "A", 900, 123, "Chapter", 8, 19, "/cover"),
            ReadRecord("tablet", "Book", "A", 600, 122, "Tablet chapter", 7),
            ReadRecord("phone", "Book", "", 100, resolvedAuthor = "A"),
            ReadRecord("phone", "Book", "B", 800), ReadRecord("phone", "Other", "A", 700))
        dao.insertHistory(ReadRecordHistory("phone", "Book", "A", 500),
            ReadRecordHistory("tablet", "Book", "A", 200), ReadRecordHistory("phone", "Book", "", 50))
        dao.insertDaily(ReadRecordDaily("phone", "Book", "A", "2026-01-15", 100),
            ReadRecordDaily("phone", "Book", "A", "2026-01-18", 200),
            ReadRecordDaily("phone", "Book", "A", "2026-02-01", 100),
            ReadRecordDaily("tablet", "Book", "A", "2026-01-18", 400),
            ReadRecordDaily("phone", "Book", "", "2026-01-18", 50),
            ReadRecordDaily("phone", "Book", "B", "2026-01-18", 800))
        dao.insertHourly(ReadRecordHourly("phone", "Book", "A", "2026-01-15", 9, 100),
            ReadRecordHourly("phone", "Book", "A", "2026-01-18", 10, 80),
            ReadRecordHourly("phone", "Book", "A", "2026-01-18", 20, 120),
            ReadRecordHourly("tablet", "Book", "A", "2026-01-18", 10, 400),
            ReadRecordHourly("phone", "Book", "", "2026-01-18", 10, 50))
    }

    @Test fun monthDeletesOnlySelectedIdentityAcrossDevicesAndResolvedAuthors() = withDatabase { db ->
        seed(db)
        val dao = db.readRecordDao
        val before = dao.getRecord("phone", "Book", "A")!!
        assertEquals(750L, dao.deleteBooksInPeriod(books + books, scope(ReadRecordTimeRange.MONTH)))
        assertEquals(before.copy(readTime = 600), dao.getRecord("phone", "Book", "A"))
        assertEquals(850L, dao.allShow.single { it.bookName == "Book" && it.author == "A" }.readTime)
        assertEquals(100L, dao.dailyShow().filter { it.author == "A" }.sumOf { it.readTime })
        assertFalse(dao.hourlyShow().any { it.bookName == "Book" && it.author == "A" })
        assertEquals(800L, dao.getRecord("phone", "Book", "B")!!.readTime)
        assertEquals(700L, dao.getRecord("phone", "Other", "A")!!.readTime)
        assertEquals(500L, dao.getHistory("phone", "Book", "A"))
        assertEquals(0L, dao.deleteBooksInPeriod(books, scope(ReadRecordTimeRange.MONTH)))
    }

    @Test fun hourKeepsOtherHoursDaysAndProgress() = withDatabase { db ->
        seed(db)
        val dao = db.readRecordDao
        assertEquals(530L, dao.deleteBooksInPeriod(books, scope(ReadRecordTimeRange.HOUR, 10)))
        assertEquals(820L, dao.getRecord("phone", "Book", "A")!!.readTime)
        assertEquals(120L, dao.getDailyTime("phone", "Book", "A", "2026-01-18"))
        assertEquals(120L, dao.getHourlyTime("phone", "Book", "A", "2026-01-18", 20))
        assertEquals(100L, dao.getDailyTime("phone", "Book", "A", "2026-01-15"))
        assertEquals(8, dao.getRecord("phone", "Book", "A")!!.lastChapterIndex)
        assertFalse(dao.hourlyShow().any { it.author == "A" && it.hour == 10 })
    }

    @Test fun deletionRollsBackAllBooksWhenOneAccountHasInconsistentTime() = withDatabase { db ->
        seed(db)
        val dao = db.readRecordDao
        dao.insert(ReadRecord("phone", "Broken", "A", 1))
        dao.insertDaily(ReadRecordDaily("phone", "Broken", "A", "2026-01-18", 100))
        val originals = dao.all
        val days = dao.allDaily()
        try {
            dao.deleteBooksInPeriod(books + ReadRecordBook("Broken", "A"), scope(ReadRecordTimeRange.MONTH))
            fail("An inconsistent total must roll back the transaction")
        } catch (_: IllegalStateException) { }
        assertEquals(originals, dao.all)
        assertEquals(days, dao.allDaily())
    }

    @Test fun zeroedAccountsDisappearFromListsWhileBackupKeepsDeletionEvidence() = withDatabase { db ->
        val dao = db.readRecordDao
        dao.insert(ReadRecord("phone", "Book", "A", 100))
        dao.insertDaily(ReadRecordDaily("phone", "Book", "A", "2026-01-18", 100))
        dao.insertHourly(ReadRecordHourly("phone", "Book", "A", "2026-01-18", 10, 100))
        dao.deleteBooksInPeriod(books, scope(ReadRecordTimeRange.DAY))
        assertTrue(dao.allShow.isEmpty())
        assertTrue(dao.search("Book").isEmpty())
        assertTrue(runBlocking { dao.flowBooks().first() }.isEmpty())
        assertTrue(dao.dailyShow().isEmpty())
        assertTrue(dao.hourlyShow().isEmpty())
        assertEquals(100L, dao.allDaily().single().deletedTime)
        assertEquals(0L, dao.all.single().readTime)
    }

    @Test fun continuedReadingRetainsRemovalCountersAndRestoringOldBackupCannotReviveHours() = withDatabase { db ->
        seed(db)
        val dao = db.readRecordDao
        val records = dao.all
        val days = dao.allDaily()
        val hours = dao.allHourly()
        val history = dao.allHistory()
        dao.deleteBooksInPeriod(books, scope(ReadRecordTimeRange.DAY))
        val saved = dao.getRecord("phone", "Book", "A")!!
        dao.addDaily(saved, listOf(ReadDayDuration("2026-01-18", 30)))
        dao.addHourly(saved, listOf(ReadHourDuration("2026-01-18", 15, 30)))
        dao.update(saved.copy(readTime = saved.readTime + 30))
        repeat(2) { dao.restoreStatistics(records, days, null, "phone", history, hours) }
        assertEquals(730L, dao.getRecord("phone", "Book", "A")!!.readTime)
        assertEquals(30L, dao.getDailyTime("phone", "Book", "A", "2026-01-18"))
        assertEquals(listOf(15), dao.hourlyShow().filter {
            it.author == "A" && it.date == "2026-01-18"
        }.map { it.hour })
    }

    @Test fun gsonBackupRoundTripPreservesZeroCountersAndWorksOnFreshDatabase() = withDatabase { original ->
        seed(original)
        val dao = original.readRecordDao
        dao.deleteBooksInPeriod(books, scope(ReadRecordTimeRange.MONTH))
        val records = GSON.fromJson(GSON.toJson(dao.all), Array<ReadRecord>::class.java).toList()
        val daily = GSON.fromJson(GSON.toJson(dao.allDaily()), Array<ReadRecordDaily>::class.java).toList()
        val hourly = GSON.fromJson(GSON.toJson(dao.allHourly()), Array<ReadRecordHourly>::class.java).toList()
        withDatabase { restored ->
            repeat(2) {
                restored.readRecordDao.restoreStatistics(records, daily, null, "new-device", dao.allHistory(), hourly)
            }
            assertEquals(dao.all.toSet(), restored.readRecordDao.all.toSet())
            assertEquals(dao.allDaily().toSet(), restored.readRecordDao.allDaily().toSet())
            assertEquals(dao.allHourly().toSet(), restored.readRecordDao.allHourly().toSet())
        }
    }

    @Test fun metadataRenameKeepsNetTimeAndDeletionEvidence() = withDatabase { db ->
        seed(db)
        val dao = db.readRecordDao
        dao.deleteBooksInPeriod(books, scope(ReadRecordTimeRange.MONTH))
        dao.updateBookMetadata("Book", "A", "Renamed", "Author")
        assertEquals(850L, dao.allShow.single { it.bookName == "Renamed" }.readTime)
        assertEquals(750L, dao.allDaily().filter { it.bookName == "Renamed" }.sumOf { it.deletedTime })
        assertTrue(dao.allHourly().filter { it.bookName == "Renamed" }.all { it.resetTime > 0 })
    }

    @Test fun cumulativeRetainsExistingWholeBookDeletionBehavior() = withDatabase { db ->
        seed(db)
        val dao = db.readRecordDao
        assertEquals(1600L, dao.deleteBooksInPeriod(books, scope(ReadRecordTimeRange.ALL)))
        assertTrue(dao.all.none { it.bookName == "Book" && (it.author == "A" || it.author == "") })
        assertTrue(dao.allDaily().none { it.bookName == "Book" && it.author != "B" })
        assertTrue(dao.allHourly().isEmpty())
        assertTrue(dao.allHistory().isEmpty())
        assertEquals(2, dao.all.size)
    }

    @Test fun migration120PreservesEveryExistingCounterAndSnapshot() {
        val name = "scoped-delete-${UUID.randomUUID()}"
        try {
            migrations.createDatabase(name, 120).use {
                it.execSQL("INSERT INTO readRecord(deviceId,bookName,author,readTime,lastRead,lastChapterIndex) VALUES('phone','Book','A',1234,5678,9)")
                it.execSQL("INSERT INTO readRecordDaily(deviceId,bookName,author,date,readTime) VALUES('phone','Book','A','2026-01-18',234)")
                it.execSQL("INSERT INTO readRecordHourly(deviceId,bookName,author,date,hour,readTime) VALUES('phone','Book','A','2026-01-18',10,234)")
            }
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(*DatabaseMigrations.migrations).build()
            try {
                assertEquals(1234L, db.readRecordDao.all.single().readTime)
                assertEquals(9, db.readRecordDao.all.single().lastChapterIndex)
                assertEquals(ReadRecordDaily("phone", "Book", "A", "2026-01-18", 234), db.readRecordDao.allDaily().single())
                assertEquals(ReadRecordHourly("phone", "Book", "A", "2026-01-18", 10, 234), db.readRecordDao.allHourly().single())
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
