package io.legado.app.help.book

import io.legado.app.data.entities.ReadRecordShow
import java.time.LocalDate

enum class ReadRecordTimeRange { HOUR, DAY, MONTH, YEAR, ALL }

data class ReadRecordFilterItem(val record: ReadRecordShow, val duration: Long)

/** Qualify books by their time in the period; deletion uses the same period. */
internal fun ReadStatisticsSnapshot.filterReadRecords(
    range: ReadRecordTimeRange,
    date: LocalDate,
    hour: Int,
    minimum: Long,
    maximum: Long?,
    query: String,
): List<ReadRecordFilterItem> {
    require(hour in 0..23 && minimum >= 0 && (maximum == null || maximum >= minimum))
    val times = when (range) {
        ReadRecordTimeRange.ALL -> records.associate { (it.bookName to it.author) to it.readTime }
        ReadRecordTimeRange.HOUR -> hourly.filter { it.date == date.toString() && it.hour == hour }
            .groupBy { it.bookName to it.author }.mapValues { (_, rows) -> rows.sumOf { it.readTime } }
        else -> {
            val prefix = when (range) {
                ReadRecordTimeRange.DAY -> date.toString()
                ReadRecordTimeRange.MONTH -> date.toString().take(7)
                else -> date.toString().take(4)
            }
            daily.filter { it.date.startsWith(prefix) }.groupBy { it.bookName to it.author }
                .mapValues { (_, rows) -> rows.sumOf { it.readTime } }
        }
    }
    val search = query.trim()
    return records.mapNotNull { record ->
        val duration = times[record.bookName to record.author] ?: return@mapNotNull null
        if (duration < minimum || (maximum != null && duration > maximum) ||
            (search.isNotEmpty() && !record.bookName.contains(search, true) &&
                !record.displayAuthor.contains(search, true))) return@mapNotNull null
        ReadRecordFilterItem(record, duration)
    }.sortedWith(compareBy<ReadRecordFilterItem> { it.duration }
        .thenBy { it.record.bookName }.thenBy { it.record.author })
}
