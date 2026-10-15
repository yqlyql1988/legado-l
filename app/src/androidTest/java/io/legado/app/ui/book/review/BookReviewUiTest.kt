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
            assertTrue(binding.tvReviewRating.text.contains("4.5"))
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
                assertTrue(binding.tvSummary.text.contains("4.0"))
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
