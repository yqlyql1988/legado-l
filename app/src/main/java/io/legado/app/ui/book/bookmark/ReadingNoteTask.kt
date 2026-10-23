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
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isLocal
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal enum class ReadingNoteTaskReason {
    PENDING,
    BOOK_UNAVAILABLE,
    LOCAL_FILE_UNAVAILABLE,
    TOC_UNAVAILABLE,
    CHAPTER_NOT_FOUND,
    CHAPTER_AMBIGUOUS,
    SOURCE_UNAVAILABLE,
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
        verify: Boolean = true,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): List<ReadingNoteTask> = withContext(IO) {
        val rows = archives.flatMap { archive ->
            archive.notes.filter { onlyIds == null || "${archive.bookUrl}:${it.id}" in onlyIds }
                .map { archive to it }
        }
        if (rows.isEmpty()) return@withContext emptyList()

        val chaptersByBook = archives.mapNotNull { it.book }
            .distinctBy { it.bookUrl }
            .associate { book ->
                val chapters = if (book.isLocal && !localFileAvailable(book)) {
                    emptyList()
                } else {
                    ReadingNoteChapter.available(book)
                }
                book.bookUrl to BookChapters(chapters, ReadingNoteChapter.index(chapters))
            }
        val savedLocations = appDb.readingNoteDao.locations().groupBy { it.kind to it.noteTime }
        val candidates = rows.map { (archive, note) ->
            val legacy = note.highlight?.let(ReadingNotes::legacy)
                ?: ReadingNotes.legacy(note.bookmark!!, archive.bookUrl)
            val source = savedLocation(legacy, archive.book?.bookUrl, savedLocations).let { saved ->
                archive.book?.let { saved.copy(bookUrl = it.bookUrl) } ?: saved
            }
            val chapter = archive.book?.let { chaptersByBook[it.bookUrl] }
                ?.index?.candidate(source)
            CandidateRow(archive, note, legacy, source, chapter)
        }

        if (!verify) {
            return@withContext candidates.mapNotNull { row ->
                pendingTask(row, row.archive.book?.let { chaptersByBook[it.bookUrl] })
            }
        }

        val targets = LinkedHashMap<ChapterKey, Pair<Book, BookChapter>>()
        candidates.forEach { row ->
            val book = row.archive.book ?: return@forEach
            val chapter = row.chapter ?: return@forEach
            targets.getOrPut(ChapterKey(book.bookUrl, chapter.index)) { book to chapter }
        }
        val bodies = loadBodies(targets, onProgress)

        candidates.mapNotNull { row ->
            val book = row.archive.book
            if (book == null) {
                return@mapNotNull ReadingNoteTask(
                    row.archive, row.note, row.source,
                    reason = ReadingNoteTaskReason.BOOK_UNAVAILABLE
                )
            }
            if (book.isLocal && !localFileAvailable(book)) {
                return@mapNotNull ReadingNoteTask(
                    row.archive, row.note, row.source,
                    reason = ReadingNoteTaskReason.LOCAL_FILE_UNAVAILABLE
                )
            }
            val chapters = chaptersByBook[book.bookUrl]
            if (chapters == null || chapters.list.isEmpty()) {
                return@mapNotNull ReadingNoteTask(
                    row.archive, row.note, row.source,
                    reason = ReadingNoteTaskReason.TOC_UNAVAILABLE
                )
            }
            val chapter = row.chapter ?: return@mapNotNull ReadingNoteTask(
                row.archive, row.note, row.source,
                reason = if (chapters.index.candidateState(row.source) ==
                    ReadingNoteChapter.CandidateState.AMBIGUOUS
                ) ReadingNoteTaskReason.CHAPTER_AMBIGUOUS
                else ReadingNoteTaskReason.CHAPTER_NOT_FOUND
            )
            val body = bodies[ChapterKey(book.bookUrl, chapter.index)]
                ?: return@mapNotNull ReadingNoteTask(
                    row.archive, row.note, row.source, chapter = chapter,
                    reason = if (!book.isLocal && book.getBookSource() == null) {
                        ReadingNoteTaskReason.SOURCE_UNAVAILABLE
                    } else {
                        ReadingNoteTaskReason.CONTENT_UNAVAILABLE
                    }
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

    private fun pendingTask(row: CandidateRow, chapters: BookChapters?): ReadingNoteTask? {
        val book = row.archive.book
        if (book == null) {
            return ReadingNoteTask(row.archive, row.note, row.source,
                reason = ReadingNoteTaskReason.BOOK_UNAVAILABLE)
        }
        if (book.isLocal && !localFileAvailable(book)) {
            return ReadingNoteTask(row.archive, row.note, row.source,
                reason = ReadingNoteTaskReason.LOCAL_FILE_UNAVAILABLE)
        }
        if (chapters == null || chapters.list.isEmpty()) {
            return ReadingNoteTask(row.archive, row.note, row.source,
                reason = ReadingNoteTaskReason.TOC_UNAVAILABLE)
        }
        val chapter = row.chapter ?: return ReadingNoteTask(
            row.archive, row.note, row.source,
            reason = if (chapters.index.candidateState(row.source) ==
                ReadingNoteChapter.CandidateState.AMBIGUOUS
            ) ReadingNoteTaskReason.CHAPTER_AMBIGUOUS
            else ReadingNoteTaskReason.CHAPTER_NOT_FOUND
        )
        if (row.source.updatedAt > 0 && row.source.verified) {
            if (!row.source.quote.isBlank() && ReadingNoteDiff.meaningfulChange(
                    ReadingNotes.anchorQuote(row.legacy), row.source.quote
                )
            ) {
                return ReadingNoteTask(
                    row.archive, row.note, row.source, chapter, row.source,
                    ReadingNoteTaskReason.CHANGED
                )
            }
            return null
        }
        return ReadingNoteTask(
            row.archive, row.note, row.source, chapter = chapter,
            reason = ReadingNoteTaskReason.PENDING
        )
    }

    private suspend fun loadBodies(
        targets: Map<ChapterKey, Pair<Book, BookChapter>>,
        onProgress: ((Int, Int) -> Unit)?,
    ): Map<ChapterKey, String?> {
        if (targets.isEmpty()) return emptyMap()
        val raw = ConcurrentHashMap<ChapterKey, String>()
        val missing = Collections.synchronizedList(
            ArrayList<Pair<ChapterKey, Pair<Book, BookChapter>>>()
        )
        val readSemaphore = Semaphore(4)
        coroutineScope {
            targets.entries.map { (key, target) ->
                async(IO) {
                    readSemaphore.withPermit {
                        val content = BookHelp.getContent(target.first, target.second)
                        if (content == null) {
                            missing.add(key to target)
                        } else {
                            raw[key] = content
                        }
                    }
                }
            }.awaitAll()
        }
        downloadMissing(missing.toList(), raw)

        val total = targets.size
        val done = AtomicInteger()
        val processSemaphore = Semaphore(4)
        return coroutineScope {
            targets.entries.map { (key, target) ->
                async(IO) {
                    processSemaphore.withPermit {
                        val content = raw.remove(key)
                        val body = if (content.isNullOrBlank() ||
                            content.startsWith("获取正文失败")
                        ) {
                            null
                        } else {
                            withContext(Default) {
                                ReadBook.processChapterContent(target.first, target.second, content)
                                    .second.textList.joinToString("\n")
                            }
                        }
                        onProgress?.invoke(done.incrementAndGet(), total)
                        key to body
                    }
                }
            }.awaitAll()
        }.toMap()
    }

    private suspend fun downloadMissing(
        missing: List<Pair<ChapterKey, Pair<Book, BookChapter>>>,
        raw: MutableMap<ChapterKey, String>,
    ) {
        if (missing.isEmpty()) return
        val bookSemaphore = Semaphore(2)
        coroutineScope {
            missing.groupBy { it.second.first.bookUrl }.values.map { items ->
                async(IO) {
                    bookSemaphore.withPermit {
                        val book = items.first().second.first
                        val cacheBook = CacheBook.getOrCreate(book.bookUrl) ?: return@withPermit
                        val chapters = items.map { it.second.second }
                        if (chapters.size >= 2) {
                            val remaining = try {
                                cacheBook.downloadBatchAwait(chapters)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                chapters
                            }
                            for (chapter in remaining) {
                                val content = BookHelp.getContent(book, chapter)
                                    ?: downloadOne(cacheBook, chapter)
                                if (!content.isNullOrBlank() &&
                                    !content.startsWith("获取正文失败")
                                ) {
                                    raw[ChapterKey(book.bookUrl, chapter.index)] = content
                                }
                            }
                        } else {
                            for (chapter in chapters) {
                                val content = downloadOne(cacheBook, chapter)
                                if (!content.isNullOrBlank() &&
                                    !content.startsWith("获取正文失败")
                                ) {
                                    raw[ChapterKey(book.bookUrl, chapter.index)] = content
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun downloadOne(
        cacheBook: CacheBook.CacheBookModel,
        chapter: BookChapter,
    ): String? =
        try {
            cacheBook.downloadAwait(chapter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private fun savedLocation(
        legacy: ReadingNoteLocation,
        preferredBookUrl: String?,
        saved: Map<Pair<String, Long>, List<ReadingNoteLocation>>,
    ): ReadingNoteLocation {
        val locations = saved[legacy.kind to legacy.noteTime].orEmpty()
        return preferredBookUrl?.let { url ->
            locations.firstOrNull { it.bookUrl == url }
        } ?: locations.firstOrNull { it.bookUrl == legacy.bookUrl } ?: legacy
    }

    private fun localFileAvailable(book: Book): Boolean = runCatching {
        LocalBook.getBookInputStream(book).use { }
        true
    }.getOrDefault(false)

    private fun saveIfChanged(row: CandidateRow, resolved: ReadingNoteLocation) {
        if (row.source.copy(updatedAt = 0) == resolved.copy(updatedAt = 0)) return
        ReadingNotes.saveLocation(
            resolved,
            row.archive.book?.name ?: row.note.bookName,
            row.archive.book?.author ?: row.note.author,
        )
    }

    private data class BookChapters(
        val list: List<BookChapter>,
        val index: ReadingNoteChapter.ChapterIndex,
    )

    private data class CandidateRow(
        val archive: NoteBook,
        val note: BookNote,
        val legacy: ReadingNoteLocation,
        val source: ReadingNoteLocation,
        val chapter: BookChapter?,
    )

    private data class ChapterKey(val bookUrl: String, val chapterIndex: Int)
}
