package io.legado.app.help.book

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChapterCacheMigrationTest {

    @Test
    fun renamedChapterKeepsItsDownloadedContent() = withBook { book, chapters ->
        val old = chapters[1]
        BookHelp.saveText(book, old, "chapter-content")
        val oldFile = cacheFile(book, old)
        assertTrue(oldFile.isFile)

        val renamed = old.copy(title = "Chapter 1 renamed")
        val newChapters = listOf(chapters[0], renamed)
        val oldKeys = BookHelp.captureChapterCacheKeys(book)
        replaceChapters(book, newChapters)
        BookHelp.migrateChapterCache(book, oldKeys, newChapters)

        assertFalse("旧文件名应已迁移", oldFile.exists())
        assertTrue(BookHelp.hasContent(book, renamed))
        assertEquals("chapter-content", BookHelp.getContent(book, renamed))
    }

    @Test
    fun shiftedChapterKeepsItsDownloadedContent() = withBook { book, chapters ->
        val old = chapters[1]
        BookHelp.saveText(book, old, "shifted-content")
        val oldFile = cacheFile(book, old)

        val inserted = BookChapter(
            bookUrl = book.bookUrl,
            url = "${book.bookUrl}/inserted",
            title = "Inserted",
            index = 1,
        )
        val moved = old.copy(index = 2)
        val tail = chapters[2].copy(index = 3)
        val newChapters = listOf(chapters[0], inserted, moved, tail)
        val oldKeys = BookHelp.captureChapterCacheKeys(book)
        replaceChapters(book, newChapters)
        BookHelp.migrateChapterCache(book, oldKeys, newChapters)

        assertFalse(oldFile.exists())
        assertTrue(BookHelp.hasContent(book, moved))
        assertEquals("shifted-content", BookHelp.getContent(book, moved))
        assertEquals("shifted-content", BookHelp.getContent(book, moved))
    }

    @Test
    fun sideFilesFollowTheMigratedChapter() = withBook { book, chapters ->
        val old = chapters[1]
        BookHelp.saveText(book, old, "甲乙")
        assertTrue(BookHelp.reverseContent(book, old))
        val oldFile = cacheFile(book, old)
        File(oldFile.path + ".resources").writeText("7")

        val renamed = old.copy(title = "Renamed chapter")
        val newChapters = listOf(chapters[0], renamed)
        val oldKeys = BookHelp.captureChapterCacheKeys(book)
        replaceChapters(book, newChapters)
        BookHelp.migrateChapterCache(book, oldKeys, newChapters)

        val newFile = cacheFile(book, renamed)
        assertTrue(File(newFile.path + ".reversed").isFile)
        assertTrue(File(newFile.path + ".resources").isFile)
        assertEquals("7", File(newFile.path + ".resources").readText())
        assertTrue(BookHelp.isContentReversed(book, renamed))
    }

    @Test
    fun existingTargetIsNotOverwrittenAndOldFileIsKept() = withBook { book, chapters ->
        val old = chapters[1]
        BookHelp.saveText(book, old, "old-content")
        val oldFile = cacheFile(book, old)
        val renamed = old.copy(title = "Renamed chapter")
        BookHelp.saveText(book, renamed, "new-content")

        val newChapters = listOf(chapters[0], renamed)
        val oldKeys = BookHelp.captureChapterCacheKeys(book)
        replaceChapters(book, newChapters)
        BookHelp.migrateChapterCache(book, oldKeys, newChapters)

        assertEquals("new-content", BookHelp.getContent(book, renamed))
        assertTrue("目标已存在时保留旧文件，避免覆盖更新内容", oldFile.isFile)
    }

    @Test
    fun unchangedChapterKeepsItsFile() = withBook { book, chapters ->
        val unchanged = chapters[0]
        BookHelp.saveText(book, unchanged, "keep")
        val unchangedFile = cacheFile(book, unchanged)
        val renamed = chapters[1].copy(title = "Only this one changed")
        BookHelp.saveText(book, chapters[1], "move")

        val newChapters = listOf(unchanged, renamed)
        val oldKeys = BookHelp.captureChapterCacheKeys(book)
        replaceChapters(book, newChapters)
        BookHelp.migrateChapterCache(book, oldKeys, newChapters)

        assertTrue(unchangedFile.isFile)
        assertEquals("keep", BookHelp.getContent(book, unchanged))
    }

    @Test
    fun bookRenameMovesTheWholeCacheFolderAndRefreshesFolderName() = withBook { book, chapters ->
        BookHelp.saveText(book, chapters[0], "content")
        val oldFolderName = book.getFolderName()
        val oldFolder = File(BookHelp.cachePath, oldFolderName)
        assertTrue(oldFolder.isDirectory)

        val oldBook = book.copy()
        book.name = "Renamed ${UUID.randomUUID()}"
        assertEquals("改名后未迁移前仍使用缓存目录名", oldFolderName, book.getFolderName())
        BookHelp.updateCacheFolder(oldBook, book)

        val newFolderName = book.getFolderName()
        assertNotEquals(oldFolderName, newFolderName)
        assertFalse(oldFolder.exists())
        assertTrue(File(BookHelp.cachePath, newFolderName).isDirectory)
        assertEquals("content", BookHelp.getContent(book, chapters[0]))
    }

    private fun cacheFile(book: Book, chapter: BookChapter): File {
        return File(BookHelp.cachePath, "${book.getFolderName()}/${chapter.getFileName()}")
    }

    private fun replaceChapters(book: Book, chapters: List<BookChapter>) {
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
    }

    private fun withBook(test: (Book, List<BookChapter>) -> Unit) {
        val id = UUID.randomUUID().toString()
        val book = Book(
            bookUrl = "https://example.invalid/cache-migration/$id",
            name = "Cache migration $id",
        )
        val chapters = (0..2).map { index ->
            BookChapter(
                bookUrl = book.bookUrl,
                url = "${book.bookUrl}/$index",
                title = "Chapter $index",
                index = index,
            )
        }
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        try {
            test(book, chapters)
        } finally {
            BookHelp.clearCache(book)
            appDb.bookDao.delete(book)
        }
    }
}
