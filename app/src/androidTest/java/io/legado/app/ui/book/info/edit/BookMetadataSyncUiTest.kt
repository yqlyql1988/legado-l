package io.legado.app.ui.book.info.edit

import android.content.Intent
import android.os.SystemClock
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadRecord
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.config.OtherConfigFragment
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookMetadataSyncUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.defaultSharedPreferences
    private val savedPreference = prefs.getString(PreferKey.bookMetadataSync, null)
    private var scenario: ActivityScenario<BookInfoEditActivity>? = null
    private val books = mutableListOf<Book>()
    private val bookmarks = mutableListOf<Bookmark>()

    private fun launchBook(): Book {
        val id = UUID.randomUUID().toString()
        val book = Book(bookUrl = id, name = "Old-$id", author = "Author")
        val bookmark = Bookmark(bookName = book.name, bookAuthor = book.author, chapterPos = 19)
        books += book
        bookmarks += bookmark
        appDb.bookDao.insert(book)
        appDb.bookmarkDao.insert(bookmark)
        appDb.readRecordDao.insert(ReadRecord("test-device", book.name, book.author, 100))
        scenario = ActivityScenario.launch(Intent(context, BookInfoEditActivity::class.java).putExtra("bookUrl", id))
        await {
            var loaded = false
            scenario?.onActivity {
                loaded = it.findViewById<EditText>(R.id.tie_book_name).text.toString() == book.name
            }
            loaded
        }
        return book
    }

    private fun editName(book: Book) {
        onView(withId(R.id.tie_book_name)).perform(replaceText("New-${book.bookUrl}"), closeSoftKeyboard())
        onView(withId(R.id.menu_save)).perform(click())
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        assertTrue("Timed out waiting for edit", condition())
    }

    @After
    fun cleanUp() {
        scenario?.close()
        bookmarks.forEach { appDb.bookmarkDao.delete(it) }
        books.forEach {
            appDb.bookDao.delete(it)
            appDb.readRecordDao.deleteByBook(it.name, it.author)
            appDb.readRecordDao.deleteByBook("New-${it.bookUrl}", it.author)
        }
        prefs.edit().apply {
            if (savedPreference == null) remove(PreferKey.bookMetadataSync)
            else putString(PreferKey.bookMetadataSync, savedPreference)
        }.commit()
    }

    @Test
    fun defaultPromptCancelKeepsEditingAndThenSyncMovesTheRelatedData() {
        prefs.edit().remove(PreferKey.bookMetadataSync).commit()
        val book = launchBook()
        editName(book)
        onView(withText(R.string.book_metadata_sync_prompt)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withId(android.R.id.button3)).inRoot(isDialog()).perform(click())
        assertEquals(book.name, appDb.bookDao.getBook(book.bookUrl)?.name)
        assertEquals(bookmarks.single(), appDb.bookmarkDao.getByBook(book.name, book.author).single())
        onView(withId(R.id.tie_book_name)).check(matches(withText("New-${book.bookUrl}")))
        onView(withId(R.id.menu_save)).perform(click())
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        await { appDb.bookDao.getBook(book.bookUrl)?.name == "New-${book.bookUrl}" }
        assertEquals(19, appDb.bookmarkDao.getByBook("New-${book.bookUrl}", book.author).single().chapterPos)
        assertEquals(100L, appDb.readRecordDao.getReadTime("test-device", "New-${book.bookUrl}", book.author))
        assertNull(prefs.getString(PreferKey.bookMetadataSync, null))
    }

    @Test
    fun decliningThePromptSavesTheEditAndRetainsOldBookmarksAndHistory() {
        prefs.edit().putString(PreferKey.bookMetadataSync, "ask").commit()
        val book = launchBook()
        editName(book)
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
        await { appDb.bookDao.getBook(book.bookUrl)?.name == "New-${book.bookUrl}" }
        assertEquals(bookmarks.single(), appDb.bookmarkDao.getByBook(book.name, book.author).single())
        assertEquals(100L, appDb.readRecordDao.getReadTime("test-device", book.name, book.author))
        assertNull(appDb.readRecordDao.getRecord("test-device", "New-${book.bookUrl}", book.author))
    }

    @Test
    fun globalSyncAndNeverChoicesApplyToMultipleBooksWithoutPrompting() {
        for (mode in listOf("sync", "never")) {
            prefs.edit().putString(PreferKey.bookMetadataSync, mode).commit()
            repeat(2) {
                scenario?.close()
                val book = launchBook()
                editName(book)
                await { appDb.bookDao.getBook(book.bookUrl)?.name == "New-${book.bookUrl}" }
                val expectedName = if (mode == "sync") "New-${book.bookUrl}" else book.name
                assertEquals(19, appDb.bookmarkDao.getByBook(expectedName, book.author).single().chapterPos)
                assertEquals(100L, appDb.readRecordDao.getReadTime("test-device", expectedName, book.author))
            }
        }
    }

    @Test
    fun unchangedMetadataSavesWithoutPromptEvenInAskMode() {
        prefs.edit().putString(PreferKey.bookMetadataSync, "ask").commit()
        val book = launchBook()
        onView(withId(R.id.menu_save)).perform(click())
        await { scenario?.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        assertEquals(book.name, appDb.bookDao.getBook(book.bookUrl)?.name)
        assertEquals(100L, appDb.readRecordDao.getReadTime("test-device", book.name, book.author))
    }

    @Test
    fun otherSettingsShowsTheCompactTitleExplanationAndThreePersistentChoices() {
        prefs.edit().remove(PreferKey.bookMetadataSync).commit()
        val intent = Intent(context, ConfigActivity::class.java)
            .putExtra("configTag", ConfigTag.OTHER_CONFIG)
        ActivityScenario.launch<ConfigActivity>(intent).use { config ->
            await {
                var loaded = false
                config.onActivity {
                    val fragment = it.supportFragmentManager
                        .findFragmentByTag(ConfigTag.OTHER_CONFIG) as? OtherConfigFragment
                    if (fragment?.preferenceScreen != null) {
                        fragment.scrollToPreference(PreferKey.bookMetadataSync)
                        loaded = true
                    }
                }
                loaded
            }
            onView(withText(R.string.book_metadata_sync_summary)).check(matches(isDisplayed()))
            onView(withText(R.string.book_metadata_sync_title)).perform(click())
            for (choice in listOf(R.string.book_metadata_sync_yes, R.string.book_metadata_sync_no, R.string.book_metadata_sync_ask)) {
                onView(withText(choice)).inRoot(isDialog()).check(matches(isDisplayed()))
            }
            onView(withText(R.string.book_metadata_sync_yes)).inRoot(isDialog()).perform(click())
            assertEquals("sync", prefs.getString(PreferKey.bookMetadataSync, null))
        }
        val book = launchBook()
        editName(book)
        await { appDb.bookDao.getBook(book.bookUrl)?.name == "New-${book.bookUrl}" }
        assertEquals(100L, appDb.readRecordDao.getReadTime("test-device", "New-${book.bookUrl}", book.author))
    }
}
