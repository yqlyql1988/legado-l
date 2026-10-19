package io.legado.app.help

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.HighlightTagOrder
import io.legado.app.data.entities.ReadingNoteLocation
import io.legado.app.data.entities.ReadingNoteLocation.Companion.BOOKMARK
import io.legado.app.data.entities.ReadingNoteLocation.Companion.HIGHLIGHT
import io.legado.app.data.entities.ReadingNoteSource
import java.util.UUID
import java.util.concurrent.Callable

/** Legacy records remain the current-source projection; history lives in optional extension tables. */
object ReadingNotes {
    const val LOCATION_FILE = "readingNoteLocation.json"
    const val SOURCE_FILE = "readingNoteSource.json"
    const val CONTEXT_LENGTH = 50

    fun key(location: ReadingNoteLocation) = "${location.kind}:${location.noteTime}"

    fun legacy(highlight: BookHighlight) = ReadingNoteLocation(
        kind = HIGHLIGHT, noteTime = highlight.time, bookUrl = highlight.bookUrl,
        chapterUrl = highlight.chapterUrl, chapterIndex = highlight.chapterIndex,
        chapterName = highlight.chapterName, chapterPos = highlight.chapterPos,
        chapterPosEnd = highlight.chapterPosEnd, layoutTitleLength = highlight.layoutTitleLength,
        quote = highlight.bookText, verified = true, updatedAt = 0
    )

    fun legacy(bookmark: Bookmark, bookUrl: String) = ReadingNoteLocation(
        kind = BOOKMARK, noteTime = bookmark.time, bookUrl = bookUrl,
        chapterIndex = bookmark.chapterIndex, chapterName = bookmark.chapterName,
        chapterPos = bookmark.chapterPos, chapterPosEnd = bookmark.chapterPos + bookmark.bookText.length,
        quote = bookmark.bookText, verified = false, updatedAt = 0
    )

    fun location(legacy: ReadingNoteLocation): ReadingNoteLocation =
        appDb.readingNoteDao.locationsForNote(legacy.kind, legacy.noteTime)
            .firstOrNull { it.bookUrl == legacy.bookUrl } ?: legacy

    fun target(legacy: ReadingNoteLocation, chapters: List<BookChapter>): ReadingNoteLocation? {
        val saved = location(legacy)
        val chapter = ReadingNoteChapter.candidate(saved, chapters) ?: return null
        val trusted = saved.verified && ReadingNoteChapter.sameUrl(saved.chapterUrl, chapter.url)
        if (saved.quote.isBlank() && !trusted) return null
        return saved.copy(chapterUrl = chapter.url, chapterIndex = chapter.index,
            verified = trusted)
    }

    fun captured(
        location: ReadingNoteLocation,
        chapter: BookChapter,
        bodyText: String,
        titleLength: Int
    ): ReadingNoteLocation? {
        if (location.bookUrl != chapter.bookUrl) return null
        val position = (location.chapterPos -
            location.layoutTitleLength.takeIf { it >= 0 }.let { it ?: titleLength }).coerceAtLeast(0)
        val match = ReadingNoteAnchor.find(bodyText, location.quote, position,
            location.prefix, location.suffix, location.verified) ?: return null
        return location.copy(
            chapterUrl = chapter.url, chapterIndex = chapter.index, chapterName = chapter.title,
            chapterPos = match.start + titleLength, chapterPosEnd = match.end + titleLength,
            layoutTitleLength = titleLength, quote = bodyText.substring(match.start, match.end),
            prefix = bodyText.substring((match.start - CONTEXT_LENGTH).coerceAtLeast(0), match.start),
            suffix = bodyText.substring(match.end, (match.end + CONTEXT_LENGTH).coerceAtMost(bodyText.length)),
            verified = true, updatedAt = maxOf(System.currentTimeMillis(), location.updatedAt + 1)
        )
    }

    /** A late layout job cannot recreate a deleted note or replace a newer manual position. */
    fun saveLocation(location: ReadingNoteLocation, bookName: String, author: String): Boolean =
        appDb.runInTransaction(Callable {
            val exists = when (location.kind) {
                HIGHLIGHT -> appDb.bookHighlightDao.getByBook(location.bookUrl).any { it.time == location.noteTime }
                BOOKMARK -> appDb.bookmarkDao.getByBook(bookName, author).any { it.time == location.noteTime }
                else -> false
            }
            if (!exists) return@Callable false
            val dao = appDb.readingNoteDao
            val previous = dao.locationsForNote(location.kind, location.noteTime)
                .firstOrNull { it.bookUrl == location.bookUrl }
            if (previous != null && previous.updatedAt >= location.updatedAt) return@Callable false
            val active = dao.sources().firstOrNull { it.bookUrl == location.bookUrl }?.activeBookUrl
            if (active != null && active != location.bookUrl) return@Callable false
            dao.saveLocations(listOf(location))
            if (location.kind == HIGHLIGHT) {
                appDb.bookHighlightDao.updateLocation(location.noteTime, location.bookUrl,
                    location.chapterUrl, location.chapterIndex, location.chapterName,
                    location.chapterPos, location.chapterPosEnd, location.layoutTitleLength)
            } else {
                appDb.bookmarkDao.updateLocation(location.noteTime, bookName, author,
                    location.chapterIndex, location.chapterPos, location.chapterName)
            }
            true
        })

    /** Called in the same transaction as the explicit source change, before old chapters are removed. */
    fun changeSource(oldBook: Book, newBook: Book, chapters: List<BookChapter>) {
        if (oldBook.bookUrl == newBook.bookUrl) return
        val dao = appDb.readingNoteDao
        val previousSources = dao.sources()
        val relatedIds = previousSources.filter {
            it.bookUrl == oldBook.bookUrl || it.bookUrl == newBook.bookUrl
        }.map { it.workId }.toSet()
        val workId = previousSources.firstOrNull { it.bookUrl == oldBook.bookUrl }?.workId
            ?: previousSources.firstOrNull { it.bookUrl == newBook.bookUrl }?.workId
            ?: UUID.randomUUID().toString()
        val urls = previousSources.filter { it.workId in relatedIds }.map { it.bookUrl }.toSet() +
            oldBook.bookUrl + newBook.bookUrl
        val now = System.currentTimeMillis()
        dao.saveSources(urls.map { ReadingNoteSource(it, workId, newBook.bookUrl, now) })
        val originalChapters = appDb.bookChapterDao.getChapterList(oldBook.bookUrl)
        val highlights = appDb.bookHighlightDao.all.filter { it.bookUrl in urls && it.bookUrl != newBook.bookUrl }
        highlights.forEach { highlight ->
            val original = location(legacy(highlight))
            dao.saveLocations(listOf(original))
            val restored = dao.locationsForNote(HIGHLIGHT, highlight.time)
                .firstOrNull { it.bookUrl == newBook.bookUrl }
            val candidate = restored ?: original.copy(bookUrl = newBook.bookUrl, verified = false)
            val chapter = ReadingNoteChapter.candidate(candidate, chapters)
            val next = candidate.copy(
                chapterUrl = chapter?.url.orEmpty(), chapterIndex = chapter?.index ?: -1,
                verified = candidate.verified && chapter != null &&
                    ReadingNoteChapter.sameUrl(candidate.chapterUrl, chapter.url),
                updatedAt = now
            )
            dao.saveLocations(listOf(next))
            appDb.bookHighlightDao.update(highlight.copy(
                bookUrl = newBook.bookUrl,
                bookName = newBook.name, bookAuthor = newBook.author,
                chapterUrl = chapter?.url ?: "unresolved:${highlight.time}",
                chapterIndex = chapter?.index ?: -1,
                chapterName = chapter?.title ?: highlight.chapterName,
                chapterPos = next.chapterPos, chapterPosEnd = next.chapterPosEnd,
                layoutTitleLength = next.layoutTitleLength
            ))
        }
        appDb.bookmarkDao.getByBook(oldBook.name, oldBook.author).forEach { bookmark ->
            val original = location(legacy(bookmark, oldBook.bookUrl)).let { saved ->
                if (saved.chapterUrl.isNotBlank()) saved else saved.copy(
                    chapterUrl = ReadingNoteChapter.candidate(saved, originalChapters)?.url.orEmpty()
                )
            }
            dao.saveLocations(listOf(original))
            val restored = dao.locationsForNote(BOOKMARK, bookmark.time)
                .firstOrNull { it.bookUrl == newBook.bookUrl }
            val candidate = restored ?: original.copy(bookUrl = newBook.bookUrl, verified = false)
            val chapter = ReadingNoteChapter.candidate(candidate, chapters)
            val next = candidate.copy(chapterUrl = chapter?.url.orEmpty(),
                chapterIndex = chapter?.index ?: -1, updatedAt = now,
                verified = candidate.verified && chapter != null &&
                    ReadingNoteChapter.sameUrl(candidate.chapterUrl, chapter.url))
            dao.saveLocations(listOf(next))
            if (restored != null && chapter != null) {
                appDb.bookmarkDao.update(bookmark.copy(
                    chapterIndex = chapter.index, chapterPos = next.chapterPos, chapterName = chapter.title
                ))
            }
        }
        if (oldBook.name != newBook.name || oldBook.author != newBook.author) {
            appDb.bookmarkDao.updateBookMetadata(oldBook.name, oldBook.author, newBook.name, newBook.author)
        }
        // Tag IDs and links stay intact. Retain source-local label availability in the new source.
        val tagDao = appDb.highlightTagDao
        val existing = tagDao.ordersForBook(newBook.bookUrl).mapTo(hashSetOf()) { it.tagId }
        val oldOrders = tagDao.ordersForBook(oldBook.bookUrl).associateBy { it.tagId }
        val retained = (tagDao.forBook(oldBook.bookUrl, includeHidden = true).map { it.id } +
            highlights.flatMap { tagDao.idsForHighlight(it.time) }).distinct().filter { it !in existing }
        tagDao.upsertOrders(retained.mapIndexed { index, id ->
            HighlightTagOrder(newBook.bookUrl, id, existing.size + index, oldOrders[id]?.hidden ?: false)
        })
    }

    /** Extension files are merged, never cleared just because an older backup lacks them. */
    fun restore(
        locations: List<ReadingNoteLocation>?,
        sources: List<ReadingNoteSource>?,
        highlights: List<BookHighlight>,
        bookmarks: List<Bookmark>
    ) {
        val dao = appDb.readingNoteDao
        val highlightIds = highlights.mapTo(hashSetOf()) { it.time }
        val bookmarkIds = bookmarks.mapTo(hashSetOf()) { it.time }
        val currentLocations = dao.locations().associateBy { Triple(it.kind, it.noteTime, it.bookUrl) }
        dao.saveLocations(locations.orEmpty().filter {
            it.bookUrl.isNotBlank() && it.chapterIndex >= -1 &&
                it.chapterPos >= 0 && it.chapterPosEnd >= it.chapterPos &&
                it.layoutTitleLength >= -1 && it.updatedAt in 0 until Long.MAX_VALUE &&
                (it.kind == HIGHLIGHT && it.noteTime in highlightIds ||
                    it.kind == BOOKMARK && it.noteTime in bookmarkIds) &&
                it.updatedAt >= (currentLocations[Triple(it.kind, it.noteTime, it.bookUrl)]?.updatedAt ?: -1)
        })
        val currentSources = dao.sources().associateBy { it.bookUrl }
        dao.saveSources(sources.orEmpty().filter {
            it.bookUrl.isNotBlank() && it.workId.isNotBlank() && it.activeBookUrl.isNotBlank() &&
                it.updatedAt >= 0 &&
                it.updatedAt >= (currentSources[it.bookUrl]?.updatedAt ?: -1)
        })
        // A legacy backup may still refer to an earlier source of an explicitly linked work.
        val activeSources = dao.sources().associateBy { it.bookUrl }
        highlights.forEach { highlight ->
            val active = activeSources[highlight.bookUrl]?.activeBookUrl ?: return@forEach
            if (active == highlight.bookUrl || !appDb.bookDao.has(active)) return@forEach
            val original = legacy(highlight)
            if (dao.locationsForNote(HIGHLIGHT, highlight.time).none { it.bookUrl == original.bookUrl }) {
                dao.saveLocations(listOf(original))
            }
            val candidate = dao.locationsForNote(HIGHLIGHT, highlight.time).firstOrNull { it.bookUrl == active }
                ?: original.copy(bookUrl = active, verified = false)
            val chapter = ReadingNoteChapter.candidate(candidate, appDb.bookChapterDao.getChapterList(active))
            dao.saveLocations(listOf(candidate.copy(chapterUrl = chapter?.url.orEmpty(),
                chapterIndex = chapter?.index ?: -1, verified = candidate.verified && chapter != null &&
                    ReadingNoteChapter.sameUrl(candidate.chapterUrl, chapter.url))))
            appDb.bookHighlightDao.update(highlight.copy(bookUrl = active,
                chapterUrl = chapter?.url ?: "unresolved:${highlight.time}",
                chapterIndex = chapter?.index ?: -1,
                chapterName = chapter?.title ?: candidate.chapterName,
                chapterPos = candidate.chapterPos, chapterPosEnd = candidate.chapterPosEnd,
                layoutTitleLength = candidate.layoutTitleLength))
        }
    }
}
