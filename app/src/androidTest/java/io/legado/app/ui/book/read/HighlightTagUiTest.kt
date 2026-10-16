package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HighlightTag
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightTags
import io.legado.app.help.TextSelectMenuConfig
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.ReadBook
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.utils.getCompatColor
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.utils.defaultSharedPreferences
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HighlightTagUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val savedPrefs = listOf(PreferKey.textSelectAble, PreferKey.longPressSelectParagraph,
        PreferKey.highlightLastStyle, PreferKey.preDownloadNum,
        PreferKey.textSelectMenuConfig, PreferKey.highlightMenuConfig).associateWith { prefs.all[it] }
    private val savedHelp = listOf("readHelpVersion", "readMenuHelpVersion").associateWith { LocalConfig.all[it] }
    private val savedTags = appDb.highlightTagDao.all()
    private val savedLinks = appDb.highlightTagDao.links()
    private val savedOrders = appDb.highlightTagDao.orders()
    private val savedTimes = appDb.bookHighlightDao.all.map { it.time }
    private val id = UUID.randomUUID().toString()
    private val source = BookSource(bookSourceUrl = "https://example.invalid/tag-source/$id")
    private val book = Book(bookUrl = "https://example.invalid/tag-book/$id",
        tocUrl = "https://example.invalid/tag-book/$id/toc", origin = source.bookSourceUrl,
        name = "标注标签验证", type = BookType.text, totalChapterNum = 1, canUpdate = false)
        .apply { setPageAnim(PageAnim.noAnim); setUseReplaceRule(false) }
    private val chapter = BookChapter(bookUrl = book.bookUrl, url = "${book.bookUrl}/0", title = "第一章")
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private lateinit var highlight: BookHighlight
    private val tags = HighlightTags.defaults() + (1..9).map {
        HighlightTag(id = "fixture-extra-$it", name = "附加标签$it", sortOrder = 3 + it,
            style = HighlightTags.defaults().first().style)
    }

    @Before fun before() {
        prefs.edit().putBoolean(PreferKey.textSelectAble, true)
            .putBoolean(PreferKey.longPressSelectParagraph, true)
            .putString(PreferKey.textSelectMenuConfig, TextSelectMenuConfig.default().toJson())
            .remove(PreferKey.highlightMenuConfig)
            .putInt(PreferKey.preDownloadNum, 0).commit()
        LocalConfig.edit().putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
        appDb.highlightTagDao.saveTags(tags)
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(chapter)
        BookHelp.saveText(book, chapter,
            "她袖口缝着一小片蓝色丝线。\n" + (1..60).joinToString("\n") { "这是用于标注标签验证的第${it}行正文。" })
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl).putExtra("inBookshelf", true))
        await { view -> view.curPage.textPage.lines.size > 3 && ReadBook.curTextChapter?.isCompleted == true }
        instrumentation.waitForIdleSync()
    }

    @After fun after() {
        scenario?.close()
        appDb.bookHighlightDao.delete(*appDb.bookHighlightDao.getByBook(book.bookUrl).toTypedArray())
        BookHelp.clearCache(book)
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        appDb.highlightTagDao.restore(savedTags, savedLinks, savedTimes, savedOrders)
        prefs.edit().apply {
            savedPrefs.forEach { (key, value) -> when (value) {
                null -> remove(key)
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
            } }
        }.commit()
        LocalConfig.edit().apply {
            savedHelp.forEach { (key, value) -> if (value == null) remove(key) else putInt(key, value as Int) }
        }.commit()
    }

    @Test fun selectTextOpensTwoRowsAndTagsToggleWithoutChangingNotesOrBaseStyle() {
        selectAndHighlight()
        val base = highlight.style
        var actionY = 0
        var labelY = 0
        var labelWidth = 0
        var textBounds: android.graphics.Rect? = null
        scenario!!.onActivity { textBounds = it.findViewById<ReadView>(R.id.read_view).curPage.highlightBounds(highlight) }
        onView(withText(R.string.highlight_note)).inRoot(isPlatformPopup()).check { view, _ ->
            actionY = screenY(view)
            val panel = view.parent.parent.parent.parent as View
            val selection = requireNotNull(textBounds)
            assertTrue("Place both rows outside the visible text: $selection",
                screenY(panel) + panel.height <= selection.top || screenY(panel) >= selection.bottom)
            val density = context.resources.displayMetrics.density
            assertTrue("Comfortable two-row height",
                panel.height in (96 * density).toInt()..(100 * density).toInt())
            assertTrue("Comfortable action target", view.height >= (44 * density).toInt())
            assertEquals("Use the original upstream popup background",
                context.bottomBackground, (panel.background as GradientDrawable).color?.defaultColor)
            assertEquals("Use upstream primary text",
                context.getCompatColor(R.color.primaryText), (view as TextView).currentTextColor)
        }
        onView(withText("赞")).inRoot(isPlatformPopup()).check { view, _ ->
            labelY = screenY(view); labelWidth = view.width
        }
        assertTrue("Labels are the second row", labelY > actionY)
        assertEquals("Both rows use comfortable 44dp targets",
            (44 * context.resources.displayMetrics.density).toInt(), labelY - actionY)
        onView(withContentDescription(R.string.text_menu_zone_more)).inRoot(isPlatformPopup())
            .check(matches(withText("⋮")))
        popupLabel("伏笔").perform(click())
        popupLabel("赞").perform(click())
        onView(withText(R.string.highlight_note)).inRoot(isPlatformPopup()).check { view, _ ->
            assertEquals("Toggling labels keeps the popup stable", actionY, screenY(view))
        }
        popupLabel("赞").check { view, _ ->
            assertTrue(view.isSelected)
            assertEquals("赞", (view as TextView).text.toString())
            assertEquals(labelWidth, view.width)
            assertTrue(view.compoundDrawables.all { it == null })
        }
        assertEquals(tags.first().styleObj(), ReadBook.effectiveHighlightStyle(highlight))
        assertEquals(base, appDb.bookHighlightDao.getByBook(book.bookUrl).single().style)
        screenshot("two-rows")

        // The labels scroll independently while the settings button stays visible.
        var settingsX = 0
        onView(withContentDescription(R.string.highlight_tag_manage)).inRoot(isPlatformPopup())
            .check { view, _ -> settingsX = screenX(view) }
        popupLabel("赞").perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isDisplayed()
            override fun getDescription() = "Scroll the real label row to its final labels"
            override fun perform(ui: UiController, view: View) {
                val scroll = view.parent.parent as HorizontalScrollView
                scroll.smoothScrollTo((view.parent as LinearLayout).width, 0)
                ui.loopMainThreadForAtLeast(500)
            }
        })
        popupLabel("附加标签9").check(matches(isDisplayed()))
        onView(withContentDescription(R.string.highlight_tag_manage)).inRoot(isPlatformPopup())
            .check { view, _ -> assertEquals(settingsX, screenX(view)) }
        screenshot("scrolled-labels")
        onView(withText(R.string.highlight_note)).inRoot(isPlatformPopup()).perform(click())
        onView(withId(R.id.edit_note)).inRoot(isDialog()).perform(replaceText("这条备注跟着标注保留"), closeSoftKeyboard())
        onView(withId(R.id.btn_ok)).inRoot(isDialog()).perform(click())
        assertEquals("这条备注跟着标注保留", appDb.bookHighlightDao.getByBook(book.bookUrl).single().note)
        reopen()
        popupLabel("赞").perform(click())
        assertEquals(tags[2].styleObj(), ReadBook.effectiveHighlightStyle(highlight))
        popupLabel("伏笔").perform(click())
        assertEquals(base, appDb.bookHighlightDao.getByBook(book.bookUrl).single().style)
        assertEquals(appDb.bookHighlightDao.getByBook(book.bookUrl).single().styleObj(),
            ReadBook.effectiveHighlightStyle(highlight))
        assertEquals("这条备注跟着标注保留", appDb.bookHighlightDao.getByBook(book.bookUrl).single().note)
        more()
        onView(withText(R.string.highlight_create_rule)).inRoot(isPlatformPopup()).perform(click())
        scenario!!.onActivity {
            assertTrue(it.supportFragmentManager.fragments.any { fragment ->
                fragment is io.legado.app.ui.highlight.edit.HighlightRuleEditDialog && fragment.dialog?.isShowing == true
            })
        }
        onView(withId(R.id.btn_cancel)).inRoot(isDialog()).perform(click())
        reopen()
        popupLabel("赞").perform(click())
        more()
        onView(withText(R.string.delete)).inRoot(isPlatformPopup()).perform(click())
        assertTrue(appDb.bookHighlightDao.getByBook(book.bookUrl).isEmpty())
        assertTrue(appDb.highlightTagDao.idsForHighlight(highlight.time).isEmpty())
    }

    @Test fun bookManagerEditsSharedLabelsAndKeepsItsOwnOrderAndNewLabelScope() {
        selectAndHighlight()
        popupLabel("伏笔").perform(click())
        onView(withContentDescription(R.string.highlight_tag_manage)).inRoot(isPlatformPopup()).perform(click())
        onView(org.hamcrest.Matchers.allOf(withText("伏笔"),
            isDescendantOfA(withId(R.id.recycler_view)))).check(matches(isDisplayed()))
        screenshot("manager")
        var dragX = 0f
        var startY = 0f
        var endY = 0f
        onView(withId(R.id.recycler_view)).check { view, error ->
            if (error != null) throw error
            val recycler = view as RecyclerView
            val from = requireNotNull(recycler.findViewHolderForAdapterPosition(3)).itemView
                .findViewById<View>(R.id.drag_handle)
            val target = requireNotNull(recycler.findViewHolderForAdapterPosition(0)).itemView
            val start = IntArray(2).also(from::getLocationOnScreen)
            val end = IntArray(2).also(target::getLocationOnScreen)
            dragX = (start[0] + from.width / 2).toFloat()
            startY = (start[1] + from.height / 2).toFloat()
            // Cross the target's edge so ItemTouchHelper commits the final swap.
            endY = end[1] + target.height / 4f
        }
        val down = SystemClock.uptimeMillis()
        fun drag(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, dragX, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        drag(MotionEvent.ACTION_DOWN, startY)
        SystemClock.sleep(650)
        try {
            for (step in 1..20) {
                drag(MotionEvent.ACTION_MOVE, startY + (endY - startY) * step / 20)
                SystemClock.sleep(35)
            }
            SystemClock.sleep(200)
        } finally { drag(MotionEvent.ACTION_UP, endY) }
        instrumentation.waitForIdleSync()
        onView(withId(R.id.recycler_view)).check { view, _ ->
            val recycler = view as RecyclerView
            val visible = (0 until recycler.adapter!!.itemCount).mapNotNull {
                recycler.findViewHolderForAdapterPosition(it)?.itemView
                    ?.findViewById<TextView>(R.id.tv_name)?.text?.toString()
            }
            assertEquals("Visible tag order: $visible", "外貌", visible.first())
        }
        // Scope row text to the recycler because preview chips repeat tag names.
        onView(org.hamcrest.Matchers.allOf(withText("赞"),
            isDescendantOfA(withId(R.id.recycler_view)))).perform(click())
        onView(withId(R.id.et_name)).inRoot(isDialog()).perform(replaceText("喜欢"), closeSoftKeyboard())
        screenshot("edit-label")
        onView(withId(R.id.btn_ok)).inRoot(isDialog()).perform(click())
        onView(withId(R.id.tv_add)).perform(click())
        onView(withId(R.id.et_name)).inRoot(isDialog()).perform(replaceText("情节"), closeSoftKeyboard())
        onView(withId(R.id.btn_ok)).inRoot(isDialog()).perform(click())
        onView(org.hamcrest.Matchers.allOf(withText("伏笔"),
            isDescendantOfA(withId(R.id.recycler_view)))).perform(click())
        onView(withId(R.id.btn_delete)).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withText(R.string.highlight_tag_delete_title)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        // Toolbar back must offer to save the same draft as system back.
        onView(withContentDescription(R.string.back)).perform(click())
        onView(withText(R.string.highlight_tag_unsaved)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withId(android.R.id.button3)).inRoot(isDialog()).perform(click())
        onView(withId(R.id.btn_save)).perform(click())
        popupLabel("喜欢").check(matches(isDisplayed()))
        assertEquals("外貌", appDb.highlightTagDao.forBook(book.bookUrl).first().name)
        assertEquals("喜欢", appDb.highlightTagDao.forBook("another-book").first().name)
        assertTrue(appDb.highlightTagDao.all().any { it.id == tags.first().id && it.name == "喜欢" })
        assertTrue(appDb.highlightTagDao.all().any { it.name == "情节" && it.bookUrl == book.bookUrl })
        assertFalse(appDb.highlightTagDao.forBook("another-book").any { it.name == "情节" })
        assertFalse(appDb.highlightTagDao.all().any { it.name == "伏笔" })
        assertTrue(appDb.highlightTagDao.idsForHighlight(highlight.time).isEmpty())
        assertEquals(highlight.styleObj(), ReadBook.effectiveHighlightStyle(highlight))
    }

    @Test fun tocFiltersSecondaryLabelsLocalLabelsAndUntaggedAnnotationsAlongsideSearch() {
        val first = BookHighlight(bookUrl = book.bookUrl, chapterUrl = chapter.url,
            bookText = "标注甲", note = "保留备注", chapterPosEnd = 3)
        val second = first.copy(time = first.time + 1, bookText = "旧版无标签", note = "")
        val third = first.copy(time = first.time + 2, bookText = "本书线索", note = "")
        val local = HighlightTag(id = "fixture-local-$id", name = "嫌疑人", bookUrl = book.bookUrl)
        appDb.highlightTagDao.saveTag(local)
        appDb.bookHighlightDao.insert(first, second, third)
        appDb.highlightTagDao.toggle(first.time, tags[0].id)
        appDb.highlightTagDao.toggle(first.time, tags[2].id)
        appDb.highlightTagDao.toggle(third.time, local.id)
        val toc = ActivityScenario.launch<io.legado.app.ui.book.toc.TocActivity>(
            Intent(context, io.legado.app.ui.book.toc.TocActivity::class.java).putExtra("bookUrl", book.bookUrl))
        try {
            toc.onActivity { it.findViewById<androidx.viewpager.widget.ViewPager>(R.id.view_pager).setCurrentItem(2, false) }
            fun awaitCount(expected: Int) {
                val deadline = SystemClock.uptimeMillis() + 5000
                do {
                    var count = -1
                    toc.onActivity { activity ->
                        val fragments = activity.supportFragmentManager.fragments
                        val highlightView = fragments.firstOrNull { it is io.legado.app.ui.book.toc.HighlightFragment }?.view
                        count = highlightView?.findViewById<RecyclerView>(R.id.recycler_view)?.adapter?.itemCount ?: -1
                    }
                    if (count == expected) return
                    SystemClock.sleep(60)
                } while (SystemClock.uptimeMillis() < deadline)
                error("Expected $expected filtered annotations")
            }
            fun filter(name: String) = onView(org.hamcrest.Matchers.allOf(withText(name),
                isDescendantOfA(withId(R.id.filter_tags)))).perform(click())
            awaitCount(3)
            onView(org.hamcrest.Matchers.allOf(withText("外貌"), isDescendantOfA(withId(R.id.filter_tags))))
                .check(doesNotExist())
            filter("伏笔")
            awaitCount(1)
            onView(withText("标注甲")).check(matches(isDisplayed()))
            filter("嫌疑人")
            awaitCount(1)
            onView(withText("本书线索")).check(matches(isDisplayed()))
            filter(context.getString(R.string.highlight_filter_untagged))
            awaitCount(1)
            onView(withText("旧版无标签")).check(matches(isDisplayed()))
            onView(withId(R.id.filter_all)).perform(click())
            awaitCount(3)
            filter("伏笔")
            toc.onActivity {
                val model = androidx.lifecycle.ViewModelProvider(it)[io.legado.app.ui.book.toc.TocViewModel::class.java]
                model.searchKey = "不匹配的文字"
                model.startHighlightSearch(model.searchKey)
            }
            awaitCount(0)
            toc.onActivity {
                val model = androidx.lifecycle.ViewModelProvider(it)[io.legado.app.ui.book.toc.TocViewModel::class.java]
                model.searchKey = "保留备注"
                model.startHighlightSearch(model.searchKey)
            }
            awaitCount(1)
            screenshot("toc-label-filter")
            toc.recreate()
            awaitCount(1)
        } finally { toc.close() }
    }

    @Test fun annotationActionsCanBeFoldedAndCustomOrderIsUsed() {
        selectAndHighlight()
        more()
        onView(withText(R.string.highlight_menu_config)).inRoot(isPlatformPopup()).perform(click())
        onView(org.hamcrest.Matchers.allOf(withId(R.id.iv_move), hasSibling(withText(R.string.highlight_note))))
            .inRoot(isDialog()).perform(click())
        androidx.test.espresso.Espresso.pressBack()
        reopen()
        onView(withText(R.string.highlight_note)).inRoot(isPlatformPopup()).check(doesNotExist())
        more()
        onView(withText(R.string.highlight_note)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
        androidx.test.espresso.Espresso.pressBack()
        androidx.test.espresso.Espresso.pressBack()
        scenario!!.onActivity {
            io.legado.app.help.HighlightMenuConfig.save(it,
                TextSelectMenuConfig(listOf("COPY", "STYLE", "NOTE"), listOf("DELETE", "CREATE_RULE")))
        }
        reopen()
        var copyX = 0
        var styleX = 0
        onView(withText(android.R.string.copy)).inRoot(isPlatformPopup()).check { view, _ -> copyX = screenX(view) }
        onView(withText(R.string.highlight_style)).inRoot(isPlatformPopup()).check { view, _ -> styleX = screenX(view) }
        assertTrue(copyX < styleX)
    }

    private fun selectAndHighlight() {
        onView(withId(R.id.read_view)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isDisplayed()
            override fun getDescription() = "Long press real body text before choosing highlight"
            override fun perform(ui: UiController, view: View) {
                val reader = view as ReadView
                val page = reader.curPage
                val line = page.textPage.lines.first { row ->
                    row.columns.any { it is TextBaseColumn && it.charData == "袖" }
                }
                val column = line.columns.first { it is TextBaseColumn && it.charData == "袖" }
                val x = (column.start + column.end) / 2 + page.imgBgPaddingStart
                val y = (line.lineTop + line.lineBottom) / 2 + page.headerHeight
                val down = SystemClock.uptimeMillis()
                fun dispatch(action: Int) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                dispatch(MotionEvent.ACTION_DOWN)
                ui.loopMainThreadForAtLeast(750)
                dispatch(MotionEvent.ACTION_UP)
                ui.loopMainThreadForAtLeast(200)
                assertTrue(reader.isTextSelected)
            }
        })
        screenshot("selection")
        onView(withText(R.string.highlight)).inRoot(isPlatformPopup()).perform(click())
        instrumentation.waitForIdleSync()
        highlight = appDb.bookHighlightDao.getByBook(book.bookUrl).single()
        assertTrue(highlight.bookText.isNotBlank())
        popupLabel("赞").check(matches(isDisplayed()))
    }

    private fun reopen() {
        scenario!!.onActivity {
            highlight = ReadBook.highlights.single()
            it.onHighlightClick(highlight, 100f, 250f)
        }
        instrumentation.waitForIdleSync()
    }
    private fun more() = onView(withContentDescription(R.string.text_menu_zone_more)).inRoot(isPlatformPopup()).perform(click())
    private fun popupLabel(name: String) = onView(withText(name)).inRoot(isPlatformPopup())
    private fun screenY(view: View) = IntArray(2).also(view::getLocationOnScreen)[1]
    private fun screenX(view: View) = IntArray(2).also(view::getLocationOnScreen)[0]
    private fun await(condition: (ReadView) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        do {
            var ready = false
            scenario!!.onActivity { ready = condition(it.findViewById(R.id.read_view)) }
            if (ready) return
            SystemClock.sleep(60)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Reader fixture was not ready")
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(context.getExternalFilesDir(null), "tag-ui").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }
}
