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
}
