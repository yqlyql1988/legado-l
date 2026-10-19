package io.legado.app.help.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadingNoteLocation
import io.legado.app.help.ReadingNotes
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
class ReadingNoteBackupRestoreTest {
    @Test fun optionalExtensionsRoundTripAndOldOrDamagedBackupsKeepBaseNotes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.defaultSharedPreferences
        val savedLatest = preferences.all[PreferKey.onlyLatestBackup]
        val savedLocal = LocalConfig.lastBackup
        val savedIgnore = HashMap(BackupConfig.ignoreConfig)
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "reading-notes-$id")
        val highlight = BookHighlight(bookUrl = "notes-backup-$id", bookName = "Book $id",
            chapterUrl = "old/chapter", chapterIndex = 2, chapterPos = 14, chapterPosEnd = 19,
            bookText = "Quote", style = """{"fill":123}""", note = "Keep note")
        val bookmark = Bookmark(time = highlight.time + 1, bookName = highlight.bookName,
            chapterIndex = 2, chapterPos = 14, bookText = "Quote", content = "Keep memo")
        val locations = listOf(ReadingNotes.legacy(highlight).copy(prefix = "Before", suffix = "After"),
            ReadingNotes.legacy(bookmark, highlight.bookUrl).copy(prefix = "Before", suffix = "After"))
        val unpacked = File(root, "unpacked").apply { mkdirs() }
        fun restore() = runBlocking(Dispatchers.IO) { Restore.restoreLocked(unpacked.path) }
        fun assertNotes() {
            assertEquals(highlight, appDb.bookHighlightDao.getByBook(highlight.bookUrl).single())
            assertEquals(bookmark, appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor).single())
            assertEquals(locations.toSet(), appDb.readingNoteDao.locationsForBook(highlight.bookUrl).toSet())
        }
        try {
            preferences.edit().putBoolean(PreferKey.onlyLatestBackup, true).commit()
            BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = true }
            BackupConfig.ignoreConfig[BackupConfig.annotationContentKey] = false
            appDb.bookHighlightDao.insert(highlight)
            appDb.bookmarkDao.insert(bookmark)
            appDb.readingNoteDao.saveLocations(locations)
            runBlocking(Dispatchers.IO) { Backup.backupLocked(context, root.path, uploadWebDav = false) }
            ZipFile(File(root, "backup.zip")).use { zip ->
                assertNull(zip.getEntry("bookshelf.json"))
                for (name in listOf("highlight.json", "bookmark.json", ReadingNotes.LOCATION_FILE, ReadingNotes.SOURCE_FILE)) {
                    File(unpacked, name).writeBytes(zip.getInputStream(requireNotNull(zip.getEntry(name))).use { it.readBytes() })
                }
                // The same legacy types still read the two original files without any extension.
                assertTrue(GSON.fromJson(File(unpacked, "highlight.json").readText(), Array<BookHighlight>::class.java)
                    .any { it == highlight })
                assertTrue(GSON.fromJson(File(unpacked, "bookmark.json").readText(), Array<Bookmark>::class.java)
                    .any { it == bookmark })
                File(unpacked, "highlight.json").writeText(GSON.toJson(listOf(highlight)))
                File(unpacked, "bookmark.json").writeText(GSON.toJson(listOf(bookmark)))
                File(unpacked, ReadingNotes.LOCATION_FILE).writeText(GSON.toJson(locations))
                File(unpacked, ReadingNotes.SOURCE_FILE).writeText("[]")
            }
            appDb.bookHighlightDao.delete(highlight)
            appDb.bookmarkDao.delete(bookmark)
            appDb.readingNoteDao.deleteLocations("highlight", listOf(highlight.time))
            appDb.readingNoteDao.deleteLocations("bookmark", listOf(bookmark.time))
            repeat(2) { restore() }
            assertNotes()

            File(unpacked, ReadingNotes.LOCATION_FILE).delete()
            File(unpacked, ReadingNotes.SOURCE_FILE).delete()
            repeat(2) { restore() }
            assertNotes()

            File(unpacked, ReadingNotes.LOCATION_FILE).writeText("{incomplete")
            restore()
            assertNotes()
            File(unpacked, ReadingNotes.LOCATION_FILE).writeText(GSON.toJson(listOf(
                ReadingNoteLocation(noteTime = highlight.time, bookUrl = "bad", chapterPos = -1))))
            restore()
            assertNotes()
        } finally {
            appDb.bookHighlightDao.delete(highlight)
            appDb.bookmarkDao.delete(bookmark)
            appDb.readingNoteDao.deleteLocations("highlight", listOf(highlight.time))
            appDb.readingNoteDao.deleteLocations("bookmark", listOf(bookmark.time))
            BackupConfig.ignoreConfig.clear()
            BackupConfig.ignoreConfig.putAll(savedIgnore)
            LocalConfig.lastBackup = savedLocal
            preferences.edit().apply {
                if (savedLatest is Boolean) putBoolean(PreferKey.onlyLatestBackup, savedLatest)
                else remove(PreferKey.onlyLatestBackup)
            }.commit()
            root.deleteRecursively()
        }
    }
}
