package io.legado.app.ui.book.read

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.help.HighlightTextBuilder
import io.legado.app.help.ReadingNotes
import io.legado.app.help.TextSelectMenuConfig
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.bookmark.ReadingNoteNavigation
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.utils.defaultSharedPreferences
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadingNoteNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val savedPrefs = listOf(PreferKey.textSelectAble, PreferKey.longPressSelectParagraph,
        PreferKey.preDownloadNum, PreferKey.textSelectMenuConfig).associateWith { prefs.all[it] }
    private val savedHelp = listOf("readHelpVersion", "readMenuHelpVersion").associateWith { LocalConfig.all[it] }
    private val id = UUID.randomUUID().toString()
    private val source = BookSource(bookSourceUrl = "https://example.invalid/note-source/$id")
    private val book = Book(bookUrl = "https://example.invalid/note-book/$id",
        tocUrl = "https://example.invalid/note-book/$id/toc", origin = source.bookSourceUrl,
        name = "Note fixture $id", type = BookType.text, totalChapterNum = 1, canUpdate = false)
        .apply { setPageAnim(PageAnim.noAnim); setUseReplaceRule(false) }
    private val chapter = BookChapter(bookUrl = book.bookUrl, url = "${book.bookUrl}/new-url",
        title = "第1章 修订后的章名")
    private val highlight = BookHighlight(bookUrl = book.bookUrl, bookName = book.name,
        chapterUrl = "${book.bookUrl}/expired-url?key=old", chapterName = "第一章 旧标题",
        chapterPos = 8, chapterPosEnd = 15, layoutTitleLength = 0,
        bookText = "唯一的原文摘录", note = "原笔记必须保留", style = """{"fill":123}""")
    private val bookmark = Bookmark(time = highlight.time + 1, bookName = book.name,
        chapterName = highlight.chapterName, chapterPos = 8, bookText = highlight.bookText, content = "原书签备注")
    private var scenario: ActivityScenario<ReadBookActivity>? = null

    @Before fun before() {
        prefs.edit().putBoolean(PreferKey.textSelectAble, true)
            .putBoolean(PreferKey.longPressSelectParagraph, true)
            .putInt(PreferKey.preDownloadNum, 0)
            .putString(PreferKey.textSelectMenuConfig, TextSelectMenuConfig.default().toJson()).commit()
        LocalConfig.edit().putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(chapter)
        appDb.bookHighlightDao.insert(highlight)
        appDb.bookmarkDao.insert(bookmark)
    }

    @After fun after() {
        scenario?.close()
        appDb.bookHighlightDao.delete(*appDb.bookHighlightDao.getByBook(book.bookUrl).toTypedArray())
        val bookmarks = appDb.bookmarkDao.getByBook(book.name, book.author)
        appDb.readingNoteDao.deleteLocations("bookmark", bookmarks.map { it.time })
        appDb.bookmarkDao.delete(*bookmarks.toTypedArray())
        appDb.readingNoteDao.deleteLocations("highlight", listOf(highlight.time))
        appDb.readingNoteDao.deleteLocations("bookmark", listOf(bookmark.time))
        BookHelp.clearCache(book)
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        prefs.edit().apply { savedPrefs.forEach { (key, value) ->
            when (value) { null -> remove(key); is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value); is String -> putString(key, value) }
        } }.commit()
        LocalConfig.edit().apply { savedHelp.forEach { (key, value) ->
            if (value == null) remove(key) else putInt(key, value as Int)
        } }.commit()
    }

    @Test fun anySourceCanNavigateRenamedChapterAndRepairBothNoteTypes() {
        val text = (1..45).joinToString("\n") { "修文增加的第${it}段正文。" } + "\n她读到唯一的原文摘录并留下笔记。"
        BookHelp.saveText(book, chapter, text)
        val target = requireNotNull(ReadingNotes.target(ReadingNotes.legacy(highlight), listOf(chapter)))
        assertFalse(target.verified)
        launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl).putExtra("index", target.chapterIndex)
            .putExtra("chapterPos", target.chapterPos)
            .putExtra(TocActivityResult.EXTRA_READING_NOTE_KEY, ReadingNotes.key(target))
            .putExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH, target.layoutTitleLength)
            .putExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT, target.quote))
        var expected = -1
        scenario!!.onActivity {
            val layout = requireNotNull(ReadBook.curTextChapter)
            val full = HighlightTextBuilder.build(layout.pages.flatMap { page ->
                page.lines.map { line -> HighlightTextBuilder.LineInput(line.text, line.isParagraphEnd) }
            })
            expected = full.indexOf(highlight.bookText)
            assertTrue(expected > 100)
            assertEquals(expected, ReadBook.durChapterPos)
            assertEquals(1, ReadBook.anchoredHighlightsOfChapter(layout, layout.layoutTitleLength).size)
        }
        await {
            appDb.bookHighlightDao.getByBook(book.bookUrl).single().chapterPos == expected &&
                appDb.bookmarkDao.getByBook(book.name, book.author).single().chapterPos == expected
        }
        assertEquals(highlight.note, appDb.bookHighlightDao.getByBook(book.bookUrl).single().note)
        assertEquals(highlight.style, appDb.bookHighlightDao.getByBook(book.bookUrl).single().style)
        assertEquals(bookmark.content, appDb.bookmarkDao.getByBook(book.name, book.author).single().content)
    }

    @Test fun repeatedTextWithChangedAddressDoesNotUseOldOffsetsWithoutEvidence() {
        val repeat = highlight.copy(bookText = "重复的一句话")
        appDb.bookHighlightDao.insert(repeat)
        BookHelp.saveText(book, chapter, "重复的一句话。\n不同上下文。\n重复的一句话。")
        launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl).putExtra("index", 0).putExtra("chapterPos", repeat.chapterPos)
            .putExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH, 0)
            .putExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT, repeat.bookText))
        scenario!!.onActivity {
            val layout = requireNotNull(ReadBook.curTextChapter)
            assertEquals(0, ReadBook.durChapterPos)
            assertTrue(ReadBook.anchoredHighlightsOfChapter(layout, layout.layoutTitleLength).isEmpty())
        }
        assertTrue(appDb.readingNoteDao.locationsForNote("highlight", highlight.time).isEmpty())
    }

    @Test fun manualSelectionMovesExistingNoteAndKeepsItsExcerptStyleAndMemo() {
        BookHelp.saveText(book, chapter, "她袖口缝着一小片蓝色丝线。\n这里是作者改写后的正文。")
        launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl).putExtra("index", 0).putExtra("chapterPos", 0)
            .putExtra(ReadingNoteNavigation.EXTRA_KIND, "highlight")
            .putExtra(ReadingNoteNavigation.EXTRA_TIME, highlight.time))
        await {
            var visible = false
            scenario!!.onActivity { activity ->
                visible = activity.findViewById<ReadView>(R.id.read_view).curPage.textPage.lines.any { line ->
                    line.columns.any { it is TextBaseColumn && it.charData == "袖" }
                }
            }
            visible
        }
        onView(withId(R.id.read_view)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isDisplayed()
            override fun getDescription() = "Select replacement text in the reader"
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
        onView(withText(R.string.highlight)).inRoot(isPlatformPopup()).perform(click())
        await { appDb.readingNoteDao.locationsForNote("highlight", highlight.time).isNotEmpty() }
        val saved = appDb.bookHighlightDao.getByBook(book.bookUrl).single()
        assertEquals(highlight.bookText, saved.bookText)
        assertEquals(highlight.note, saved.note)
        assertEquals(highlight.style, saved.style)
        assertEquals(highlight.time, saved.time)
        val location = appDb.readingNoteDao.locationsForNote("highlight", highlight.time).single()
        assertEquals(chapter.url, location.chapterUrl)
        assertTrue(location.quote.contains("袖"))
        assertTrue(location.verified)
    }

    @Test fun noteIdentityKeepsRepeatedQuoteContextAfterEarlierLayoutAlreadyMovedItsPosition() {
        val quote = "重复的一句话"
        val repeated = highlight.copy(bookText = quote, chapterPosEnd = highlight.chapterPos + quote.length)
        appDb.bookHighlightDao.insert(repeated)
        appDb.readingNoteDao.saveLocations(listOf(ReadingNotes.legacy(repeated).copy(
            prefix = "第二处之前", suffix = "后文二", updatedAt = 1)))
        BookHelp.saveText(book, chapter,
            "第一处之前重复的一句话后文一。\n这里添加了新的前言正文。\n第二处之前重复的一句话后文二。")
        launch(Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl))
        await { appDb.readingNoteDao.locationsForNote("highlight", highlight.time).single().chapterUrl == chapter.url }
        var expected = -1
        scenario!!.onActivity {
            val layout = requireNotNull(ReadBook.curTextChapter)
            val full = HighlightTextBuilder.build(layout.pages.flatMap { page ->
                page.lines.map { line -> HighlightTextBuilder.LineInput(line.text, line.isParagraphEnd) }
            })
            expected = full.lastIndexOf(quote)
            assertTrue(expected > repeated.chapterPos)
            ReadBook.openChapter(0, repeated.chapterPos, highlightLayoutTitleLength = 0,
                highlightAnchorText = quote, readingNoteKey = ReadingNotes.key(ReadingNotes.legacy(repeated)))
        }
        await {
            var ready = false
            scenario!!.onActivity {
                ready = ReadBook.curTextChapter?.isCompleted == true && ReadBook.durChapterPos == expected
            }
            ready
        }
    }

    @Test fun legacyWholePageBookmarkReopensWithoutTreatingItsTitleAsChangedBody() {
        BookHelp.saveText(book, chapter,
            (1..55).joinToString("\n") { "第${it}段唯一正文，用于验证完整页面书签退出后仍能定位。" })
        launch(Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl))
        var legacy = bookmark
        scenario!!.onActivity { activity ->
            val page = activity.findViewById<ReadView>(R.id.read_view).curPage.textPage
            assertTrue(page.lines.any { it.isTitle })
            assertFalse(page.bookmarkText.startsWith(chapter.title))
            assertTrue(page.text.trim().startsWith(chapter.title))
            legacy = bookmark.copy(chapterName = chapter.title,
                chapterPos = page.chapterPosition, bookText = page.text.trim())
        }
        appDb.readingNoteDao.deleteLocations("bookmark", listOf(bookmark.time))
        appDb.bookmarkDao.insert(legacy)
        scenario!!.close()
        scenario = null
        val target = requireNotNull(ReadingNotes.target(ReadingNotes.legacy(legacy, book.bookUrl), listOf(chapter)))
        launchBookmark(target)
        scenario!!.onActivity {
            val layout = requireNotNull(ReadBook.curTextChapter)
            assertTrue(ReadBook.durChapterPos >= layout.layoutTitleLength)
            assertTrue(ReadBook.durChapterPos > 0)
        }
        await { appDb.readingNoteDao.locationsForNote("bookmark", bookmark.time).any { it.verified } }
        val saved = appDb.readingNoteDao.locationsForNote("bookmark", bookmark.time).single()
        assertFalse(saved.quote.startsWith(chapter.title))
        assertEquals(legacy.bookText, appDb.bookmarkDao.getByBook(book.name, book.author).single().bookText)
        assertEquals(legacy.content, appDb.bookmarkDao.getByBook(book.name, book.author).single().content)
    }

    @Test fun newWholePageBookmarksInDifferentChaptersSurviveClosingAndReopening() {
        val chapters = (0..2).map { chapter.copy(index = it, url = "${chapter.url}/$it", title = "第${it + 1}章 测试") }
        appDb.bookDao.insert(book.copy(totalChapterNum = chapters.size))
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        appDb.bookmarkDao.delete(bookmark)
        chapters.forEach { row ->
            BookHelp.saveText(book, row,
                (1..60).joinToString("\n") { "章节${row.index}的第${it}段唯一正文，跨章整页书签兼容退出和重新打开。" })
        }
        chapters.forEach { row ->
            launch(Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl)
                .putExtra("index", row.index).putExtra("chapterPos", 0))
            scenario!!.onActivity { it.toggleBookmark() }
            await { appDb.bookmarkDao.getByBook(book.name, book.author).any { it.chapterIndex == row.index } }
            val created = appDb.bookmarkDao.getByBook(book.name, book.author).single { it.chapterIndex == row.index }
            assertFalse(created.bookText.startsWith(row.title))
            await { appDb.readingNoteDao.locationsForNote("bookmark", created.time).any { it.verified } }
            scenario!!.close()
            scenario = null
            val target = requireNotNull(ReadingNotes.target(ReadingNotes.legacy(created, book.bookUrl), chapters))
            launchBookmark(target)
            scenario!!.onActivity {
                assertEquals(row.index, ReadBook.durChapterIndex)
                assertEquals(target.chapterPos, ReadBook.durChapterPos)
                assertTrue(ReadBook.durChapterPos > 0)
            }
            scenario!!.close()
            scenario = null
        }
    }

    @Test fun middlePageBookmarkKeepsItsBodyExcerptAndPositionAfterReopening() {
        BookHelp.saveText(book, chapter,
            (1..90).joinToString("\n") { "第${it}段章中唯一正文，验证书签不会被错误地移到章节开头。" })
        launch(Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl))
        appDb.bookmarkDao.delete(bookmark)
        scenario!!.onActivity {
            assertTrue(requireNotNull(ReadBook.curTextChapter).pageSize > 2)
            ReadBook.skipToPage(2)
        }
        await {
            var ready = false
            scenario!!.onActivity {
                ready = it.findViewById<ReadView>(R.id.read_view).curPage.textPage.index == 2
            }
            ready
        }
        var excerpt = ""
        scenario!!.onActivity { activity ->
            val page = activity.findViewById<ReadView>(R.id.read_view).curPage.textPage
            assertFalse(page.lines.any { it.isTitle })
            excerpt = page.text.trim()
            assertEquals(excerpt, page.bookmarkText)
            activity.toggleBookmark()
        }
        await { appDb.bookmarkDao.getByBook(book.name, book.author).isNotEmpty() }
        val created = appDb.bookmarkDao.getByBook(book.name, book.author).single()
        assertEquals(excerpt, created.bookText)
        await { appDb.readingNoteDao.locationsForNote("bookmark", created.time).any { it.verified } }
        scenario!!.close()
        scenario = null
        val target = requireNotNull(ReadingNotes.target(ReadingNotes.legacy(created, book.bookUrl), listOf(chapter)))
        launchBookmark(target)
        scenario!!.onActivity {
            assertEquals(target.chapterPos, ReadBook.durChapterPos)
            assertTrue(ReadBook.durPageIndex >= 2)
        }
    }

    private fun launchBookmark(target: ReadingNoteLocation) {
        launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", book.bookUrl).putExtra("index", target.chapterIndex)
            .putExtra("chapterPos", target.chapterPos)
            .putExtra(TocActivityResult.EXTRA_READING_NOTE_KEY, ReadingNotes.key(target))
            .putExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH, target.layoutTitleLength)
            .putExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT, target.quote))
    }

    private fun launch(intent: Intent) {
        val expectedChapter = intent.getIntExtra("index", -1)
        scenario = ActivityScenario.launch(intent.putExtra("inBookshelf", true))
        await {
            var ready = false
            scenario!!.onActivity {
                val page = it.findViewById<ReadView>(R.id.read_view).curPage.textPage
                ready = ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.isCompleted == true &&
                    page.textChapter.chapter.bookUrl == book.bookUrl && page.lines.isNotEmpty() &&
                    (expectedChapter < 0 || ReadBook.durChapterIndex == expectedChapter &&
                        page.chapterIndex == expectedChapter)
            }
            ready
        }
        instrumentation.waitForIdleSync()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        do {
            if (condition()) return
            SystemClock.sleep(60)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Reading note fixture was not ready: chapter=${ReadBook.durChapterIndex}, " +
            "size=${ReadBook.chapterSize}, layout=${ReadBook.curTextChapter?.chapter?.index}, " +
            "completed=${ReadBook.curTextChapter?.isCompleted}")
    }
}
