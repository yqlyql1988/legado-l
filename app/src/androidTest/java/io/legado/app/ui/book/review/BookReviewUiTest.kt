package io.legado.app.ui.book.review

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookReview
import io.legado.app.databinding.ActivityBookReviewBinding
import io.legado.app.databinding.ActivityBookReviewEditBinding
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookReviewUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val book = Book(name = "书评测试-${UUID.randomUUID()}", author = "Test author",
        bookUrl = "review-test:${UUID.randomUUID()}")

    @After fun tearDown() {
        appDb.openHelper.writableDatabase.execSQL(
            "delete from book_reviews where bookName = ?", arrayOf(book.name)
        )
        val draftKey = UUID.nameUUIDFromBytes(
            io.legado.app.utils.GSON.toJson(listOf(book.name, book.author)).toByteArray(Charsets.UTF_8)
        ).toString()
        context.getSharedPreferences("bookReviewDrafts", 0).edit().remove(draftKey).commit()
    }

    @Test fun emptyAndWrittenPanelsBothExpandAndCollapse() {
        instrumentation.runOnMainSync {
            val view = BookReviewView(androidx.appcompat.view.ContextThemeWrapper(
                context, io.legado.app.R.style.AppTheme_Light))
            view.setBook(book)
            view.submitReviews(emptyList())
            val binding = io.legado.app.databinding.ViewBookReviewBinding.bind(view.getChildAt(0))
            assertEquals(android.view.View.GONE, binding.llReviewDetails.visibility)
            binding.tvReviewToggle.performClick()
            assertEquals(android.view.View.VISIBLE, binding.llReviewDetails.visibility)
            binding.tvReviewToggle.performClick()
            assertEquals(android.view.View.GONE, binding.llReviewDetails.visibility)
            view.submitReviews(listOf(BookReview(bookName = book.name, rating = 9,
                content = "Review", reviewDate = "2026-10-15")))
            assertEquals(android.view.View.VISIBLE, binding.llReviewDetails.visibility)
            binding.tvReviewToggle.performClick()
            assertEquals(android.view.View.GONE, binding.llReviewDetails.visibility)
            assertEquals(android.view.View.VISIBLE, binding.tvReviewDate.visibility)
            assertTrue(binding.tvReviewDate.text.contains("2026-10-15"))
            assertEquals(4.5f, binding.reviewRatingStars.rating)
            assertEquals(android.view.View.VISIBLE, binding.reviewRatingStars.visibility)
            view.submitReviews(emptyList())
            assertEquals(android.view.View.GONE, binding.llReviewDetails.visibility)
            assertEquals(android.view.View.GONE, binding.tvReviewDate.visibility)
            assertEquals(context.getString(io.legado.app.R.string.book_review_none),
                binding.tvReviewRating.text.toString())
            assertEquals(context.getString(io.legado.app.R.string.book_review_write),
                binding.tvReviewAdd.text.toString())
        }
    }

    @Test fun editorKeepsTextAndHalfStarRatingAcrossRecreationAndReopening() {
        val intent = Intent(context, BookReviewEditActivity::class.java).withReviewBook(book)
        ActivityScenario.launch<BookReviewEditActivity>(intent).use { scenario ->
            awaitEditor(scenario)
            scenario.onActivity {
                val binding = ActivityBookReviewEditBinding.bind(
                    it.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
                binding.ratingBar.rating = 4.5f
                binding.etContent.setText("重读后仍然喜欢这本书")
            }
            scenario.recreate()
            awaitEditor(scenario)
            scenario.onActivity {
                val binding = ActivityBookReviewEditBinding.bind(
                    (it.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0))
                assertEquals("重读后仍然喜欢这本书", binding.etContent.text.toString())
                assertEquals(4.5f, binding.ratingBar.rating)
                assertEquals(book.name, binding.tvBook.text.toString())
                assertEquals(book.author, binding.tvAuthor.text.toString())
                assertTrue(binding.tvAuthor.textSize < binding.tvBook.textSize)
            }
        }
        ActivityScenario.launch<BookReviewEditActivity>(intent).use { scenario ->
            awaitEditor(scenario)
            scenario.onActivity {
                val binding = ActivityBookReviewEditBinding.bind(
                    it.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
                assertEquals("重读后仍然喜欢这本书", binding.etContent.text.toString())
                assertEquals(4.5f, binding.ratingBar.rating)
                binding.titleBar.toolbar.menu.performIdentifierAction(io.legado.app.R.id.menu_save, 0)
            }
            await { appDb.bookReviewDao.forBook(book.name, book.author).isNotEmpty() }
        }
        assertEquals(9, appDb.bookReviewDao.forBook(book.name, book.author).single().rating)
    }

    @Test fun archivePlacesScoreBesideTitleAndSmallAuthorBelowIt() {
        appDb.bookReviewDao.save(BookReview(bookName = book.name, bookAuthor = book.author,
            rating = 9, content = "Layout", reviewDate = "2026-10-15"))
        ActivityScenario.launch<BookReviewActivity>(
            Intent(context, BookReviewActivity::class.java)
        ).use { scenario ->
            await {
                var ready = false
                scenario.onActivity {
                    val search = it.findViewById<androidx.appcompat.widget.SearchView>(io.legado.app.R.id.search_view)
                    search.setQuery(book.name, false)
                    val list = it.findViewById<androidx.recyclerview.widget.RecyclerView>(io.legado.app.R.id.recycler_view)
                    val child = list.getChildAt(0)
                    if (child != null) {
                        val row = io.legado.app.databinding.ItemBookReviewBinding.bind(child)
                        ready = row.tvTitle.text.toString() == book.name && row.tvTitle.width > 0
                        if (ready) {
                            assertEquals(book.author, row.tvAuthor.text.toString())
                            assertTrue(row.tvAuthor.textSize < row.tvTitle.textSize)
                            assertSame(row.tvTitle.parent, row.reviewRatingStars.parent)
                            assertEquals(2, row.tvTitle.maxLines)
                            val titleBounds = android.graphics.Rect()
                            val ratingBounds = android.graphics.Rect()
                            val authorBounds = android.graphics.Rect()
                            row.tvTitle.getGlobalVisibleRect(titleBounds)
                            row.reviewRatingStars.getGlobalVisibleRect(ratingBounds)
                            row.tvAuthor.getGlobalVisibleRect(authorBounds)
                            assertTrue(ratingBounds.left >= titleBounds.right)
                            assertTrue(authorBounds.top >= titleBounds.bottom)
                        }
                    }
                }
                ready
            }
        }
    }

    @Test fun promotedTextOnlyInitialKeepsOptionalScoreAcrossRecreation() {
        val initial = appDb.bookReviewDao.save(BookReview(bookName = book.name, bookAuthor = book.author,
            rating = 9, content = "Initial", reviewDate = "2026-10-14"))
        val follow = initial.copy(id = UUID.randomUUID().toString(),
            rating = null, isFollowUp = true, content = "Text follow-up", reviewDate = "2026-10-15")
        appDb.bookReviewDao.insert(follow)
        appDb.bookReviewDao.delete(initial)
        try {
            ActivityScenario.launch<BookReviewEditActivity>(
                Intent(context, BookReviewEditActivity::class.java).putExtra("reviewId", follow.id)
            ).use { scenario ->
                awaitEditor(scenario)
                scenario.onActivity {
                    it.findViewById<android.widget.RatingBar>(io.legado.app.R.id.rating_bar).rating = 3f
                }
                scenario.recreate()
                awaitEditor(scenario)
                scenario.onActivity {
                    val binding = ActivityBookReviewEditBinding.bind(
                        it.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
                    assertEquals(android.view.View.VISIBLE, binding.tvClearRating.visibility)
                    binding.tvClearRating.performClick()
                    binding.etContent.setText("Edited after promotion")
                    binding.titleBar.toolbar.menu.performIdentifierAction(io.legado.app.R.id.menu_save, 0)
                }
                await { appDb.bookReviewDao.get(follow.id)?.content == "Edited after promotion" }
            }
            assertNull(appDb.bookReviewDao.get(follow.id)?.rating)
        } finally {
            appDb.openHelper.writableDatabase.execSQL(
                "delete from book_review_deletions where id = ?", arrayOf(initial.id))
            context.getSharedPreferences("bookReviewDrafts", 0).edit().remove("edit:${follow.id}").commit()
        }
    }

    @Test fun perBookHistoryShowsInitialAndFollowUp() {
        appDb.bookReviewDao.save(BookReview(bookName = book.name, bookAuthor = book.author,
            rating = 8, content = "Initial review", reviewDate = "2026-10-14"))
        appDb.bookReviewDao.save(BookReview(bookName = book.name, bookAuthor = book.author,
            rating = null, content = "Follow-up", reviewDate = "2026-10-15"))
        ActivityScenario.launch<BookReviewActivity>(
            Intent(context, BookReviewActivity::class.java).withReviewBook(book)
        ).use { scenario ->
            await {
                var count = 0
                scenario.onActivity {
                    val binding = ActivityBookReviewBinding.bind(
                        it.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
                    count = binding.recyclerView.adapter?.itemCount ?: 0
                }
                count == 2
            }
            scenario.onActivity {
                val binding = ActivityBookReviewBinding.bind(
                    it.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
                assertEquals(4f, binding.bookRatingStars.rating)
                assertFalse(binding.tvSort.isShown)
            }
        }
    }

    private fun awaitEditor(scenario: ActivityScenario<BookReviewEditActivity>) = await {
        var ready = false
        scenario.onActivity {
            ready = it.findViewById<android.widget.TextView>(io.legado.app.R.id.tv_book)
                ?.text?.contains(book.name) == true
        }
        ready
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("Timed out waiting for review UI", condition())
    }
}
