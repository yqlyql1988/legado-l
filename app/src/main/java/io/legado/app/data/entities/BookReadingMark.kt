package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import io.legado.app.utils.GSON
import java.time.LocalDate

/** Personal annotations survive changing sources and removing a bookshelf row. */
@Entity(tableName = "book_reading_marks", primaryKeys = ["bookName", "bookAuthor"])
@TypeConverters(ReadingMarkConverters::class)
data class BookReadingMark(
    val bookName: String,
    val bookAuthor: String = "",
    val status: String = UNMARKED,
    val date: String = "",
    val position: ReadingMarkChapter? = null,
    val reason: String = "",
    val skipped: List<ReadingMarkChapter> = emptyList(),
    val updatedAt: Long = 0,
) {
    fun validated() = apply {
        require(bookName.isNotBlank() && status in STATUSES && updatedAt >= 0)
        require(date.isEmpty() || LocalDate.parse(date).toString() == date)
        require(status == UNMARKED || date.isNotEmpty())
        position?.validated()
        skipped.forEach { it.validated() }
    }

    companion object {
        const val UNMARKED = "unmarked"
        const val READING = "reading"
        const val ABANDONED = "abandoned"
        const val FINISHED = "finished"
        val STATUSES = listOf(UNMARKED, READING, ABANDONED, FINISHED)
    }
}

data class ReadingMarkChapter(
    val bookUrl: String,
    val url: String,
    val title: String,
    val index: Int,
    val pos: Int = 0,
) {
    fun validated() = apply { require(index >= 0 && pos >= 0) }
}

fun BookChapter.readingMarkChapter(pos: Int = 0) =
    ReadingMarkChapter(bookUrl, url, title, index, pos)

/** No index-only fallback: after a source/TOC change that could navigate to unrelated text. */
class ReadingMarkResolver(chapters: List<BookChapter>) {
    private val byUrl = chapters.filterNot { it.isVolume }.groupBy { it.bookUrl to it.url }
    private val byTitle = chapters.filterNot { it.isVolume }.groupBy { it.title.trim() }

    fun resolve(mark: ReadingMarkChapter): BookChapter? =
        mark.url.takeIf { it.isNotBlank() }?.let { byUrl[mark.bookUrl to it]?.singleOrNull() }
            ?: mark.title.trim().takeIf { it.isNotEmpty() }?.let { byTitle[it]?.singleOrNull() }
}

fun BookReadingMark.withSkipped(
    chapters: List<BookChapter>,
    selected: List<BookChapter>,
    remove: Boolean = false,
): BookReadingMark {
    val resolver = ReadingMarkResolver(chapters)
    val indexes = selected.filterNot { it.isVolume }.mapTo(hashSetOf()) { it.index }
    val retained = skipped.filterNot { resolver.resolve(it)?.index in indexes }
    return copy(skipped = if (remove) retained else retained +
        selected.filterNot { it.isVolume }.distinctBy { it.index }.map { it.readingMarkChapter() })
}

class ReadingMarkConverters {
    @TypeConverter
    fun positionToJson(value: ReadingMarkChapter?): String? = value?.let { GSON.toJson(it) }

    @TypeConverter
    fun positionFromJson(value: String?): ReadingMarkChapter? =
        value?.let { GSON.fromJson(it, ReadingMarkChapter::class.java) }

    @TypeConverter
    fun skippedToJson(value: List<ReadingMarkChapter>): String = GSON.toJson(value)

    @TypeConverter
    fun skippedFromJson(value: String): List<ReadingMarkChapter> =
        GSON.fromJson(value, Array<ReadingMarkChapter>::class.java).toList()
}
