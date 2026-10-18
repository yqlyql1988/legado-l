package io.legado.app.ui.book.read

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightTextBuilder
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.BackupConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.config.MoreConfigDialog
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CrossPageSelectionUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val keys = listOf(PreferKey.crossPageTextSelection, PreferKey.textSelectAble,
        PreferKey.preDownloadNum, PreferKey.longPressSelectParagraph)
    private val saved = keys.associateWith { prefs.all[it] }
    private val savedHelp = listOf("readHelpVersion", "readMenuHelpVersion").associateWith { LocalConfig.all[it] }
    private val unique = UUID.randomUUID().toString()
    private val source = BookSource(bookSourceUrl = "https://example.invalid/cross-page-source/$unique")
    private val book = Book(bookUrl = "https://example.invalid/cross-page/$unique",
        tocUrl = "https://example.invalid/cross-page/$unique/toc", origin = source.bookSourceUrl,
        name = "跨页选择验证", type = BookType.text, totalChapterNum = 2, canUpdate = false)
        .apply { setPageAnim(PageAnim.slidePageAnim); setUseReplaceRule(false) }
    private val chapters = (0..1).map { BookChapter(bookUrl = book.bookUrl,
        url = "${book.bookUrl}/$it", index = it, title = "第${it + 1}章") }
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private var cursor: View? = null
    private var downTime = 0L
    private var pointer = floatArrayOf()
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f

    @Before fun prepare() {
        prefs.edit().putBoolean(PreferKey.crossPageTextSelection, true)
            .putBoolean(PreferKey.textSelectAble, true)
            .putBoolean(PreferKey.longPressSelectParagraph, false)
            .putInt(PreferKey.preDownloadNum, 0).commit()
        LocalConfig.edit().putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        chapters.forEach { chapter ->
            BookHelp.saveText(book, chapter, (0 until 180).joinToString("\n") {
                "第${chapter.index}章第${it}段：跨页选字保留完整段落，包含标点、English及表情😀，反向拖动也要准确。"
            })
        }
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl).putExtra("inBookshelf", true))
        await { ReadBook.curTextChapter?.isCompleted == true && it.curPage.textPage.lineSize > 2 }
    }

    @After fun cleanup() {
        scenario?.close()
        BookHelp.clearCache(book)
        appDb.bookHighlightDao.delete(*appDb.bookHighlightDao.getByBook(book.bookUrl).toTypedArray())
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        prefs.edit().apply {
            saved.forEach { (key, value) -> when (value) {
                null -> remove(key)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
            } }
        }.commit()
        LocalConfig.edit().apply {
            savedHelp.forEach { (key, value) ->
                if (value == null) remove(key) else putInt(key, value as Int)
            }
        }.commit()
    }

    @Test fun stationaryHandleWaitsThenSelectsSeveralPagesAndStopsOnRelease() {
        selectWord()
        val initial = pageIndex()
        dragHandle(false, false)
        SystemClock.sleep(500)
        assertEquals("The longer initial dwell must be respected", initial, pageIndex())
        await { it.curPage.textPage.index >= initial + 3 }
        release()
        verifyTextAndAnchors()
        val stopped = pageIndex()
        SystemClock.sleep(1200)
        assertEquals("A released handle must never turn another page", stopped, pageIndex())
        screenshot("cross-page-paged-selection")
    }

    @Test fun initialLongPressCanContinueAcrossPagesWithoutLiftingTheFinger() {
        var x = 0f
        var y = 0f
        onReader { read ->
            val line = read.curPage.textPage.lines.first { !it.isTitle && it.columns.size > 8 }
            val column = line.columns[5]
            x = (column.start + column.end) / 2 + read.curPage.imgBgPaddingStart
            y = (line.lineTop + line.lineBottom) / 2 + read.curPage.headerHeight
            downTime = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            try { read.onTouchEvent(event) } finally { event.recycle() }
        }
        SystemClock.sleep(700)
        onReader { read ->
            assertTrue(read.isTextSelected)
            x = read.curPage.selectionRight - 2f
            y = read.curPage.selectionBottom - 2f
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE, x, y, 0)
            try { read.onTouchEvent(event) } finally { event.recycle() }
        }
        await { it.curPage.textPage.index >= 2 }
        onReader { read ->
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, x, y, 0)
            try { read.onTouchEvent(event) } finally { event.recycle() }
        }
        verifyTextAndAnchors()
    }

    @Test fun edgesOutsideTheTwoCornersNeverTurnPages() {
        onReader {
            ReadBook.setPageIndex(2)
            it.upContent(resetPageOffset = true)
        }
        await { it.curPage.textPage.index == 2 }
        val positions = listOf<(ReadView) -> Pair<Float, Float>>(
            { (it.curPage.selectionLeft + it.curPage.selectionRight) / 2f to it.curPage.selectionTop + 2f },
            { (it.curPage.selectionLeft + it.curPage.selectionRight) / 2f to it.curPage.selectionBottom - 2f },
            { it.curPage.selectionRight - 2f to it.curPage.selectionTop + 2f },
            { it.curPage.selectionLeft + 2f to it.curPage.selectionBottom - 2f },
            { it.curPage.selectionLeft - 2f to it.curPage.selectionTop + 2f },
            { it.curPage.selectionRight + 2f to it.curPage.selectionBottom - 2f },
            { it.curPage.selectionRight - 2f to it.curPage.selectionBottom + 2f },
            { it.curPage.selectionLeft + 2f to it.curPage.selectionTop - 2f },
        )
        for (position in positions) {
            selectWord()
            dragHandle(false, false, position)
            SystemClock.sleep(1000)
            assertEquals("Only the two text corners may turn pages", 2, pageIndex())
            release()
            onReader { assertTrue(it.isTextSelected) }
        }
    }

    @Test fun leavingTheCornerCancelsPendingAndRepeatingTurnsAndReentryWaitsAgain() {
        selectWord()
        val initial = pageIndex()
        dragHandle(false, false)
        SystemClock.sleep(500)
        moveHandleTo { (it.curPage.selectionLeft + it.curPage.selectionRight) / 2f to it.curPage.selectionBottom - 2f }
        SystemClock.sleep(500)
        assertEquals("Leaving must cancel the first turn", initial, pageIndex())
        moveHandleTo { it.curPage.selectionRight - 2f to it.curPage.selectionBottom - 2f }
        SystemClock.sleep(500)
        assertEquals("Reentry must wait the full dwell again", initial, pageIndex())
        await { it.curPage.textPage.index > initial }
        moveHandleTo { (it.curPage.selectionLeft + it.curPage.selectionRight) / 2f to it.curPage.selectionBottom - 2f }
        val stopped = pageIndex()
        SystemClock.sleep(1200)
        assertEquals("Leaving must cancel repeating turns", stopped, pageIndex())
        release()
        verifyTextAndAnchors()
    }

    @Test fun reverseDraggingShrinksTheRangeAndCancelStopsTheTimer() {
        selectWord()
        dragHandle(false, false)
        await { it.curPage.textPage.index >= 3 }
        release()
        var originalStart = -1
        var originalEnd = -1
        onReader {
            val highlight = it.curPage.createHighlight(HighlightStyle())!!
            originalStart = highlight.chapterPos
            originalEnd = highlight.chapterPosEnd
        }
        val before = pageIndex()
        dragHandle(false, true)
        await { it.curPage.textPage.index < before }
        release(MotionEvent.ACTION_CANCEL)
        onReader {
            val highlight = it.curPage.createHighlight(HighlightStyle())!!
            assertEquals(originalStart, highlight.chapterPos)
            assertTrue(highlight.chapterPosEnd < originalEnd)
        }
        verifyTextAndAnchors()
        val stopped = pageIndex()
        SystemClock.sleep(1100)
        assertEquals(stopped, pageIndex())
    }

    @Test fun chapterBoundaryKeepsTheSelectionAndNeverOpensTheNextChapter() {
        onReader {
            val chapter = ReadBook.curTextChapter!!
            ReadBook.setPageIndex(chapter.lastIndex - 1)
            it.upContent(resetPageOffset = true)
        }
        selectWord()
        dragHandle(false, false)
        await { it.curPage.textPage.index == ReadBook.curTextChapter!!.lastIndex }
        SystemClock.sleep(1200)
        assertEquals(0, ReadBook.durChapterIndex)
        release()
        verifyTextAndAnchors()
    }

    @Test fun scrollSelectionUsesTheSameAnchorsAndStopsWhenTheActivityPauses() {
        onReader {
            ReadBook.book!!.setPageAnim(PageAnim.scrollPageAnim)
            it.upPageAnim()
        }
        await { it.isScroll && it.curPage.textPage.lineSize > 2 }
        selectWord()
        dragHandle(false, false)
        SystemClock.sleep(500)
        assertEquals(0, pageIndex())
        await { it.curPage.textPage.index >= 1 }
        release()
        verifyTextAndAnchors()
        dragHandle(false, false)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        val stopped = ReadBook.durChapterPos
        SystemClock.sleep(1100)
        assertEquals(stopped, ReadBook.durChapterPos)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        cursor = null
    }

    @Test fun disabledSettingKeepsSinglePageSelectionAndUsesTheNormalBackupConfiguration() {
        assertTrue(BackupConfig.keyIsNotIgnore(PreferKey.crossPageTextSelection))
        prefs.edit().remove(PreferKey.crossPageTextSelection).commit()
        assertFalse(io.legado.app.help.config.AppConfig.crossPageTextSelection)
        selectWord()
        dragHandle(false, false)
        SystemClock.sleep(1100)
        release()
        assertEquals(0, pageIndex())
    }

    @Test fun savedCrossPageHighlightRestoresItsTextAndRangeAfterReopeningTheBook() {
        selectWord()
        dragHandle(false, false)
        await { it.curPage.textPage.index >= 2 }
        release()
        var saved: io.legado.app.data.entities.BookHighlight? = null
        onReader {
            saved = it.curPage.createHighlight(HighlightStyle(fill = android.graphics.Color.YELLOW))!!
            saved!!.note = "跨页标注保留笔记"
            ReadBook.addHighlight(saved!!)
            it.cancelSelect()
        }
        scenario!!.close()
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl).putExtra("inBookshelf", true))
        await { read ->
            ReadBook.curTextChapter?.isCompleted == true && read.curPage.textPage.lines.any { line ->
                line.columns.any { (it as? TextBaseColumn)?.highlightStyle?.fill == android.graphics.Color.YELLOW }
            }
        }
        val restored = appDb.bookHighlightDao.getByBook(book.bookUrl).single()
        assertEquals(saved!!.chapterPos, restored.chapterPos)
        assertEquals(saved!!.chapterPosEnd, restored.chapterPosEnd)
        assertEquals(saved!!.bookText, restored.bookText)
        assertEquals("跨页标注保留笔记", restored.note)
    }

    @Test fun switchingOffThroughReadingSettingsClearsTheExistingCrossPageSelection() {
        selectWord()
        dragHandle(false, false)
        await { it.curPage.textPage.index >= 2 }
        release()
        scenario!!.onActivity {
            MoreConfigDialog().showNow(it.supportFragmentManager, "cross-page-settings")
        }
        instrumentation.waitForIdleSync()
        scenario!!.onActivity {
            val dialog = it.supportFragmentManager.findFragmentByTag("cross-page-settings") as MoreConfigDialog
            (dialog.childFragmentManager.fragments.single() as MoreConfigDialog.ReadPreferenceFragment)
                .scrollToPreference(PreferKey.crossPageTextSelection)
        }
        onView(withText(R.string.cross_page_text_selection)).inRoot(isDialog()).perform(click())
        assertFalse(prefs.getBoolean(PreferKey.crossPageTextSelection, true))
        onReader { assertFalse(it.isTextSelected) }
        pressBack()
        val before = pageIndex()
        selectWord()
        dragHandle(false, false)
        SystemClock.sleep(1100)
        release()
        assertEquals(before, pageIndex())
    }

    private fun selectWord() {
        onReader { read ->
            val page = read.curPage.textPage
            val row = page.lines.indexOfFirst { !it.isTitle && it.columns.count { c -> c is TextBaseColumn } > 8 }
            val line = page.getLine(row)
            val index = line.columns.indices.first { it > 3 && line.columns[it] is TextBaseColumn }
            read.curPage.selectStartMoveIndex(TextPos(0, row, index))
            read.curPage.selectEndMoveIndex(TextPos(0, row, index + 1))
            read.isTextSelected = true
        }
    }

    private fun dragHandle(
        start: Boolean, top: Boolean,
        position: ((ReadView) -> Pair<Float, Float>)? = null,
    ) {
        onReader { read ->
            val activity = read.context as ReadBookActivity
            cursor = activity.findViewById(if (start) R.id.cursor_left else R.id.cursor_right)
            val target = cursor!!
            assertEquals(View.VISIBLE, target.visibility)
            val location = IntArray(2)
            target.getLocationOnScreen(location)
            val x = location[0] + target.width / 2f
            val y = location[1] + target.height / 2f
            downTime = SystemClock.uptimeMillis()
            touchOffsetX = SelectCursorGeometry.touchOffset(target.width / 2f,
                target.paddingLeft, target.width - target.paddingLeft - target.paddingRight)
            touchOffsetY = SelectCursorGeometry.touchOffset(target.height / 2f,
                target.paddingTop, target.height - target.paddingTop - target.paddingBottom)
            dispatch(MotionEvent.ACTION_DOWN, x, y)
            val point = position?.invoke(read) ?: (
                if (top) read.curPage.selectionLeft + 2f to read.curPage.selectionTop + 2f
                else read.curPage.selectionRight - 2f to read.curPage.selectionBottom - 2f)
            moveHandleTo(read, point)
        }
    }

    private fun moveHandleTo(position: (ReadView) -> Pair<Float, Float>) {
        onReader { read -> moveHandleTo(read, position(read)) }
    }

    private fun moveHandleTo(read: ReadView, point: Pair<Float, Float>) {
        val target = cursor!!
        val readerLocation = IntArray(2)
        read.getLocationOnScreen(readerLocation)
        val iconWidth = target.width - target.paddingLeft - target.paddingRight
        val iconHeight = target.height - target.paddingTop - target.paddingBottom
        val movingStart = if (target.id == R.id.cursor_left) !read.curPage.getReverseStartCursor()
            else read.curPage.getReverseEndCursor()
        pointer = floatArrayOf(
            readerLocation[0] + point.first - touchOffsetX - if (movingStart) iconWidth else -iconWidth,
            readerLocation[1] + point.second - touchOffsetY + iconHeight,
        )
        dispatch(MotionEvent.ACTION_MOVE, pointer[0], pointer[1])
    }

    private fun release(action: Int = MotionEvent.ACTION_UP) {
        onReader { dispatch(action, pointer[0], pointer[1]) }
        cursor = null
    }

    private fun dispatch(action: Int, screenX: Float, screenY: Float) {
        val view = cursor!!
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, screenX, screenY, 0)
        event.offsetLocation(-location[0].toFloat(), -location[1].toFloat())
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private fun verifyTextAndAnchors() {
        onReader {
            val bookmark = it.curPage.createBookmark()!!
            val highlight = it.curPage.createHighlight(HighlightStyle())!!
            val chapter = ReadBook.curTextChapter!!
            val canonical = HighlightTextBuilder.build(chapter.pages.flatMap { page ->
                page.lines.map { line -> HighlightTextBuilder.LineInput(line.text, line.isParagraphEnd) }
            })
            val expected = canonical.substring(highlight.chapterPos, highlight.chapterPosEnd)
            assertEquals(expected, it.getSelectText())
            assertEquals(expected, bookmark.bookText)
            assertEquals(expected, highlight.bookText)
            assertEquals(highlight.chapterPos, bookmark.chapterPos)
            assertEquals(0, bookmark.chapterIndex)
            assertTrue("Fixture must actually cover multiple pages", expected.length > 100)
        }
    }

    private fun onReader(block: (ReadView) -> Unit) {
        scenario!!.onActivity { block(it.findViewById(R.id.read_view)) }
    }

    private fun pageIndex(): Int {
        var index = -1
        onReader { index = it.curPage.textPage.index }
        return index
    }

    private fun await(condition: (ReadView) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onReader { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(40)
        }
        fail("Reader condition timed out")
    }

    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(context.getExternalFilesDir(null), "cross-page-selection").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
