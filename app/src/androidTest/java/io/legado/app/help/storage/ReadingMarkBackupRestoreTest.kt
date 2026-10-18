package io.legado.app.help.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookReadingMark
import io.legado.app.data.entities.ReadingMarkChapter
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class ReadingMarkBackupRestoreTest {
    @Test fun independentBackupRestoresRemovedBookAndKeepsNewEditsAndClearMarkers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.defaultSharedPreferences
        val savedLatest = preferences.getBoolean(PreferKey.onlyLatestBackup, false)
        val savedLocal = LocalConfig.lastBackup
        val savedIgnore = HashMap(BackupConfig.ignoreConfig)
        val directory = File(context.cacheDir, "reading-mark-backup-${UUID.randomUUID()}")
        val mark = BookReadingMark("Reading-mark-${UUID.randomUUID()}", "Author",
            BookReadingMark.ABANDONED, "2026-10-18",
            ReadingMarkChapter("removed-book", "c8", "第8章", 8, 345), "暂时放下",
            listOf(ReadingMarkChapter("removed-book", "c2", "第2章", 2)), updatedAt = 10)
        try {
            preferences.edit().putBoolean(PreferKey.onlyLatestBackup, true).commit()
            BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = true }
            BackupConfig.ignoreConfig[BackupConfig.readingMarkContentKey] = false
            appDb.bookReadingMarkDao.restore(listOf(mark))
            runBlocking(Dispatchers.IO) { Backup.backupLocked(context, directory.path, uploadWebDav = false) }
            val unpacked = File(directory, "unpacked").apply { mkdirs() }
            ZipFile(File(directory, "backup.zip")).use { zip ->
                assertNotNull(zip.getEntry("bookReadingMark.json"))
                assertNull(zip.getEntry("bookshelf.json"))
                assertNull(zip.getEntry("readRecord.json"))
            }
            File(unpacked, "bookReadingMark.json").writeText(GSON.toJson(listOf(mark)))
            appDb.openHelper.writableDatabase.execSQL(
                "delete from book_reading_marks where bookName = ?", arrayOf(mark.bookName))
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(unpacked.path) }
            assertEquals(mark, appDb.bookReadingMarkDao.get(mark.bookName, mark.bookAuthor))
            val edited = appDb.bookReadingMarkDao.save(mark.copy(reason = "新的原因"), 100)
            repeat(2) { runBlocking(Dispatchers.IO) { Restore.restoreLocked(unpacked.path) } }
            assertEquals(edited, appDb.bookReadingMarkDao.get(mark.bookName, mark.bookAuthor))
            val cleared = appDb.bookReadingMarkDao.save(
                BookReadingMark(mark.bookName, mark.bookAuthor, updatedAt = edited.updatedAt), 200)
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(unpacked.path) }
            assertEquals(cleared, appDb.bookReadingMarkDao.get(mark.bookName, mark.bookAuthor))
            File(unpacked, "bookReadingMark.json").delete()
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(unpacked.path) }
            assertEquals(cleared, appDb.bookReadingMarkDao.get(mark.bookName, mark.bookAuthor))
        } finally {
            appDb.openHelper.writableDatabase.execSQL(
                "delete from book_reading_marks where bookName = ?", arrayOf(mark.bookName))
            BackupConfig.ignoreConfig.clear()
            BackupConfig.ignoreConfig.putAll(savedIgnore)
            preferences.edit().putBoolean(PreferKey.onlyLatestBackup, savedLatest).commit()
            LocalConfig.lastBackup = savedLocal
            directory.deleteRecursively()
        }
    }
}
