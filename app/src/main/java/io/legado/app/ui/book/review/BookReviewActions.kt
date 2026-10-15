package io.legado.app.ui.book.review

import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookReview
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun BaseActivity<*>.confirmDeleteBookReview(
    review: BookReview,
    onBusy: (Boolean) -> Unit = {},
    onDeleted: () -> Unit = {},
) {
    val explanation = getString(
        if (review.isFollowUp) R.string.book_review_delete_confirm
        else R.string.book_review_delete_initial_confirm
    )
    alert(
        title = getString(R.string.book_review_delete),
        message = "${review.bookName} · ${review.reviewDate}\n\n" +
            "${review.content.take(120)}\n\n$explanation",
    ) {
        positiveButton(R.string.delete) {
            onBusy(true)
            lifecycleScope.launch {
                try {
                    val deleted = withContext(IO) { appDb.bookReviewDao.delete(review) }
                    toastOnUi(if (deleted) R.string.book_review_deleted else R.string.book_review_missing)
                    onDeleted()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toastOnUi(e.localizedMessage ?: getString(R.string.book_review_delete_failed))
                } finally {
                    onBusy(false)
                }
            }
        }
        cancelButton()
    }
}
