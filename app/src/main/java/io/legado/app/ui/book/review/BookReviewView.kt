package io.legado.app.ui.book.review

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookReview
import io.legado.app.data.entities.BookReviewArchive
import io.legado.app.databinding.ViewBookReviewBinding

/** The same position and the same expand/collapse interaction for every book. */
class BookReviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val binding = ViewBookReviewBinding.inflate(LayoutInflater.from(context), this, true)
    private var book: Book? = null
    private var reviews: List<BookReview> = emptyList()
    private var expanded: Boolean? = null
    private var hasLoaded = false

    init {
        binding.reviewHeader.setOnClickListener {
            expanded = expanded != true
            render()
        }
        binding.tvReviewToggle.setOnClickListener {
            expanded = expanded != true
            render()
        }
        binding.tvReviewAll.setOnClickListener {
            book?.let { BookReviewActivity.open(context, it) }
        }
        binding.tvReviewEdit.setOnClickListener {
            reviews.firstOrNull()?.let { BookReviewEditActivity.edit(context, it.id) }
        }
        binding.tvReviewAdd.setOnClickListener {
            book?.let { BookReviewEditActivity.create(context, it) }
        }
    }

    fun setBook(value: Book) {
        val oldBook = book
        if (oldBook != null && (oldBook.name != value.name || oldBook.author != value.author)) {
            expanded = null
            reviews = emptyList()
            hasLoaded = false
        }
        book = value.copy()
    }

    fun submitReviews(value: List<BookReview>) {
        if (hasLoaded && reviews.isEmpty() && value.isNotEmpty()) expanded = true
        if (hasLoaded && reviews.isNotEmpty() && value.isEmpty()) expanded = false
        reviews = value
        if (expanded == null) expanded = value.isNotEmpty()
        hasLoaded = true
        render()
    }

    private fun render() = binding.run {
        val latest = reviews.firstOrNull()
        val isExpanded = expanded == true
        tvReviewTitle.text = context.getString(R.string.book_reviews)
        bindBookReviewRating(reviewRatingStars, tvReviewRating,
            latest?.let { BookReviewArchive(reviews).rating },
            if (latest == null) R.string.book_review_none else R.string.book_review_unrated)
        tvReviewCount.text = if (latest == null) "" else
            context.getString(R.string.book_review_short_count, reviews.size)
        tvReviewToggle.setText(
            if (isExpanded) R.string.book_review_collapse else R.string.book_review_expand
        )
        llReviewDetails.isVisible = isExpanded
        reviewSummary.isVisible = latest != null
        tvReviewDate.isVisible = latest != null
        tvReviewDate.text = latest?.let {
            "${it.reviewDate} · ${context.getString(
                if (it.isFollowUp) R.string.book_review_follow_up_label else R.string.book_review_initial)}"
        }
        tvReviewContent.text = latest?.content
            ?: context.getString(R.string.book_review_book_empty)
        tvReviewAll.isVisible = latest != null
        tvReviewEdit.isVisible = latest != null
        tvReviewAdd.setText(
            if (latest == null) R.string.book_review_write else R.string.book_review_follow_up
        )
    }

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable("super", super.onSaveInstanceState())
        expanded?.let { putBoolean("expanded", it) }
    }

    @Suppress("DEPRECATION")
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            super.onRestoreInstanceState(state.getParcelable("super"))
            if (state.containsKey("expanded")) expanded = state.getBoolean("expanded")
            render()
        } else {
            super.onRestoreInstanceState(state)
        }
    }
}
