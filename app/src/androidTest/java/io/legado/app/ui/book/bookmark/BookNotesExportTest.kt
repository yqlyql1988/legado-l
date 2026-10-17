package io.legado.app.ui.book.bookmark

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.HighlightTag
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookNotesExportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun originalJsonAndMarkdownExportsKeepAllFieldsAndGroupSameAuthorCorrectly() {
        val marker = UUID.randomUUID().toString()
        val time = System.currentTimeMillis() + 4_000_000
        val entries = listOf(
            Bookmark(time, "书一-$marker", "作者-$marker", 2, 34, "章节一", "原文一\n原文续行", "完整备注一"),
            Bookmark(time + 1, "书二-$marker", "作者-$marker", 3, 56, "章节二", "原文二", "完整备注二"),
            Bookmark(time + 2, "书一-$marker", "另一作者-$marker", 4, 78, "章节三", "原文三", "完整备注三")
        )
        val folder = File(context.cacheDir, "book-notes-export-$marker").apply { mkdirs() }
        var vm: AllBookmarkViewModel? = null
        try {
            appDb.bookmarkDao.insert(*entries.toTypedArray())
            instrumentation.runOnMainSync {
                val model = AllBookmarkViewModel(context.applicationContext as Application)
                vm = model
                model.exportBookmark(Uri.fromFile(folder))
                model.exportBookmarkMd(Uri.fromFile(folder))
            }
            for (attempt in 0 until 160) {
                val files = folder.listFiles().orEmpty()
                val json = files.firstOrNull { it.extension == "json" }
                val md = files.firstOrNull { it.extension == "md" }
                if (json != null && md != null &&
                    runCatching { JsonParser.parseString(json.readText()).isJsonArray }.getOrDefault(false) &&
                    md.readText().contains("完整备注三")) break
                SystemClock.sleep(50)
            }
            val jsonFile = folder.listFiles()!!.single { it.extension == "json" }
            val data = JsonParser.parseString(jsonFile.readText()).asJsonArray
            entries.forEach { entry ->
                val saved = data.map { it.asJsonObject }.single { it["time"].asLong == entry.time }
                assertEquals(entry.bookName, saved["bookName"].asString)
                assertEquals(entry.bookAuthor, saved["bookAuthor"].asString)
                assertEquals(entry.chapterIndex, saved["chapterIndex"].asInt)
                assertEquals(entry.chapterPos, saved["chapterPos"].asInt)
                assertEquals(entry.bookText, saved["bookText"].asString)
                assertEquals(entry.content, saved["content"].asString)
            }
            val markdown = folder.listFiles()!!.single { it.extension == "md" }.readText()
            entries.forEach { entry ->
                assertTrue(markdown.contains("## ${entry.bookName} ${entry.bookAuthor}"))
                assertTrue(markdown.contains(entry.bookText))
                assertTrue(markdown.contains(entry.content))
            }
        } finally {
            vm?.let { model -> instrumentation.runOnMainSync { model.viewModelScope.cancel() } }
            appDb.bookmarkDao.delete(*entries.toTypedArray())
            folder.deleteRecursively()
        }
    }

    @Test fun combinedMarkdownExportKeepsSelectedFullQuotesNotesAndLabels() = runBlocking {
        val folder = File(context.cacheDir, "book-notes-combined-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val quote = "雨停的时候，书店还亮着灯。\n" + "完整原文".repeat(200)
            val selected = listOf(
                BookNote(bookmark = Bookmark(bookName = "长夜书店", content = "书签备注")),
                BookNote(highlight = BookHighlight(bookName = "长夜书店", bookText = quote, note = "个人笔记"),
                    tags = listOf(HighlightTag(name = "伏笔")))
            )
            BookNotesRepository.exportMarkdown(Uri.fromFile(folder), selected)
            val markdown = folder.listFiles()!!.single().readText()
            assertTrue(markdown.contains(quote.lineSequence().joinToString("\n") { "> $it" }))
            assertTrue(markdown.contains("书签备注"))
            assertTrue(markdown.contains("个人笔记"))
            assertTrue(markdown.contains("标签：伏笔"))
        } finally { folder.deleteRecursively() }
    }
}
