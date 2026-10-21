package io.legado.app.ui.book.bookmark

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ReadingNoteNavigation {
    const val EXTRA_KIND = "relocateReadingNoteKind"
    const val EXTRA_TIME = "relocateReadingNoteTime"
    data class QueueItem(
        val bookUrl: String,
        val kind: String,
        val time: Long,
        val chapterIndex: Int = -1,
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
                appDb.bookChapterDao.getChapterList(book.bookUrl).filterNot { it.isVolume }
            }
            if (activity.isFinishing || activity.isDestroyed) {
                clearQueue()
                return@launch
            }
            val chapter = chapters.firstOrNull { it.index == item.chapterIndex }
            if (chapter != null) {
                launchReader(activity, book, chapter.index, item)
            } else if (chapters.isEmpty()) {
                launchReader(activity, book, 0, item)
            } else {
                AlertDialog.Builder(activity).setTitle(R.string.reading_note_choose_chapter)
                    .setItems(chapters.map { it.title }.toTypedArray()) { _, position ->
                        launchReader(activity, book, chapters[position].index, item)
                    }.setNegativeButton(R.string.cancel) { _, _ -> clearQueue() }
                    .setOnCancelListener { clearQueue() }
                    .show()
            }
        }
    }

    fun unavailable(activity: FragmentActivity, book: Book, kind: String, time: Long) {
        AlertDialog.Builder(activity).setMessage(R.string.reading_note_unresolved)
            .setPositiveButton(R.string.reading_note_relocate) { _, _ -> relocate(activity, book, kind, time) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    fun relocate(activity: FragmentActivity, book: Book, kind: String, time: Long) {
        activity.lifecycleScope.launch {
            val chapters = withContext(IO) { appDb.bookChapterDao.getChapterList(book.bookUrl).filterNot { it.isVolume } }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (chapters.isEmpty()) {
                activity.startActivityForBook(book) {
                    putExtra(EXTRA_KIND, kind)
                    putExtra(EXTRA_TIME, time)
                }
                return@launch
            }
            AlertDialog.Builder(activity).setTitle(R.string.reading_note_choose_chapter)
                .setItems(chapters.map { it.title }.toTypedArray()) { _, position ->
                    activity.startActivityForBook(book) {
                        putExtra("index", chapters[position].index)
                        putExtra("chapterPos", 0)
                        putExtra(EXTRA_KIND, kind)
                        putExtra(EXTRA_TIME, time)
                    }
                }.setNegativeButton(R.string.cancel, null).show()
        }
    }

    private fun launchReader(
        activity: FragmentActivity,
        book: Book,
        chapterIndex: Int,
        item: QueueItem,
    ) {
        activity.startActivityForBook(book) {
            putExtra("index", chapterIndex)
            putExtra("chapterPos", 0)
            putExtra(EXTRA_KIND, item.kind)
            putExtra(EXTRA_TIME, item.time)
        }
    }
}
