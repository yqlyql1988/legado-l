package io.legado.app.ui.book.bookmark

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReadingNoteLocation
import io.legado.app.help.ReadingNoteChapter
import io.legado.app.help.ReadingNotes
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ReadingNoteNavigation {
    const val EXTRA_KIND = "relocateReadingNoteKind"
    const val EXTRA_TIME = "relocateReadingNoteTime"
    const val EXTRA_TEXT = "relocateReadingNoteText"

    data class QueueItem(
        val bookUrl: String,
        val kind: String,
        val time: Long,
        val chapterIndex: Int = -1,
        val chapterUrl: String = "",
        val chapterName: String = "",
        val chapterPos: Int = 0,
        val layoutTitleLength: Int = -1,
        val quote: String = "",
    )

    private val relocationQueue = ArrayList<QueueItem>()

    fun startQueue(activity: FragmentActivity, items: List<QueueItem>) {
        relocationQueue.clear()
        relocationQueue.addAll(items)
        openNext(activity)
    }

    fun completeQueueItem(bookUrl: String, kind: String, time: Long): Boolean {
        val index = relocationQueue.indexOfFirst {
            it.bookUrl == bookUrl && it.kind == kind && it.time == time
        }
        if (index < 0) return false
        relocationQueue.removeAt(index)
        return relocationQueue.isNotEmpty()
    }

    fun clearQueue() {
        relocationQueue.clear()
    }

    fun openNext(activity: FragmentActivity) {
        val item = relocationQueue.firstOrNull() ?: return
        activity.lifecycleScope.launch {
            val book = withContext(IO) { appDb.bookDao.getBook(item.bookUrl) }
            if (book == null) {
                relocationQueue.removeAt(0)
                openNext(activity)
                return@launch
            }
            val chapters = withContext(IO) {
                ReadingNoteChapter.available(book)
            }
            if (activity.isFinishing || activity.isDestroyed) {
                clearQueue()
                return@launch
            }
            val location = ReadingNoteLocation(
                kind = item.kind,
                noteTime = item.time,
                bookUrl = book.bookUrl,
                chapterUrl = item.chapterUrl,
                chapterIndex = item.chapterIndex,
                chapterName = item.chapterName,
                chapterPos = item.chapterPos,
            )
            val chapter = ReadingNoteChapter.candidate(location, chapters)
            if (chapter != null) {
                launchReader(activity, book, chapter, item)
            } else if (chapters.isEmpty()) {
                launchReader(activity, book, null, item)
            } else {
                AlertDialog.Builder(activity).setTitle(R.string.reading_note_choose_chapter)
                    .setItems(chapters.map { it.title }.toTypedArray()) { _, position ->
                        launchReader(activity, book, chapters[position], item)
                    }.setNegativeButton(R.string.cancel) { _, _ -> clearQueue() }
                    .setOnCancelListener { clearQueue() }
                    .show()
            }
        }
    }

    fun unavailable(
        activity: FragmentActivity,
        book: Book,
        legacy: ReadingNoteLocation,
    ) {
        AlertDialog.Builder(activity).setMessage(R.string.reading_note_unresolved)
            .setPositiveButton(R.string.reading_note_relocate) { _, _ ->
                relocate(activity, book, legacy)
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /**
     * Opens the saved chapter when it still exists, at the last known character position.
     * Only a lost directory asks the reader to pick a chapter from the full list.
     */
    fun relocate(
        activity: FragmentActivity,
        book: Book,
        legacy: ReadingNoteLocation,
    ) {
        activity.lifecycleScope.launch {
            val chapters = withContext(IO) { ReadingNoteChapter.available(book) }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val saved = withContext(IO) { ReadingNotes.location(legacy, book.bookUrl) }
            val chapter = ReadingNoteChapter.candidate(saved, chapters)
            if (chapter != null) {
                launchReader(activity, book, chapter, queueItem(book, chapter, saved))
                return@launch
            }
            if (chapters.isEmpty()) {
                launchReader(activity, book, null, queueItem(book, null, saved))
                return@launch
            }
            AlertDialog.Builder(activity).setTitle(R.string.reading_note_choose_chapter)
                .setItems(chapters.map { it.title }.toTypedArray()) { _, position ->
                    val selected = chapters[position]
                    launchReader(activity, book, selected, queueItem(book, selected, saved))
                }.setNegativeButton(R.string.cancel, null).show()
        }
    }

    private fun queueItem(
        book: Book,
        chapter: BookChapter?,
        location: ReadingNoteLocation,
    ) = QueueItem(
        bookUrl = book.bookUrl,
        kind = location.kind,
        time = location.noteTime,
        chapterIndex = chapter?.index ?: location.chapterIndex,
        chapterUrl = chapter?.url ?: location.chapterUrl,
        chapterName = chapter?.title ?: location.chapterName,
        chapterPos = chapter?.let { location.chapterPos.coerceAtLeast(0) } ?: 0,
        layoutTitleLength = location.layoutTitleLength,
        quote = ReadingNotes.anchorQuote(location),
    )

    private fun launchReader(
        activity: FragmentActivity,
        book: Book,
        chapter: BookChapter?,
        item: QueueItem,
    ) {
        activity.startActivityForBook(book) {
            putExtra("index", chapter?.index ?: item.chapterIndex.coerceAtLeast(0))
            val sameChapter = chapter != null && (
                item.chapterUrl.isBlank() ||
                    ReadingNoteChapter.sameUrl(item.chapterUrl, chapter.url)
                )
            putExtra("chapterPos", if (sameChapter) item.chapterPos.coerceAtLeast(0) else 0)
            putExtra(EXTRA_KIND, item.kind)
            putExtra(EXTRA_TIME, item.time)
            if (item.quote.isNotBlank()) {
                putExtra(EXTRA_TEXT, item.quote)
                putExtra(
                    TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH,
                    item.layoutTitleLength,
                )
                putExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT, item.quote)
                putExtra(TocActivityResult.EXTRA_READING_NOTE_KEY, "${item.kind}:${item.time}")
            }
        }
    }
}
