package io.legado.app.help

import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadingNoteLocation
import io.legado.app.data.appDb
import io.legado.app.help.book.getChapterNum
import io.legado.app.help.book.getPureChapterName
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.book.update
import io.legado.app.model.localBook.LocalBook
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

object ReadingNoteChapter {
    /**
     * Reading notes may be restored before the local book's chapter table has been rebuilt.
     * Reuse the normal local-book parser only when the cached directory is missing or stale.
     */
    fun available(book: Book): List<BookChapter> {
        val stored = appDb.bookChapterDao.getChapterList(book.bookUrl)
        val chapters = stored.filterNot { it.isVolume }
        if (!shouldRefreshLocalChapters(book.isLocal, chapters, book.isLocalModified())) {
            return chapters
        }
        return runCatching {
            val loaded = LocalBook.getChapterList(book)
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*loaded.toTypedArray())
            book.update()
            loaded.filterNot { it.isVolume }
        }.getOrElse { chapters }
    }

    internal fun shouldRefreshLocalChapters(
        isLocal: Boolean,
        chapters: List<BookChapter>,
        modified: Boolean,
    ): Boolean = isLocal && (chapters.isEmpty() || modified)

    /** Never drop arbitrary query parameters: they may identify a different chapter or book. */
    fun stableIdentity(url: String): String? = runCatching {
        val query = if (url.startsWith("data:vipContent;base64,")) {
            String(Base64.getDecoder().decode(url.substringAfter("base64,").substringBefore(',')), Charsets.UTF_8)
        } else {
            val uri = URI(url.substringBefore(",{"))
            val host = uri.host?.lowercase() ?: return null
            if (host != "jjwxc.net" && !host.endsWith(".jjwxc.net") &&
                host != "jjwxc.com" && !host.endsWith(".jjwxc.com")) return null
            if (uri.path != "/androidapi/chapterContent") return null
            uri.rawQuery.orEmpty()
        }
        val params = query.split('&').mapNotNull {
            val pair = it.split('=', limit = 2)
            if (pair.size != 2) null
            else URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }.groupBy({ it.first }, { it.second })
        val novel = params["novelId"]?.singleOrNull()?.takeIf { it.matches(Regex("[0-9]+")) } ?: return null
        val chapter = params["chapterId"]?.singleOrNull()?.takeIf { it.matches(Regex("[0-9]+")) } ?: return null
        "jjwxc:$novel:$chapter"
    }.getOrNull()

    fun sameUrl(first: String, second: String): Boolean =
        first.isNotBlank() && (first == second ||
            stableIdentity(first)?.let { it == stableIdentity(second) } == true)

    fun exactIndex(url: String, chapterIndexes: Map<String, Int>): Int? {
        chapterIndexes[url]?.let { return it }
        val identity = stableIdentity(url) ?: return null
        return chapterIndexes.entries.filter { stableIdentity(it.key) == identity }.singleOrNull()?.value
    }

    /** Titles and numbers are candidates only; the quote must be verified against actual text. */
    fun candidate(location: ReadingNoteLocation, chapters: List<BookChapter>): BookChapter? {
        val available = chapters.filterNot { it.isVolume }
        if (location.chapterUrl.isNotBlank()) {
            val exact = available.filter { it.url == location.chapterUrl }
            if (exact.isNotEmpty()) return exact.singleOrNull()
            val stable = available.filter { sameUrl(location.chapterUrl, it.url) }
            if (stable.isNotEmpty()) return stable.singleOrNull()
        }
        var ambiguous = false
        val name = getPureChapterName(location.chapterName)
        if (name.isNotBlank()) {
            val titles = available.filter { getPureChapterName(it.title) == name }
            if (titles.size == 1) return titles.single()
            ambiguous = titles.size > 1
        }
        val number = getChapterNum(location.chapterName)
        if (number > 0) {
            val numbers = available.filter { getChapterNum(it.title) == number }
            if (numbers.size == 1) return numbers.single()
            ambiguous = ambiguous || numbers.size > 1
        }
        if (ambiguous) return null
        return available.singleOrNull { it.index == location.chapterIndex }
    }
}
