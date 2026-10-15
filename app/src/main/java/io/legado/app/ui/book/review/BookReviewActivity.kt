package io.legado.app.ui.book.review

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.content.res.ColorStateList
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DividerItemDecoration
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookReview
import io.legado.app.data.entities.BookReviewArchive
import io.legado.app.data.entities.BookReviewSort
import io.legado.app.data.entities.archiveBookReviews
import io.legado.app.databinding.ActivityBookReviewBinding
import io.legado.app.databinding.ItemBookReviewBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.widget.PopupAction
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

class BookReviewActivity : BaseActivity<ActivityBookReviewBinding>() {
    override val binding by viewBinding(ActivityBookReviewBinding::inflate)
    private val book by lazy { intent.reviewBook() }
    private val adapter = ReviewAdapter()
    private var reviews: List<BookReview> = emptyList()
    private var query = ""
    private var sort = BookReviewSort.RECENT
    private lateinit var search: SearchView

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        sort = if (getPrefString(SORT_PREFERENCE) == "rating") BookReviewSort.RATING
            else BookReviewSort.RECENT
        binding.recyclerView.adapter = adapter
        if (book == null) binding.recyclerView.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        binding.recyclerView.applyNavigationBarPadding()
        binding.tvSort.isVisible = book == null
        binding.tvSort.setOnClickListener { showSortMenu() }
        binding.tvWrite.isVisible = book != null
        binding.tvWrite.setOnClickListener {
            book?.let { BookReviewEditActivity.create(this, it) }
        }
        binding.titleBar.setTitle(if (book == null) R.string.book_reviews else R.string.book_review_history)
        binding.bookHeading.isVisible = book != null
        binding.tvBookName.text = book?.name
        binding.tvBookAuthor.text = book?.author
        binding.tvBookAuthor.isVisible = !book?.author.isNullOrBlank()
        search = binding.root.findViewById(R.id.search_view)
        search.applyTint(getCompatColor(R.color.primaryText))
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
        search.isVisible = savedInstanceState?.getBoolean("searchVisible") == true
        search.setQuery(savedInstanceState?.getString("query").orEmpty(), false)
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

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_review, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_search) {
            search.isVisible = !search.isVisible
            if (search.isVisible) {
                search.isIconified = false
                search.requestFocus()
            } else {
                search.setQuery("", false)
                search.clearFocus()
            }
            return true
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", query)
        outState.putBoolean("searchVisible", search.isVisible)
        super.onSaveInstanceState(outState)
    }

    private fun showSortMenu() = PopupAction(this).apply {
        setVertical(true)
        setActionItems(BookReviewSort.entries.map { option ->
            PopupAction.PopupActionItem(
                title = getString(if (option == BookReviewSort.RECENT)
                    R.string.book_review_sort_recent else R.string.book_review_sort_rating),
                value = option.name, checkable = true, checked = sort == option)
        })
        onActionClick = { value ->
            dismiss()
            sort = BookReviewSort.valueOf(value)
            putPrefString(SORT_PREFERENCE, if (sort == BookReviewSort.RATING) "rating" else "recent")
            render()
        }
        showAsDropDown(this@BookReviewActivity.binding.tvSort, 0, 4.dpToPx())
    }

    private fun render() {
        val archives = archiveBookReviews(reviews, if (book == null) sort else BookReviewSort.RECENT)
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
            if (reviews.isEmpty()) getString(R.string.book_review_none)
            else getString(R.string.book_review_count, reviews.size)
        }
        binding.tvSort.setText(if (sort == BookReviewSort.RECENT)
            R.string.book_review_sort_recent else R.string.book_review_sort_rating)
        bindBookReviewRating(binding.bookRatingStars, binding.tvBookRating,
            archives.firstOrNull()?.rating)
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
                if (field == value) return
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
                root.setPadding(18.dpToPx(), if (isArchive) 18.dpToPx() else 0,
                    18.dpToPx(), if (isArchive) 18.dpToPx() else 0)
                reviewBody.setPadding(0, 0, 0, if (isArchive) 0 else 24.dpToPx())
                timelineIndicator.isVisible = !isArchive
                timelineLine.isVisible = !isArchive && position < rows.lastIndex
                timelineDot.backgroundTintList = ColorStateList.valueOf(
                    if (position == 0) accentColor else getCompatColor(R.color.secondaryText))
                ivCover.isVisible = isArchive
                if (isArchive) ivCover.load(review.asBook(), false)
                tvTitle.text = if (isArchive) {
                    review.bookName
                } else getString(
                    if (review.isFollowUp) R.string.book_review_follow_up_label
                    else R.string.book_review_initial
                )
                tvTitle.maxLines = if (isArchive) 2 else 1
                tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    if (isArchive) 16f else 13f)
                tvAuthor.isVisible = isArchive && review.bookAuthor.isNotBlank()
                tvAuthor.text = review.bookAuthor
                bindBookReviewRating(reviewRatingStars, tvRating, archive.rating,
                    if (!isArchive && review.isFollowUp) R.string.book_review_no_score
                    else R.string.book_review_unrated
                )
                tvContent.maxLines = if (isArchive) 2 else Int.MAX_VALUE
                tvContent.ellipsize = if (isArchive) android.text.TextUtils.TruncateAt.END else null
                tvContent.text = review.content
                tvDate.text = if (isArchive) {
                    "${review.reviewDate} · ${getString(R.string.book_review_count, archive.reviews.size)}"
                } else review.reviewDate
                tvDate.isVisible = isArchive
                tvHistoryDate.isVisible = !isArchive
                tvHistoryDate.text = review.reviewDate
                reviewMore.isVisible = !isArchive
                reviewMore.setOnClickListener { anchor ->
                    popupActionMenu(this@BookReviewActivity) {
                        item(getString(R.string.edit), "edit")
                        item(getString(R.string.delete), "delete")
                        danger("delete")
                    }.show(anchor) { action ->
                        if (action == "edit") BookReviewEditActivity.edit(
                            this@BookReviewActivity, review.id)
                        else confirmDeleteBookReview(review)
                    }
                }
                root.setOnClickListener {
                    if (isArchive) open(this@BookReviewActivity, review.asBook())
                    else BookReviewEditActivity.edit(this@BookReviewActivity, review.id)
                }
                var openDetailsOnRelease = false
                val openDetails = {
                    startActivity(Intent(this@BookReviewActivity, BookInfoActivity::class.java)
                        .putExtra("name", review.bookName)
                        .putExtra("author", review.bookAuthor)
                        .putExtra("bookUrl", review.bookUrl))
                }
                root.setOnTouchListener(if (isArchive) {
                    android.view.View.OnTouchListener { view, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL ->
                                openDetailsOnRelease = false
                            MotionEvent.ACTION_UP -> if (openDetailsOnRelease) {
                                openDetailsOnRelease = false
                                // Finish this gesture before opening a screen with clickable content.
                                view.post { openDetails() }
                            }
                        }
                        false
                    }
                } else null)
                root.setOnLongClickListener(if (isArchive) {
                    android.view.View.OnLongClickListener { view ->
                        if (view.isPressed) openDetailsOnRelease = true else openDetails()
                        true
                    }
                } else null)
                root.isLongClickable = isArchive
            }
        }
    }

    private class ReviewHolder(val binding: ItemBookReviewBinding) :
        RecyclerView.ViewHolder(binding.root)

    companion object {
        internal const val SORT_PREFERENCE = "bookReviewArchiveSort"
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
