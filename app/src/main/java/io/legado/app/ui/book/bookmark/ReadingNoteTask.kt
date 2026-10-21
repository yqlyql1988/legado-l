package io.legado.app.ui.book.bookmark

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReadingNoteLocation
import io.legado.app.help.ReadingNoteAnchor
import io.legado.app.help.ReadingNoteChapter
import io.legado.app.help.ReadingNoteDiff
import io.legado.app.help.ReadingNotes
import io.legado.app.help.book.BookHelp
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal enum class ReadingNoteTaskReason {
    CHAPTER_UNAVAILABLE,
    CONTENT_UNAVAILABLE,
    MULTIPLE_MATCHES,
    NOT_FOUND,
    CHANGED,
}

internal data class ReadingNoteTask(
    val archive: NoteBook,
    val note: BookNote,
    val source: ReadingNoteLocation,
    val chapter: BookChapter? = null,
    val resolved: ReadingNoteLocation? = null,
    val reason: ReadingNoteTaskReason,
    val candidateCount: Int = 0,
) {
    val id get() = "${archive.bookUrl}:${note.id}"
    val beforeQuote get() = note.highlight?.bookText
        ?: note.bookmark?.bookText.orEmpty()
    val afterQuote get() = resolved?.quote.orEmpty()
    val actionable get() = reason != ReadingNoteTaskReason.CHANGED ||
        resolved == null
}

internal object ReadingNoteTaskScanner {

    suspend fun scan(
        archives: List<NoteBook>,
        onlyIds: Set<String>? = null,
    ): List<ReadingNoteTask> = withContext(IO) {
        val rows = archives.flatMap { archive ->
            archive.notes.filter { onlyIds == null || "${archive.bookUrl}:${it.id}" in onlyIds }
                .map { archive to it }
        }
        if (rows.isEmpty()) return@withContext emptyList()

        val chaptersByBook = archives.mapNotNull { it.book }
            .distinctBy { it.bookUrl }
            .associate { book ->
                book.bookUrl to ReadingNoteChapter.available(book)
            }
        val candidates = rows.map { (archive, note) ->
            val legacy = note.highlight?.let(ReadingNotes::legacy)
                ?: ReadingNotes.legacy(note.bookmark!!, archive.bookUrl)
            val source = ReadingNotes.location(legacy)
            val chapters = archive.book?.let { chaptersByBook[it.bookUrl] }.orEmpty()
            val chapter = ReadingNoteChapter.candidate(source, chapters)
            CandidateRow(archive, note, legacy, source, chapter)
        }

        val chapterKeys = candidates.mapNotNull { row ->
            val book = row.archive.book ?: return@mapNotNull null
            val chapter = row.chapter ?: return@mapNotNull null
            ChapterKey(book.bookUrl, chapter.index)
        }.distinct()
        val bodies = HashMap<ChapterKey, String?>()
        val semaphore = Semaphore(2)
        coroutineScope {
            chapterKeys.map { key ->
                async(IO) {
                    key to semaphore.withPermit {
                        val book = appDb.bookDao.getBook(key.bookUrl)
                        val chapter = book?.let {
                            appDb.bookChapterDao.getChapter(it.bookUrl, key.chapterIndex)
                        }
                        if (book == null || chapter == null) {
                            null
                        } else {
                            chapterBody(book, chapter)
                        }
                    }
                }
            }.awaitAll().forEach { (key, body) -> bodies[key] = body }
        }

        candidates.mapNotNull { row ->
            val book = row.archive.book
            if (book == null) {
                return@mapNotNull ReadingNoteTask(
                    row.archive, row.note, row.source,
                    reason = ReadingNoteTaskReason.CHAPTER_UNAVAILABLE
                )
            }
            val chapter = row.chapter ?: return@mapNotNull ReadingNoteTask(
                row.archive, row.note, row.source,
                reason = ReadingNoteTaskReason.CHAPTER_UNAVAILABLE
            )
            val body = bodies[ChapterKey(book.bookUrl, chapter.index)]
                ?: return@mapNotNull ReadingNoteTask(
                    row.archive, row.note, row.source, chapter = chapter,
                    reason = ReadingNoteTaskReason.CONTENT_UNAVAILABLE
                )

            val resolved = ReadingNotes.captured(row.source, chapter, body, 0)
            if (resolved != null) {
                saveIfChanged(row, resolved)
                val original = ReadingNotes.anchorQuote(row.legacy)
                if (ReadingNoteDiff.meaningfulChange(original, resolved.quote)) {
                    return@mapNotNull ReadingNoteTask(
                        row.archive, row.note, row.source, chapter, resolved,
                        ReadingNoteTaskReason.CHANGED
                    )
                }
                return@mapNotNull null
            }

            val contextMatches = ReadingNoteAnchor.contextMatches(
                body, row.source.prefix, row.source.suffix
            )
            if (contextMatches.size == 1) {
                val match = contextMatches.single()
                val candidate = row.source.copy(
                    chapterUrl = chapter.url,
                    chapterIndex = chapter.index,
                    chapterName = chapter.title,
                    chapterPos = match.start,
                    chapterPosEnd = match.end,
                    layoutTitleLength = 0,
                    quote = body.substring(match.start, match.end),
                    prefix = body.substring(
                        (match.start - ReadingNotes.CONTEXT_LENGTH).coerceAtLeast(0), match.start
                    ),
                    suffix = body.substring(
                        match.end, (match.end + ReadingNotes.CONTEXT_LENGTH).coerceAtMost(body.length)
                    ),
                    verified = true,
                    updatedAt = maxOf(System.currentTimeMillis(), row.source.updatedAt + 1),
                )
                saveIfChanged(row, candidate)
                return@mapNotNull ReadingNoteTask(
                    row.archive, row.note, row.source, chapter, candidate,
                    ReadingNoteTaskReason.CHANGED
                )
            }
            val reason = if (contextMatches.size > 1) {
                ReadingNoteTaskReason.MULTIPLE_MATCHES
            } else {
                ReadingNoteTaskReason.NOT_FOUND
            }
            ReadingNoteTask(
                row.archive, row.note, row.source, chapter = chapter,
                reason = reason, candidateCount = contextMatches.size
            )
        }
    }

    private suspend fun chapterBody(book: Book, chapter: BookChapter): String? {
        val raw = BookHelp.getContent(book, chapter)
            ?: CacheBook.getOrCreate(book.bookUrl)?.downloadAwait(chapter)
        if (raw.isNullOrBlank() || raw.startsWith("获取正文失败")) return null
        return withContext(Default) {
            ReadBook.processChapterContent(book, chapter, raw).second.textList.joinToString("\n")
        }
    }

    private fun saveIfChanged(row: CandidateRow, resolved: ReadingNoteLocation) {
        if (row.source.copy(updatedAt = 0) == resolved.copy(updatedAt = 0)) return
        ReadingNotes.saveLocation(resolved, row.note.bookName, row.note.author)
    }

    private data class CandidateRow(
        val archive: NoteBook,
        val note: BookNote,
        val legacy: ReadingNoteLocation,
        val source: ReadingNoteLocation,
        val chapter: BookChapter?,
    )

    private data class ChapterKey(val bookUrl: String, val chapterIndex: Int)
}
