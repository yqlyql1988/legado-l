package io.legado.app.ui.about

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordDaily
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadStatisticsUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val name = "统计验证 $id"
    private val removedName = "移出书架 $id"
    private val today = LocalDate.now()
    private val originalSimple = AppConfig.readRecordSimpleLayout
    private val originalFixed = AppConfig.readRecordFixedCard
    private val originalQualifiedOnly = AppConfig.readStatisticsQualifiedOnly
    private val minimumKeys = listOf("readStatisticsDayMinimum", "readStatisticsMonthMinimum",
        "readStatisticsYearMinimum", "readStatisticsAllMinimum")
    private val originalMinimums = minimumKeys.associateWith { context.defaultSharedPreferences.getLong(it, 0) }
    private val source = BookSource(bookSourceUrl = "https://example.invalid/statistics-source/$id")
    private val book = Book(bookUrl = "https://example.invalid/statistics-book/$id",
        tocUrl = "https://example.invalid/statistics-book/$id/toc", name = name, author = "统计作者",
        origin = source.bookSourceUrl, type = BookType.text, totalChapterNum = 1, canUpdate = false)
    private val chapter = BookChapter(bookUrl = book.bookUrl, url = "${book.bookUrl}/0", title = "继续阅读的章节")

    @Before fun before() {
        AppConfig.readStatisticsQualifiedOnly = false
        context.defaultSharedPreferences.edit().apply {
            minimumKeys.forEach { putLong(it, 0) }
        }.commit()
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(chapter)
        BookHelp.saveText(book, chapter, (1..40).joinToString("\n") { "阅读统计验证的第${it}行正文。" })
        appDb.readRecordDao.insert(
            ReadRecord("statistics-ui", name, book.author, 7_200_000, System.currentTimeMillis(), "历史章节", 7),
            ReadRecord("statistics-ui", removedName, "另一作者", 1_800_000, System.currentTimeMillis()),
        )
        appDb.readRecordDao.insertDaily(
            ReadRecordDaily("statistics-ui", name, book.author, today.toString(), 600_000),
            ReadRecordDaily("statistics-ui", removedName, "另一作者", today.toString(), 300_000),
        )
    }

    @After fun after() {
        AppConfig.readStatisticsQualifiedOnly = originalQualifiedOnly
        AppConfig.readRecordSimpleLayout = originalSimple
        AppConfig.readRecordFixedCard = originalFixed
        context.defaultSharedPreferences.edit().apply {
            originalMinimums.forEach { (key, value) -> putLong(key, value) }
        }.commit()
        appDb.readRecordDao.deleteByBook(name, book.author)
        appDb.readRecordDao.deleteByBook(removedName, "另一作者")
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
    }

    @Test fun bothRecordLayoutsKeepTopSearchAndOpenStatistics() {
        for ((simple, fixed) in listOf(true to true, false to true, false to false)) {
            AppConfig.readRecordSimpleLayout = simple
            AppConfig.readRecordFixedCard = fixed
            val monitor = instrumentation.addMonitor(ReadStatisticsActivity::class.java.name, null, false)
            try {
                ActivityScenario.launch<ReadRecordActivity>(Intent(context, ReadRecordActivity::class.java)).use { scenario ->
                    await(scenario) { it.findViewById<TextView>(R.id.tv_year_duration)?.text?.contains("今年已记录") == true }
                    scenario.onActivity { activity ->
                        val search = activity.findViewById<SearchView>(R.id.search_view)
                        assertNotNull(search)
                        assertSame(search, activity.findViewById<View>(R.id.title_bar).findViewById(R.id.search_view))
                        search.isIconified = false
                        search.setQuery(id, false)
                    }
                    await(scenario) {
                        it.findViewById<RecyclerView>(R.id.recycler_view).adapter?.itemCount ==
                            if (!simple && !fixed) 3 else 2
                    }
                    scenario.onActivity { activity ->
                        val entry = if (simple) activity.findViewById<View>(R.id.compact_summary)
                            else activity.findViewById(R.id.enhanced_summary)
                        assertTrue(entry.isClickable)
                        entry.performClick()
                    }
                    val stats = monitor.waitForActivityWithTimeout(5000)
                    assertNotNull("Statistics entry must open its own activity", stats)
                    instrumentation.runOnMainSync { stats.finish() }
                }
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }

    @Test fun tabsCalendarAndBothBookRoutesWork() {
        ActivityScenario.launch<ReadStatisticsActivity>(Intent(context, ReadStatisticsActivity::class.java)).use { scenario ->
            await(scenario) { it.findViewById<TextView>(R.id.tv_duration)?.text?.isNotEmpty() == true }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.tab_day).isSelected)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.hour_details).visibility)
                activity.findViewById<View>(R.id.tab_all).performClick()
                assertTrue(activity.findViewById<TextView>(R.id.tv_history).visibility == View.VISIBLE)
                activity.findViewById<View>(R.id.tab_year).performClick()
                assertTrue(activity.findViewById<TextView>(R.id.tv_period).text.contains(today.year.toString()))
                activity.findViewById<View>(R.id.tab_month).performClick()
                activity.findViewById<View>(R.id.graph_trend).performClick()
                val grid = activity.findViewById<GridLayout>(R.id.calendar_grid)
                val day = (0 until grid.childCount).map { grid.getChildAt(it) }.first {
                    it.contentDescription?.startsWith(today.toString()) == true
                }
                assertFalse(day.isSelected)
                day.performClick()
                assertTrue(activity.findViewById<TextView>(R.id.tv_selected_day).text.startsWith(today.toString()))
                assertTrue(activity.findViewById<LinearLayout>(R.id.day_books).childCount >= 2)
            }
            scenario.recreate()
            await(scenario) {
                it.findViewById<TextView>(R.id.tv_selected_day)?.text?.startsWith(today.toString()) == true
            }
            val monitor = instrumentation.addMonitor(ReadBookActivity::class.java.name, null, false)
            try {
                scenario.onActivity { activity -> clickDailyBook(activity, name) }
                val reader = monitor.waitForActivityWithTimeout(8000)
                assertNotNull("Book on shelf opens its reader", reader)
                instrumentation.runOnMainSync { reader.finish() }
            } finally { instrumentation.removeMonitor(monitor) }
            instrumentation.waitForIdleSync()
            val searchMonitor = instrumentation.addMonitor(SearchActivity::class.java.name, null, false)
            try {
                await(scenario) { (it.findViewById<LinearLayout>(R.id.day_books)?.childCount ?: 0) >= 2 }
                scenario.onActivity { activity -> clickDailyBook(activity, removedName) }
                val search = searchMonitor.waitForActivityWithTimeout(5000)
                assertNotNull("Removed book opens existing name search", search)
                instrumentation.runOnMainSync { search.finish() }
            } finally { instrumentation.removeMonitor(searchMonitor) }
        }
    }

    private fun clickDailyBook(activity: ReadStatisticsActivity, title: String) {
        val rows = activity.findViewById<LinearLayout>(R.id.day_books)
        val row = (0 until rows.childCount).map { rows.getChildAt(it) }.first {
            it.findViewById<TextView>(R.id.tv_book_name)?.text == title
        }
        row.performClick()
    }

    private fun <T : androidx.activity.ComponentActivity> await(scenario: ActivityScenario<T>, predicate: (T) -> Boolean) {
        repeat(100) {
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Page did not finish loading")
    }
}
