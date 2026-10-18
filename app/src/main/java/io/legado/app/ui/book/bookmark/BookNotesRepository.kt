package io.legado.app.ui.book.bookmark

import android.net.Uri
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightTag
import io.legado.app.data.entities.HighlightTagLink
import io.legado.app.data.entities.HighlightTagOrder
import io.legado.app.model.ReadBook
import io.legado.app.utils.FileDoc
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.openOutputStream
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object BookNotesRepository {
    private data class TagData(
        val tags: List<HighlightTag>, val links: List<HighlightTagLink>,
        val orders: List<HighlightTagOrder>
    )
    private val tagData = combine(appDb.highlightTagDao.flowAll(),
        appDb.highlightTagDao.flowLinks(), appDb.highlightTagDao.flowOrders(), ::TagData)
    val archives = combine(appDb.bookmarkDao.flowAll(), appDb.bookHighlightDao.flowAll(),
        appDb.bookDao.flowAll(), tagData, appDb.bookReviewDao.flowAll()) { bookmarks, highlights, books, tagData, reviews ->
        archiveBookNotes(bookmarks, highlights, books, tagData.tags, tagData.links, tagData.orders, reviews)
    }.flowOn(IO)

    suspend fun delete(notes: List<BookNote>) {
        withContext(IO) {
            appDb.runInTransaction {
                notes.mapNotNull { it.bookmark }.takeIf { it.isNotEmpty() }?.let {
                    appDb.bookmarkDao.delete(*it.toTypedArray())
                }
                notes.mapNotNull { it.highlight }.takeIf { it.isNotEmpty() }?.let {
                    appDb.bookHighlightDao.delete(*it.toTypedArray())
                }
            }
        }
        refreshReader(notes.mapNotNull { it.highlight?.bookUrl }.toSet())
    }

    suspend fun refreshReader(bookUrls: Set<String>) {
        val book = ReadBook.book?.takeIf { it.bookUrl in bookUrls } ?: return
        withContext(IO) { ReadBook.loadHighlights(book) }
        withContext(Main.immediate) {
            if (ReadBook.book?.bookUrl == book.bookUrl) ReadBook.upHighlightTags()
        }
    }

    suspend fun exportMarkdown(uri: Uri, notes: List<BookNote>) = withContext(IO) {
        val suffix = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        val name = "notes-$suffix.md"
        val text = notesMarkdown(notes)
        try {
            FileDoc.fromUri(uri, true).createFileIfNotExist(name).openOutputStream()
                .getOrThrow().bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        } catch (e: Exception) {
            AppLog.put("摘录导出失败\n${e.localizedMessage}", e)
            throw e
        }
    }
}
