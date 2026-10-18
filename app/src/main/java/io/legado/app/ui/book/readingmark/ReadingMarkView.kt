package io.legado.app.ui.book.readingmark

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import io.legado.app.R
import io.legado.app.data.entities.BookReadingMark
import io.legado.app.databinding.ViewReadingMarkBinding

class ReadingMarkView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private val binding = ViewReadingMarkBinding.inflate(LayoutInflater.from(context), this, true)

    fun submitMark(mark: BookReadingMark?) {
        binding.readingMarkSummary.text = context.readingMarkSummary(mark)
    }
}

fun Context.readingMarkStatus(status: String): String = getString(when (status) {
    BookReadingMark.READING -> R.string.reading_mark_reading
    BookReadingMark.ABANDONED -> R.string.reading_mark_abandoned
    BookReadingMark.FINISHED -> R.string.reading_mark_finished
    else -> R.string.reading_mark_unmarked
})

fun Context.readingMarkSummary(mark: BookReadingMark?): String = buildList {
    add(readingMarkStatus(mark?.status ?: BookReadingMark.UNMARKED))
    if (mark?.status == BookReadingMark.ABANDONED) {
        mark.position?.title?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    if (!mark?.skipped.isNullOrEmpty()) add(getString(R.string.reading_mark_skip_count, mark.skipped.size))
}.joinToString(" · ")
