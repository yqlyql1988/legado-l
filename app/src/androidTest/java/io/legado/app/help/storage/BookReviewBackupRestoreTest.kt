package io.legado.app.help.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookReview
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
class BookReviewBackupRestoreTest {
    @Test fun independentBackupRestoresRemovedBooksAndNeverOverwritesNewerEdits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.defaultSharedPreferences
        val savedLatest = preferences.getBoolean(PreferKey.onlyLatestBackup, false)
        val savedLocal = LocalConfig.lastBackup
        val savedIgnore = HashMap(BackupConfig.ignoreConfig)
        val directory = File(context.cacheDir, "review-backup-${UUID.randomUUID()}")
        val review = BookReview(bookName = "Removed-${UUID.randomUUID()}", bookAuthor = "Author",
            rating = 9, content = "Review for a book no longer on the shelf", reviewDate = "2026-10-15",
            createdAt = 10, updatedAt = 10)
        try {
            preferences.edit().putBoolean(PreferKey.onlyLatestBackup, true).commit()
            BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = true }
            BackupConfig.ignoreConfig[BackupConfig.bookReviewContentKey] = false
            appDb.bookReviewDao.restore(listOf(review))
            runBlocking(Dispatchers.IO) {
                Backup.backupLocked(context, directory.path, uploadWebDav = false)
            }
            val unpacked = File(directory, "unpacked").apply { mkdirs() }
            ZipFile(File(directory, "backup.zip")).use { zip ->
                assertNotNull(zip.getEntry("bookReview.json"))
                assertNull(zip.getEntry("bookshelf.json"))
                assertNull(zip.getEntry("readRecord.json"))
                File(unpacked, "bookReview.json").writeBytes(
                    zip.getInputStream(zip.getEntry("bookReview.json")).use { it.readBytes() })
            }
            // Restore only the fixture to avoid interfering with reviews belonging to the user.
            File(unpacked, "bookReview.json").writeText(GSON.toJson(listOf(review)))
            appDb.openHelper.writableDatabase.execSQL("delete from book_reviews where id = ?", arrayOf(review.id))
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(unpacked.path) }
            assertEquals(review, appDb.bookReviewDao.get(review.id))
            val edited = appDb.bookReviewDao.save(review.copy(content = "Newer local edit"), now = 100)
            runBlocking(Dispatchers.IO) {
                Restore.restoreLocked(unpacked.path)
                Restore.restoreLocked(unpacked.path)
            }
            assertEquals(edited, appDb.bookReviewDao.get(review.id))
            // Older backups have no review file and leave all reviews intact.
            File(unpacked, "bookReview.json").delete()
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(unpacked.path) }
            assertEquals(edited, appDb.bookReviewDao.get(review.id))
        } finally {
            appDb.openHelper.writableDatabase.execSQL("delete from book_reviews where id = ?", arrayOf(review.id))
            BackupConfig.ignoreConfig.clear()
            BackupConfig.ignoreConfig.putAll(savedIgnore)
            preferences.edit().putBoolean(PreferKey.onlyLatestBackup, savedLatest).commit()
            LocalConfig.lastBackup = savedLocal
            directory.deleteRecursively()
        }
    }
}
