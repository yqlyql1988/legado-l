package io.legado.app.ui.book.review

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookReview
import io.legado.app.databinding.ActivityBookReviewEditBinding
import io.legado.app.utils.GSON
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.bottomPadding
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

class BookReviewEditActivity : BaseActivity<ActivityBookReviewEditBinding>() {
    override val binding by viewBinding(ActivityBookReviewEditBinding::inflate)
    private val drafts by lazy { getSharedPreferences("bookReviewDrafts", MODE_PRIVATE) }
    private val draftKey by lazy {
        intent.getStringExtra("reviewId")?.let { "edit:$it" }
            ?: UUID.nameUUIDFromBytes(
                GSON.toJson(listOf(intent.getStringExtra("bookName"),
                    intent.getStringExtra("bookAuthor"))).toByteArray(Charsets.UTF_8)
            ).toString()
    }
    private var review: BookReview? = null
    private var date = LocalDate.now()
    private var saving = false
    private var saved = false
    private var ready = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.root.setOnApplyWindowInsetsListenerCompat { view, insets ->
            view.bottomPadding = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            ).bottom
            insets
        }
        binding.tvDate.setOnClickListener {
            if (!ready) return@setOnClickListener
            DatePickerDialog(this, { _, year, month, day ->
                date = LocalDate.of(year, month + 1, day)
                binding.tvDate.text = date.toString()
                persistDraft()
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        binding.ratingBar.setOnRatingBarChangeListener { _, rating, _ ->
            binding.tvRating.text = if (rating > 0f) {
                getString(R.string.book_review_score, rating.toDouble())
            } else getString(
                if (review?.isFollowUp == true) R.string.book_review_no_score
                else R.string.book_review_required
            )
            persistDraft()
        }
        binding.tvClearRating.setOnClickListener { binding.ratingBar.rating = 0f }
        binding.etContent.doAfterTextChanged { persistDraft() }
        lifecycleScope.launch {
            try {
                val id = intent.getStringExtra("reviewId")
                val draft = runCatching {
                    GSON.fromJson(savedInstanceState?.getString("draft")
                        ?: drafts.getString(draftKey, null), BookReview::class.java)
                }.getOrNull()
                val loaded = withContext(IO) {
                    if (id != null) appDb.bookReviewDao.get(id) else {
                        val book = requireNotNull(intent.reviewBook())
                        // A rotation can interrupt the UI after the transaction has committed.
                        // Reuse that row instead of turning its saved draft into a duplicate.
                        draft?.let { appDb.bookReviewDao.get(it.id) }
                            ?: BookReview(bookName = book.name, bookAuthor = book.author,
                                bookUrl = book.bookUrl, coverUrl = book.getDisplayCover(),
                                isFollowUp = appDb.bookReviewDao.forBook(book.name, book.author).isNotEmpty())
                    }
                }
                if (loaded == null) {
                    toastOnUi(R.string.book_review_missing)
                    finish()
                    return@launch
                }
                // Use the freshly loaded identity/version after renames or a restored backup.
                review = if (draft != null) loaded.copy(
                    id = if (id == null) draft.id else loaded.id,
                    rating = draft.rating, content = draft.content, reviewDate = draft.reviewDate,
                ) else loaded
                showReview(requireNotNull(review))
                ready = true
                if (draft != null && savedInstanceState == null) {
                    toastOnUi(R.string.book_review_draft_restored)
                }
                invalidateOptionsMenu()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toastOnUi(R.string.book_review_load_failed)
                finish()
            }
        }
    }

    private fun showReview(value: BookReview) = binding.run {
        titleBar.title = getString(when {
            intent.hasExtra("reviewId") -> R.string.book_review_edit
            value.isFollowUp -> R.string.book_review_follow_up
            else -> R.string.book_review_write
        })
        tvBook.text = listOf(value.bookName, value.bookAuthor)
            .filter { it.isNotBlank() }.joinToString(" · ")
        date = LocalDate.parse(value.reviewDate)
        tvDate.text = date.toString()
        tvClearRating.isVisible = value.isFollowUp
        ratingBar.rating = (value.rating ?: 0) / 2f
        etContent.setText(value.content)
    }

    private fun currentDraft(): BookReview? = review?.copy(
        rating = (binding.ratingBar.rating * 2).roundToInt().takeIf { it > 0 },
        content = binding.etContent.text?.toString().orEmpty(),
        reviewDate = date.toString(),
    )

    private fun persistDraft() {
        if (!ready || saved) return
        val value = currentDraft() ?: return
        if (value != review || drafts.contains(draftKey)) {
            drafts.edit().putString(draftKey, GSON.toJson(value)).apply()
        }
    }

    override fun onPause() {
        persistDraft()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (ready && !saved) outState.putString("draft", GSON.toJson(currentDraft()))
        super.onSaveInstanceState(outState)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_info_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_save)?.isEnabled = ready && !saving
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_save) save()
        return super.onCompatOptionsItemSelected(item)
    }

    private fun save() {
        if (!ready || saving) return
        val value = currentDraft() ?: return
        if (value.content.isBlank() || (!value.isFollowUp && value.rating == null)) {
            toastOnUi(R.string.book_review_required)
            return
        }
        saving = true
        binding.etContent.isEnabled = false
        binding.ratingBar.isEnabled = false
        binding.tvDate.isEnabled = false
        binding.tvClearRating.isEnabled = false
        invalidateOptionsMenu()
        persistDraft()
        lifecycleScope.launch {
            try {
                withContext(IO) { appDb.bookReviewDao.save(value) }
                saved = true
                drafts.edit().remove(draftKey).apply()
                toastOnUi(R.string.book_review_saved)
                setResult(RESULT_OK)
                finish()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toastOnUi(e.localizedMessage ?: getString(R.string.book_review_load_failed))
            } finally {
                saving = false
                binding.etContent.isEnabled = true
                binding.ratingBar.isEnabled = true
                binding.tvDate.isEnabled = true
                binding.tvClearRating.isEnabled = true
                invalidateOptionsMenu()
            }
        }
    }

    companion object {
        fun create(context: Context, book: Book) {
            context.startActivity(Intent(context, BookReviewEditActivity::class.java).withReviewBook(book))
        }

        fun edit(context: Context, id: String) {
            context.startActivity(Intent(context, BookReviewEditActivity::class.java)
                .putExtra("reviewId", id))
        }
    }
}
