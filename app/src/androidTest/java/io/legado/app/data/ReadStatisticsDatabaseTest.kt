package io.legado.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordDaily
import io.legado.app.data.entities.ReadRecordHistory
import io.legado.app.data.entities.ReadStatisticsState
import io.legado.app.data.entities.prepareSaveWithCover
import io.legado.app.data.entities.saveWithCover
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class ReadStatisticsDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration115AddsEmptyDetailsWithoutChangingAnyOldRecord() {
        val name = "statistics-migration-${UUID.randomUUID()}"
        val record = ReadRecord("old-phone", "保留旧记录", "", 3600000, 1234,
            "保留的章节", 8, 19, "/old/cover", "作者")
        try {
            helper.createDatabase(name, 115).use {
                it.execSQL("INSERT INTO readRecord VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(record.deviceId, record.bookName, record.author, record.readTime,
                        record.lastRead, record.lastChapterTitle, record.lastChapterIndex,
                        record.lastChapterPos, record.coverUrl, record.resolvedAuthor))
            }
            helper.runMigrationsAndValidate(name, 116, true, *DatabaseMigrations.migrations).close()
            val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(*DatabaseMigrations.migrations).build()
            try {
                assertEquals(listOf(record), database.readRecordDao.all)
                assertTrue(database.readRecordDao.allDaily().isEmpty())
                assertEquals(record.readTime, database.readRecordDao.allTime)
                assertEquals(record.readTime, database.readRecordDao.getHistory(record.deviceId, record.bookName, record.author))
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun sameTitleAuthorsDevicesAndResolvedUnknownJoinTheRightDailyRows() = withDatabase { database ->
        val dao = database.readRecordDao
        dao.insert(ReadRecord("phone", "Same", "", 100, resolvedAuthor = "A"),
            ReadRecord("phone", "Same", "A", 200), ReadRecord("tablet", "Same", "A", 300),
            ReadRecord("phone", "Same", "B", 400))
        dao.insertDaily(ReadRecordDaily("phone", "Same", "", "2026-10-17", 10),
            ReadRecordDaily("phone", "Same", "A", "2026-10-17", 20),
            ReadRecordDaily("tablet", "Same", "A", "2026-10-17", 30),
            ReadRecordDaily("phone", "Same", "B", "2026-10-17", 40))
        assertEquals(mapOf("A" to 60L, "B" to 40L), dao.dailyShow().associate { it.author to it.readTime })
        dao.deleteByBook("Same", "A")
        assertEquals("B", dao.all.single().author)
        assertEquals(40L, dao.allDaily().single().readTime)
        dao.clear()
        assertTrue(dao.allDaily().isEmpty())
        assertTrue(dao.all.isEmpty())
    }

    @Test fun metadataMergeMovesAllDevicesAndSumsDistinctAccounts() = withDatabase { database ->
        val dao = database.readRecordDao
        dao.insert(ReadRecord("phone", "Old", "", 100, resolvedAuthor = "A"),
            ReadRecord("phone", "New", "B", 200), ReadRecord("tablet", "Old", "A", 300))
        dao.insertDaily(ReadRecordDaily("phone", "Old", "", "2026-10-17", 10),
            ReadRecordDaily("phone", "New", "B", "2026-10-17", 20),
            ReadRecordDaily("tablet", "Old", "A", "2026-10-17", 30))
        dao.updateBookMetadata("Old", "A", "New", "B")
        assertEquals(600L, dao.allTime)
        assertEquals(60L, dao.dailyShow().single().readTime)
        assertEquals(2, dao.allDaily().size)
        assertTrue(dao.allDaily().all { it.bookName == "New" && it.author == "B" })
        dao.deleteByBook("New", "B")
        assertTrue(dao.allDaily().isEmpty())
    }

    @Test fun legacyAuthorLabelRemovalPreservesDailyDuration() = withDatabase { database ->
        val dao = database.readRecordDao
        val author = "\u001Eauthors:[\"A\",\"B\"]"
        dao.insert(ReadRecord("phone", "Book", author, 1000))
        dao.insertDaily(ReadRecordDaily("phone", "Book", author, "2026-10-17", 100))
        dao.removeLegacyAuthor("Book", author, "B")
        assertEquals("A", dao.all.single().author)
        assertEquals("A", dao.allDaily().single().author)
        assertEquals(100L, dao.dailyShow().single().readTime)
    }

    @Test fun dailyAndHistoricalCountersRestoreAtomicallyAndIdempotently() = withDatabase { database ->
        val dao = database.readRecordDao
        val original = ReadRecord("phone", "Book", "A", 1200, 100, "Old chapter", 2)
        dao.insert(original)
        dao.insertHistory(ReadRecordHistory("phone", "Book", "A", 1000))
        dao.insertDaily(ReadRecordDaily("phone", "Book", "A", "2026-10-16", 200))
        val incoming = original.copy(readTime = 1300, lastRead = 200, lastChapterIndex = 9)
        val details = listOf(ReadRecordDaily("phone", "Book", "A", "2026-10-17", 300))
        repeat(3) {
            dao.restoreStatistics(listOf(incoming), details, listOf(ReadStatisticsState("phone", 100)), "phone",
                listOf(ReadRecordHistory("phone", "Book", "A", 1000)))
            assertEquals(1500L, dao.allTime)
            assertEquals(500L, dao.allDaily().sumOf { it.readTime })
            assertEquals(9, dao.all.single().lastChapterIndex)
            assertEquals(100L, dao.statisticsStates().single().startedAt)
        }
    }

    @Test fun realSaverCapturesDatesBeforeQueueAndKeepsLatestSnapshot() {
        val dao = appDb.readRecordDao
        val name = "Statistics saver ${UUID.randomUUID()}"
        val end = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 60_000
        val original = ReadRecord(AppConst.androidId, name, "A", 1000, end - 60_000, "Original", 1)
        try {
            dao.insert(original)
            val queued = original.copy(lastRead = end, lastChapterIndex = 2).prepareSaveWithCover(null, 120_000)
            original.copy(lastRead = end + 120_000, lastChapterIndex = 8).saveWithCover(null, 60_000)
            queued()
            assertEquals(181_000L, dao.getRecord(original.deviceId, name, "A")!!.readTime)
            assertEquals(8, dao.getRecord(original.deviceId, name, "A")!!.lastChapterIndex)
            assertEquals(mapOf(LocalDate.now().minusDays(1).toString() to 60_000L,
                LocalDate.now().toString() to 120_000L),
                dao.getDaily(original.deviceId, name, "A").associate { it.date to it.readTime })
        } finally { dao.deleteByBook(name, "A") }
    }

    @Test fun actualJsonRestoreRetainsOldOnlyHistoryAndNewDetailsOnRepeat() {
        val dao = appDb.readRecordDao
        val name = "Statistics restore ${UUID.randomUUID()}"
        val directory = File(context.cacheDir, "statistics-${UUID.randomUUID()}").apply { mkdirs() }
        val record = ReadRecord(AppConst.androidId, name, "A", 1000, 100, "Saved", 7)
        val daily = ReadRecordDaily(record.deviceId, name, "A", "2026-10-17", 200)
        fun restore() = runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.absolutePath, lanTransfer = true) }
        try {
            File(directory, "readRecord.json").writeText(GSON.toJson(listOf(record)))
            restore()
            assertTrue(dao.getDaily(record.deviceId, name, "A").isEmpty())
            File(directory, "readRecordDaily.json").writeText(GSON.toJson(listOf(daily)))
            repeat(2) {
                restore()
                assertEquals(record, dao.getRecord(record.deviceId, name, "A"))
                assertEquals(listOf(daily), dao.getDaily(record.deviceId, name, "A"))
            }
            // A backup with no detail file remains compatible and does not erase known days.
            File(directory, "readRecordDaily.json").delete()
            restore()
            assertEquals(1000L, dao.getRecord(record.deviceId, name, "A")!!.readTime)
            assertEquals(listOf(daily), dao.getDaily(record.deviceId, name, "A"))
        } finally {
            dao.deleteByBook(name, "A")
            directory.deleteRecursively()
        }
    }

    @Test fun selectedHistoryBackupIncludesCompleteDailySnapshotAndStartDate() {
        val prefs = context.defaultSharedPreferences
        val oldLatest = prefs.getBoolean(PreferKey.onlyLatestBackup, false)
        val oldBackup = LocalConfig.lastBackup
        val oldIgnore = HashMap(BackupConfig.ignoreConfig)
        val dao = appDb.readRecordDao
        val name = "Statistics archive ${UUID.randomUUID()}"
        val record = ReadRecord(AppConst.androidId, name, "A", 1000, 100, "Chapter", 3)
        val detail = ReadRecordDaily(record.deviceId, name, "A", "2026-10-17", 200)
        val directory = File(context.cacheDir, "statistics-archive-${UUID.randomUUID()}")
        try {
            dao.insert(record)
            dao.insertDaily(detail)
            dao.insertHistory(ReadRecordHistory(record.deviceId, name, "A", 800))
            prefs.edit().putBoolean(PreferKey.onlyLatestBackup, true).commit()
            BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = true }
            BackupConfig.ignoreConfig[BackupConfig.historyContentKey] = false
            runBlocking(Dispatchers.IO) { Backup.backupLocked(context, directory.path, uploadWebDav = false) }
            ZipFile(File(directory, "backup.zip")).use { zip ->
                fun json(name: String) = zip.getInputStream(checkNotNull(zip.getEntry(name)))
                    .bufferedReader().use { it.readText() }
                assertEquals(record, GSON.fromJson(json("readRecord.json"), Array<ReadRecord>::class.java)
                    .single { it.bookName == name })
                assertEquals(detail, GSON.fromJson(json("readRecordDaily.json"), Array<ReadRecordDaily>::class.java)
                    .single { it.bookName == name })
                assertEquals(800L, GSON.fromJson(json("readRecordHistory.json"), Array<ReadRecordHistory>::class.java)
                    .single { it.bookName == name }.readTime)
                assertTrue(GSON.fromJson(json("readStatisticsState.json"), Array<ReadStatisticsState>::class.java)
                    .any { it.deviceId == record.deviceId && it.startedAt > 0 })
                assertNull(zip.getEntry("bookshelf.json"))
            }
        } finally {
            dao.deleteByBook(name, "A")
            BackupConfig.ignoreConfig.clear()
            BackupConfig.ignoreConfig.putAll(oldIgnore)
            prefs.edit().putBoolean(PreferKey.onlyLatestBackup, oldLatest).commit()
            LocalConfig.lastBackup = oldBackup
            directory.deleteRecursively()
        }
    }

    private fun withDatabase(block: (AppDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try { block(database) } finally { database.close() }
    }
}
