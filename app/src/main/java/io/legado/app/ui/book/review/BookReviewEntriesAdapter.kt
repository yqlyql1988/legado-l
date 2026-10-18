package io.legado.app.ui.book.review

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.entities.BookReview
import io.legado.app.databinding.ItemBookReviewBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor

/** Shared by the review archive and the reading-notes review tab. */
internal class BookReviewEntriesAdapter(private val activity: BaseActivity<*>) :
    RecyclerView.Adapter<BookReviewEntriesAdapter.Holder>() {
    var reviews: List<BookReview> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun getItemCount() = reviews.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemBookReviewBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val review = reviews[position]
        holder.binding.run {
            root.setPadding(18.dpToPx(), 0, 18.dpToPx(), 0)
            reviewBody.setPadding(0, 0, 0, 24.dpToPx())
            ivCover.isVisible = false
            timelineIndicator.isVisible = true
            timelineLine.isVisible = position < reviews.lastIndex
            timelineDot.backgroundTintList = ColorStateList.valueOf(
                if (position == 0) activity.accentColor else activity.getCompatColor(R.color.secondaryText))
            tvTitle.setText(if (review.isFollowUp) R.string.book_review_follow_up_label
                else R.string.book_review_initial)
            tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            tvTitle.maxLines = 1
            tvHistoryDate.isVisible = true
            tvHistoryDate.text = review.reviewDate
            tvAuthor.isVisible = false
            tvDate.isVisible = false
            bindBookReviewRating(reviewRatingStars, tvRating, review.rating,
                if (review.isFollowUp) R.string.book_review_no_score else R.string.book_review_unrated)
            tvContent.maxLines = Int.MAX_VALUE
            tvContent.ellipsize = null
            tvContent.text = review.content
            reviewMore.isVisible = true
            reviewMore.setOnClickListener { anchor ->
                popupActionMenu(activity) {
                    item(activity.getString(R.string.edit), "edit")
                    item(activity.getString(R.string.delete), "delete")
                    danger("delete")
                }.show(anchor) { action ->
                    if (action == "edit") BookReviewEditActivity.edit(activity, review.id)
                    else activity.confirmDeleteBookReview(review)
                }
            }
            root.setOnClickListener { BookReviewEditActivity.edit(activity, review.id) }
        }
    }

    class Holder(val binding: ItemBookReviewBinding) : RecyclerView.ViewHolder(binding.root)
}
