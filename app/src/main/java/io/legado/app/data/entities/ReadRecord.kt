package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.help.book.ReadRecordCoverCache
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.ReadDayDuration
import io.legado.app.help.book.splitReadInterval
import io.legado.app.help.book.ReadHourDuration
import io.legado.app.help.book.splitReadHourlyInterval
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import java.util.TimeZone

@Entity(
    tableName = "readRecord",
    primaryKeys = ["deviceId", "bookName", "author"],
    indices = [Index(
        name = "index_readRecord_snapshot",
        value = ["bookName", "author", "lastRead", "deviceId", "resolvedAuthor"],
        orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC, Index.Order.ASC, Index.Order.ASC],
    )],
)
data class ReadRecord(
    var deviceId: String = "",
    var bookName: String = "",
    /**
     * 同设备按书名和作者分别记录；未知作者在重读时补全，旧合并作者记录原样保留。
     */
    @ColumnInfo(defaultValue = "")
    var author: String = "",
    @ColumnInfo(defaultValue = "0")
    var readTime: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    var lastRead: Long = System.currentTimeMillis(),
    /** Snapshot fields keep the record useful after its bookshelf row is removed. */
    var lastChapterTitle: String? = null,
    @ColumnInfo(defaultValue = "-1")
    var lastChapterIndex: Int = -1,
    @ColumnInfo(defaultValue = "0")
    var lastChapterPos: Int = 0,
    var coverUrl: String? = null,
    /** Keep the original blank-author account stable when restoring older backups. */
    var resolvedAuthor: String? = null,
    /** Effective text characters counted while moving forward through the reader. */
    @ColumnInfo(defaultValue = "0")
    var readWords: Long = 0L,
)

fun ReadRecord.updateSnapshot(
    book: Book,
    chapterIndex: Int = book.durChapterIndex,
    chapterPos: Int = book.durChapterPos,
) {
    if (book.name != bookName || book.author != author) return
    lastChapterIndex = chapterIndex
    book.durChapterTitle?.takeIf { it.isNotBlank() }?.let { lastChapterTitle = it }
    lastChapterPos = chapterPos
    book.getDisplayCover()?.takeIf { it.isNotBlank() }?.let { coverUrl = it }
}

fun ReadRecord.saveWithCover(book: Book?, elapsed: Long? = null, readWords: Long = 0L) {
    val zone = TimeZone.getDefault()
    saveWithCover(book, elapsed, readWords, null,
        elapsed?.let { splitReadInterval(lastRead, it, zone) }.orEmpty(),
        elapsed?.let { splitReadHourlyInterval(lastRead, it, zone) }.orEmpty())
}

/** Capture routing while the reader still holds its record lock, before submitting the write. */
fun ReadRecord.prepareSaveWithCover(book: Book?, elapsed: Long, readWords: Long = 0L): () -> Unit {
    val ticket = book?.let { readRecordMetadataRoutes.capture(it.bookUrl, bookName, author) }
    val zone = TimeZone.getDefault()
    val intervals = splitReadInterval(lastRead, elapsed, zone)
    val hours = splitReadHourlyInterval(lastRead, elapsed, zone)
    return { saveWithCover(book, elapsed, readWords, ticket, intervals, hours) }
}

private fun ReadRecord.saveWithCover(
    book: Book?,
    elapsed: Long?,
    readWords: Long,
    ticket: ReadRecordMetadataRoutes.Ticket?,
    intervals: List<ReadDayDuration>,
    hours: List<ReadHourDuration>,
) {
    ticket?.let {
        val identity = readRecordMetadataRoutes.resolve(it)
        bookName = identity.name
        author = identity.author
        resolvedAuthor = null
    }
    val routedBook = book?.let {
        if (ticket == null) it else it.copy(name = bookName, author = author)
    }
    val snapshotBook = routedBook?.takeIf { it.name == bookName && it.author == author }
    refreshChapterTitle(snapshotBook)
    var saved = this
    appDb.runInTransaction {
        if (elapsed != null && snapshotBook != null) appDb.readRecordDao.resolveUnknownAuthor(bookName, author)
        val current = appDb.readRecordDao.getRecord(deviceId, bookName, author)
        if (current == null && elapsed != null) {
            appDb.readRecordDao.insertHistory(ReadRecordHistory(deviceId, bookName, author, 0))
        }
        if (elapsed != null) {
            // A reader can revisit this identity before an earlier interval reaches the queue.
            // Add the interval to the stored total instead of replacing it with a stale total.
            val snapshot = current?.takeIf { it.lastRead > lastRead } ?: this
            saved = snapshot.copy(
                readTime = (current?.readTime ?: 0L) + elapsed.coerceAtLeast(0L),
                readWords = Math.addExact(current?.readWords ?: 0L, readWords.coerceAtLeast(0L)),
            )
        }
        saved = saved.copy(resolvedAuthor = current?.resolvedAuthor ?: saved.resolvedAuthor)
        appDb.readRecordDao.insert(saved)
        appDb.readRecordDao.addDaily(saved, intervals, lastRead, readWords)
        appDb.readRecordDao.addHourly(saved, hours, readWords)
    }
    ReadRecordCoverCache.request(saved.copy(), snapshotBook?.getCoverSourceOrigin())
}

private fun ReadRecord.refreshChapterTitle(snapshotBook: Book?) {
    if (snapshotBook != null && lastChapterIndex >= 0) {
        appDb.bookChapterDao.getChapter(snapshotBook.bookUrl, lastChapterIndex)?.let { chapter ->
            lastChapterTitle = chapter.getDisplayTitle(
                ContentProcessor.get(snapshotBook.name, snapshotBook.origin).getTitleReplaceRules(),
                snapshotBook.getUseReplaceRule(),
                replaceBook = snapshotBook.toReplaceBook(),
            )
        }
    }
}

/** Copy the bookshelf data before deleting it so the history row remains displayable. */
fun Book.saveReadRecordSnapshot() {
    var snapshot: ReadRecord? = null
    appDb.runInTransaction {
        val current = appDb.readRecordDao.getRecord(AppConst.androidId, name, author) ?: return@runInTransaction
        val record = current.copy().apply {
            updateSnapshot(this@saveReadRecordSnapshot)
            refreshChapterTitle(this@saveReadRecordSnapshot)
            coverUrl = ReadRecordCoverCache.retainLocal(coverUrl)
        }
        if (record != current) appDb.readRecordDao.update(record)
        snapshot = record
    }
    snapshot?.let { ReadRecordCoverCache.request(it, getCoverSourceOrigin()) }
}

/** Decode historical combined-author rows without assigning their duration to one author. */
internal object ReadRecordAuthors {
    private const val PREFIX = "\u001Eauthors:"
    const val AGGREGATE_SEPARATOR = "\u001F"

    fun decode(value: String): Set<String> {
        if (value.isBlank()) return setOf("")
        if (!value.startsWith(PREFIX)) return setOf(value)
        return GSON.fromJsonArray<String>(value.removePrefix(PREFIX))
            .getOrNull()
            ?.filterTo(linkedSetOf()) { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: setOf("")
    }

    fun merge(current: String, incoming: String): String {
        val authors = sortedSetOf<String>()
        decode(current).filterTo(authors) { it.isNotBlank() }
        decode(incoming).filterTo(authors) { it.isNotBlank() }
        return when (authors.size) {
            0 -> ""
            1 -> authors.first()
            else -> PREFIX + GSON.toJsonTree(authors).toString()
        }
    }

    fun isCombined(value: String): Boolean = value.startsWith(PREFIX)

    /** Converts legacy encodings into a stable, human-readable author list. */
    fun display(value: String): String {
        if (value.isBlank()) return ""
        return value.split(AGGREGATE_SEPARATOR)
            .flatMap(::decode)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .joinToString("、")
    }
}
