package io.legado.app.ui.book.review

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookReview
import io.legado.app.data.entities.BookReviewArchive
import io.legado.app.data.entities.archiveBookReviews
import io.legado.app.databinding.ActivityBookReviewBinding
import io.legado.app.databinding.ItemBookReviewBinding
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

class BookReviewActivity : BaseActivity<ActivityBookReviewBinding>() {
    override val binding by viewBinding(ActivityBookReviewBinding::inflate)
    private val book by lazy { intent.reviewBook() }
    private val adapter = ReviewAdapter()
    private var reviews: List<BookReview> = emptyList()
    private var query = ""

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        binding.tvWrite.isVisible = book != null
        binding.tvWrite.setOnClickListener {
            book?.let { BookReviewEditActivity.create(this, it) }
        }
        binding.titleBar.subtitle = book?.name
        val search = binding.titleBar.findViewById<SearchView>(R.id.search_view)
        search.applyTint(primaryTextColor)
        search.queryHint = getString(R.string.book_review_search_hint)
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                search.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                query = newText.orEmpty()
                render()
                return true
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    val currentBook = book
                    val flow = if (currentBook == null) appDb.bookReviewDao.flowAll()
                        else appDb.bookReviewDao.flowForBook(currentBook.name, currentBook.author)
                    flow.collect {
                        reviews = it
                        render()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toastOnUi(R.string.book_review_load_failed)
                }
            }
        }
    }

    private fun render() {
        val archives = archiveBookReviews(reviews)
        val filtered = archives.filter { archive ->
            archive.reviews.any {
                it.bookName.contains(query, true) || it.bookAuthor.contains(query, true) ||
                    it.content.contains(query, true)
            }
        }
        adapter.rows = if (book == null) filtered else filtered.flatMap { archive ->
            archive.reviews.filter { it.content.contains(query, true) ||
                it.bookName.contains(query, true) || it.bookAuthor.contains(query, true)
            }.map { BookReviewArchive(listOf(it)) }
        }
        binding.tvSummary.text = if (book == null) {
            getString(R.string.book_review_archive_count, archives.size, reviews.size)
        } else {
            listOfNotNull(
                if (reviews.isEmpty()) getString(R.string.book_review_none)
                else getString(R.string.book_review_count, reviews.size),
                archives.firstOrNull()?.let { archive ->
                    getString(R.string.book_review_current_score,
                        archive.rating?.let { getString(R.string.book_review_score, it / 2.0) }
                            ?: getString(R.string.book_review_unrated))
                }
            ).joinToString(" · ")
        }
        binding.tvWrite.setText(
            if (reviews.isEmpty()) R.string.book_review_write else R.string.book_review_follow_up
        )
        binding.tvEmpty.isVisible = adapter.rows.isEmpty()
        binding.tvEmpty.setText(when {
            query.isNotEmpty() -> R.string.book_review_search_empty
            book != null -> R.string.book_review_book_empty
            else -> R.string.book_review_empty
        })
    }

    private inner class ReviewAdapter : RecyclerView.Adapter<ReviewHolder>() {
        var rows: List<BookReviewArchive> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ReviewHolder(
            ItemBookReviewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: ReviewHolder, position: Int) {
            val archive = rows[position]
            val review = archive.latest
            holder.binding.run {
                val isArchive = book == null
                ivCover.isVisible = isArchive
                if (isArchive) ivCover.load(review.asBook(), false)
                tvTitle.text = if (isArchive) {
                    review.bookName
                } else getString(
                    if (review.isFollowUp) R.string.book_review_follow_up_label
                    else R.string.book_review_initial
                )
                tvAuthor.isVisible = isArchive && review.bookAuthor.isNotBlank()
                tvAuthor.text = review.bookAuthor
                tvRating.text = archive.rating?.let {
                    getString(R.string.book_review_score, it / 2.0)
                } ?: getString(
                    if (!isArchive && review.isFollowUp) R.string.book_review_no_score
                    else R.string.book_review_unrated
                )
                tvContent.maxLines = if (isArchive) 3 else Int.MAX_VALUE
                tvContent.ellipsize = if (isArchive) android.text.TextUtils.TruncateAt.END else null
                tvContent.text = review.content
                tvDate.text = if (isArchive) {
                    "${review.reviewDate} · ${getString(R.string.book_review_count, archive.reviews.size)}"
                } else review.reviewDate
                tvEdit.isVisible = !isArchive
                tvEdit.setOnClickListener { BookReviewEditActivity.edit(this@BookReviewActivity, review.id) }
                reviewActions.isVisible = !isArchive
                tvDelete.setOnClickListener { confirmDeleteBookReview(review) }
                root.setOnClickListener {
                    if (isArchive) open(this@BookReviewActivity, review.asBook())
                    else BookReviewEditActivity.edit(this@BookReviewActivity, review.id)
                }
            }
        }
    }

    private class ReviewHolder(val binding: ItemBookReviewBinding) :
        RecyclerView.ViewHolder(binding.root)

    companion object {
        fun open(context: Context, book: Book) {
            context.startActivity(Intent(context, BookReviewActivity::class.java).withReviewBook(book))
        }
    }
}

internal fun BookReview.asBook() = Book(
    name = bookName, author = bookAuthor, bookUrl = bookUrl, coverUrl = coverUrl
)

internal fun Intent.withReviewBook(book: Book): Intent = apply {
    putExtra("bookName", book.name)
    putExtra("bookAuthor", book.author)
    putExtra("bookUrl", book.bookUrl)
    putExtra("coverUrl", book.getDisplayCover())
}

internal fun Intent.reviewBook(): Book? = getStringExtra("bookName")?.let {
    Book(name = it, author = getStringExtra("bookAuthor").orEmpty(),
        bookUrl = getStringExtra("bookUrl").orEmpty(), coverUrl = getStringExtra("coverUrl"))
}
