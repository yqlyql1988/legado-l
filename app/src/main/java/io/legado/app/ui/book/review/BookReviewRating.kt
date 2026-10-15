package io.legado.app.ui.book.review

import android.content.res.ColorStateList
import android.widget.RatingBar
import android.widget.TextView
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.getCompatColor

internal fun bindBookReviewRating(
    stars: RatingBar,
    label: TextView,
    rating: Int?,
    emptyLabel: Int = R.string.book_review_unrated,
) {
    stars.isVisible = rating != null
    label.isVisible = rating == null
    label.setText(emptyLabel)
    stars.rating = (rating ?: 0) / 2f
    stars.contentDescription = stars.context.getString(R.string.book_review_score, stars.rating.toDouble())
    stars.progressTintList = ColorStateList.valueOf(stars.context.accentColor)
    stars.progressBackgroundTintList =
        ColorStateList.valueOf(stars.context.getCompatColor(R.color.secondaryText))
}
