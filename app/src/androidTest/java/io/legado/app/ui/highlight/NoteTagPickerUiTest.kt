package io.legado.app.ui.highlight

import android.content.Intent
import android.graphics.Bitmap
import android.widget.CheckBox
import org.hamcrest.Matchers.allOf
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.*
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.ui.book.bookmark.AllBookmarkActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NoteTagPickerUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val token = UUID.randomUUID().toString()
    private val book = Book(bookUrl = "picker-$token", name = "标签选择验证-$token", author = "测试作者")
    private val time = System.currentTimeMillis()
    private val own = HighlightTag("own-$token", "待回看${token.take(3)}", bookUrl = book.bookUrl, common = false)
    private val library = HighlightTag("library-$token", "伏笔${token.take(3)}", bookUrl = "other-$token", common = false)
    private var scenario: ActivityScenario<AllBookmarkActivity>? = null
    private val created = "新建标签-${token.take(6)}"

    @Before fun before() {
        appDb.bookDao.insert(book)
        appDb.bookmarkDao.insert(Bookmark(time = time, bookName = book.name, bookAuthor = book.author,
            chapterName = "第一章", bookText = "测试摘录", content = "测试备注"))
        appDb.highlightTagDao.saveTag(own)
        appDb.highlightTagDao.saveTag(library)
        appDb.bookmarkTagDao.upsertOrders(listOf(BookmarkTagOrder(book.bookUrl, own.id, 0)))
        appDb.bookmarkTagDao.saveLinks(time, book.bookUrl, listOf(own.id))
        scenario = ActivityScenario.launch(Intent(context, AllBookmarkActivity::class.java).putExtra("bookUrl", book.bookUrl))
        scenario!!.onActivity { activity ->
            NoteTagPickerDialog.create(book.bookUrl, true, listOf(own.id), time)
                .show(activity.supportFragmentManager, "test-picker")
        }
    }
    @After fun after() {
        scenario?.close()
        appDb.bookmarkDao.delete(*appDb.bookmarkDao.getByBook(book.name, book.author).toTypedArray())
        appDb.highlightTagDao.deleteTags(appDb.highlightTagDao.all().filter {
            it.id == own.id || it.id == library.id || it.name == created
        })
        appDb.bookmarkTagDao.clearOrder(book.bookUrl)
        appDb.bookDao.delete(book)
    }

    @Test fun librarySearchAndRecreationPreserveBothSelectionsAndBottomActions() {
        onView(withText(own.name)).check(matches(isChecked()))
        onView(withText(R.string.highlight_tag_library)).perform(click())
        onView(withHint(R.string.note_tag_search)).perform(replaceText(library.name))
        closeSoftKeyboard()
        onView(allOf(withText(library.name), isAssignableFrom(CheckBox::class.java))).perform(click())
        scenario!!.recreate()
        onView(allOf(withText(library.name), isAssignableFrom(CheckBox::class.java))).check(matches(isChecked()))
        onView(withHint(R.string.note_tag_search)).perform(replaceText(""))
        closeSoftKeyboard()
        onView(withText(R.string.note_tag_book_list)).perform(click())
        onView(withText(own.name)).check(matches(isChecked()))
        onView(withText(library.name)).check(matches(isChecked()))
        screenshot("picker-bottom-actions")
        onView(withText(R.string.ok)).perform(click())
        repeat(100) {
            if (appDb.bookmarkTagDao.idsForBookmark(time).toSet() != setOf(own.id, library.id))
                android.os.SystemClock.sleep(50)
        }
        assertEquals(setOf(own.id, library.id), appDb.bookmarkTagDao.idsForBookmark(time).toSet())
        assertTrue(appDb.highlightTagDao.links().none { it.highlightTime == time })
        onView(withText(library.name)).check(matches(isDisplayed()))
        scenario!!.onActivity { activity ->
            val labels = activity.findViewById<android.view.ViewGroup>(R.id.tag_labels)
            assertEquals(2, labels.childCount)
            for (i in 0 until labels.childCount) {
                val chip = labels.getChildAt(i) as android.widget.TextView
                val margins = chip.layoutParams as android.view.ViewGroup.MarginLayoutParams
                val density = context.resources.displayMetrics.density
                assertEquals((6 * density + .5f).toInt(), margins.marginEnd)
                assertEquals((6 * density + .5f).toInt(), margins.bottomMargin)
                assertEquals(12f * context.resources.displayMetrics.scaledDensity, chip.textSize, .5f)
            }
        }
        screenshot("notes-compact-spaced-labels")
    }

    @Test fun newLabelReturnsSelectedAndCancelLeavesBookmarkAssociationUnchanged() {
        onView(withText(R.string.note_tag_new)).perform(click())
        onView(withId(R.id.et_name)).perform(replaceText(created))
        closeSoftKeyboard()
        onView(withText(R.string.action_save)).perform(click())
        onView(withText(created)).check(matches(isChecked()))
        onView(withText(R.string.cancel)).perform(click())
        assertEquals(listOf(own.id), appDb.bookmarkTagDao.idsForBookmark(time))
        assertEquals(1, appDb.highlightTagDao.all().count { it.name == created })
    }

    @Test fun landscapeKeepsSearchAndNewActionsVisible() {
        scenario!!.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        onView(withHint(R.string.note_tag_search)).check(matches(isDisplayed()))
        onView(withText(R.string.note_tag_new)).check(matches(isDisplayed()))
        onView(withText(own.name)).check(matches(isChecked()))
        screenshot("picker-landscape")
    }

    private fun screenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(context.getExternalFilesDir(null), "bookmark-tags-ui").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
