package io.legado.app.help.book

import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordDaily
import io.legado.app.data.entities.ReadRecordDailyShow
import io.legado.app.data.entities.ReadRecordHistory
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.data.entities.ReadStatisticsState
import io.legado.app.data.entities.mergeRestoredReadRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class ReadDayDuration(val date: String, val duration: Long)

/** Keep dates fixed when recording; changing a device's timezone must not move old days. */
internal fun splitReadInterval(
    end: Long,
    elapsed: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
): List<ReadDayDuration> {
    if (elapsed <= 0 || end <= 0) return emptyList()
    var cursor = (end - elapsed.coerceAtMost(end)).coerceAtLeast(0)
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { this.timeZone = timeZone }
    val calendar = Calendar.getInstance(timeZone)
    return buildList {
        while (cursor < end) {
            calendar.timeInMillis = cursor
            val date = format.format(calendar.time)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            val next = minOf(end, calendar.timeInMillis)
            if (next <= cursor) break
            add(ReadDayDuration(date, next - cursor))
            cursor = next
        }
    }
}

internal fun isReadStatisticsDate(value: String): Boolean {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) return false
    return runCatching {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }
        format.format(checkNotNull(format.parse(value))) == value
    }.getOrDefault(false)
}

data class ReadStatisticsBackup(
    val records: List<ReadRecord>,
    val daily: List<ReadRecordDaily>,
    val states: List<ReadStatisticsState>,
    val history: List<ReadRecordHistory>,
)

internal data class RestoredReadStatistics(
    val record: ReadRecord,
    val daily: List<ReadRecordDaily>,
    val historyTime: Long?,
)

/**
 * Merge each device/day counter by max and preserve the known historical baseline.
 * Never infer a baseline from an old backup's total: it may already contain some of
 * the dated time. The total also remains a floor for time whose coverage is unknown.
 */
internal fun mergeReadStatistics(
    current: ReadRecord?,
    incoming: ReadRecord,
    currentDaily: List<ReadRecordDaily>,
    incomingDaily: List<ReadRecordDaily>?,
    localDevice: Boolean,
    currentHistory: Long? = null,
    incomingHistory: Long? = null,
): RestoredReadStatistics {
    val mergedDaily = (currentDaily + incomingDaily.orEmpty())
        .groupBy { it.date }
        .values.map { rows -> rows.maxBy { it.readTime } }
    val dailyTotal = mergedDaily.sumOf { it.readTime }
    val record = mergeRestoredReadRecord(current, incoming, localDevice)
    val history = listOfNotNull(currentHistory, incomingHistory).maxOrNull()
    val knownTotal = Math.addExact(history ?: 0L, dailyTotal)
    val total = if (incomingDaily == null) maxOf(record.readTime, knownTotal)
        else maxOf(current?.readTime ?: 0L, incoming.readTime, knownTotal)
    return RestoredReadStatistics(record.copy(readTime = total), mergedDaily, history)
}

enum class ReadStatisticsPeriod { ALL, YEAR, MONTH }

/** Filter only an already aggregated ranking; the snapshot and summary stay intact. */
internal fun List<ReadRecordShow>.visibleReadStatisticsBooks(minimum: Long): List<ReadRecordShow> =
    filter { it.readTime >= minimum.coerceAtLeast(0) }

data class ReadStatisticsSummary(
    val duration: Long,
    val datedDuration: Long,
    val historyDuration: Long,
    val days: Map<String, Long>,
    val books: List<ReadRecordShow>,
) {
    val average: Long get() = if (days.isEmpty()) 0L else datedDuration / days.size
}

/** A single immutable database snapshot supplies totals, charts, calendar and rankings. */
data class ReadStatisticsSnapshot(
    val records: List<ReadRecordShow>,
    val daily: List<ReadRecordDailyShow>,
    val startedAt: Long,
) {
    fun summary(
        period: ReadStatisticsPeriod,
        year: Int,
        month: Int,
        minimum: Long = 0,
    ): ReadStatisticsSummary {
        val prefix = when (period) {
            ReadStatisticsPeriod.ALL -> ""
            ReadStatisticsPeriod.YEAR -> String.format(Locale.ROOT, "%04d-", year)
            ReadStatisticsPeriod.MONTH -> String.format(Locale.ROOT, "%04d-%02d-", year, month)
        }
        val selected = daily.filter { it.date.startsWith(prefix) }
        val bookTimes = selected.groupBy { it.bookName to it.author }
            .mapValues { (_, rows) -> rows.sumOf { it.readTime } }
        val ranked = if (period == ReadStatisticsPeriod.ALL) records else records.mapNotNull { record ->
            bookTimes[record.bookName to record.author]?.let { record.copy(readTime = it) }
        }
        // Qualify books using their aggregate for this period, then keep all of
        // those books' daily time. A threshold never trims individual sessions.
        val books = ranked.filter { it.readTime > 0 }.visibleReadStatisticsBooks(minimum)
            .sortedWith(compareByDescending<ReadRecordShow> { it.readTime }
                .thenBy { it.bookName }.thenBy { it.author })
        val identities = books.mapTo(hashSetOf()) { it.bookName to it.author }
        val details = if (minimum > 0) selected.filter { (it.bookName to it.author) in identities }
            else selected
        val dated = details.sumOf { it.readTime }
        val duration = if (period == ReadStatisticsPeriod.ALL) books.sumOf { it.readTime } else dated
        return ReadStatisticsSummary(
            duration, dated, (duration - dated).coerceAtLeast(0),
            details.groupBy { it.date }.mapValues { (_, rows) -> rows.sumOf { it.readTime } },
            books,
        )
    }
}
