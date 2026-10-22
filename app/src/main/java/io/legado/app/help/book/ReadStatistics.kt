package io.legado.app.help.book

import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordAuthors
import io.legado.app.data.entities.ReadRecordDaily
import io.legado.app.data.entities.ReadRecordDailyShow
import io.legado.app.data.entities.ReadRecordHistory
import io.legado.app.data.entities.ReadRecordHourly
import io.legado.app.data.entities.ReadRecordHourlyShow
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.data.entities.ReadStatisticsState
import io.legado.app.data.entities.mergeRestoredReadRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.time.Instant
import java.time.temporal.ChronoUnit

data class ReadDayDuration(val date: String, val duration: Long)
data class ReadHourDuration(val date: String, val hour: Int, val duration: Long)

/** Distribute a forward-reading word delta over dated intervals without losing its total. */
internal fun allocateReadWords(words: Long, durations: List<Long>): List<Long> {
    if (words <= 0 || durations.isEmpty()) return durations.map { 0L }
    val totalDuration = durations.sumOf { it.coerceAtLeast(0L) }
    if (totalDuration <= 0) return durations.map { 0L }
    var allocated = 0L
    return durations.mapIndexed { index, duration ->
        if (index == durations.lastIndex) {
            (words - allocated).coerceAtLeast(0L)
        } else {
            val safeDuration = duration.coerceAtLeast(0L)
            val whole = words / totalDuration
            val remainder = words % totalDuration
            val share = whole * safeDuration + remainder * safeDuration / totalDuration
            allocated = Math.addExact(allocated, share)
            share
        }
    }
}

/** Freeze local clock labels at recording time; account for timezone transitions too. */
internal fun splitReadHourlyInterval(
    end: Long,
    elapsed: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
): List<ReadHourDuration> {
    if (elapsed <= 0 || end <= 0) return emptyList()
    var cursor = (end - elapsed.coerceAtMost(end)).coerceAtLeast(0)
    val zone = timeZone.toZoneId()
    return buildList {
        while (cursor < end) {
            val instant = Instant.ofEpochMilli(cursor)
            val local = instant.atZone(zone)
            val boundary = local.toLocalDateTime().truncatedTo(ChronoUnit.HOURS)
                .plusHours(1).atZone(zone).toInstant().toEpochMilli()
            val transition = zone.rules.nextTransition(instant)?.instant?.toEpochMilli() ?: Long.MAX_VALUE
            val next = minOf(end, boundary, transition)
            check(next > cursor)
            add(ReadHourDuration(local.toLocalDate().toString(), local.hour, next - cursor))
            cursor = next
        }
    }
}

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
    val hourly: List<ReadRecordHourly> = emptyList(),
)

internal data class RestoredReadStatistics(
    val record: ReadRecord,
    val daily: List<ReadRecordDaily>,
    val historyTime: Long?,
    val hourly: List<ReadRecordHourly> = emptyList(),
)

/**
 * Merge gross counters and their removed portions separately. Whole-day resets also
 * invalidate older hour detail, including hours absent from a partial local backup.
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
    currentHourly: List<ReadRecordHourly> = emptyList(),
    incomingHourly: List<ReadRecordHourly>? = null,
): RestoredReadStatistics {
    val dayResets = (currentDaily + incomingDaily.orEmpty()).groupBy { it.date }
        .mapValues { (_, rows) -> rows.maxOf { it.resetTime } }
    val hourResets = (currentHourly + incomingHourly.orEmpty()).groupBy { it.date }
        .mapValues { (day, rows) -> maxOf(dayResets[day] ?: 0, rows.maxOf { it.resetTime }) }
    val mergedHourly = (currentHourly + incomingHourly.orEmpty()).groupBy { it.date to it.hour }
        .values.mapNotNull { rows ->
            val active = rows.filter { it.resetTime == hourResets[it.date] }
            if (active.isEmpty()) return@mapNotNull null
            val deletedTime = active.maxOf { it.deletedTime }
            val grossTime = active.maxOf { Math.addExact(it.readTime, it.deletedTime) }
            val deletedWords = active.maxOf { it.deletedWords }
            val grossWords = active.maxOf { Math.addExact(it.readWords, it.deletedWords) }
            active.first().copy(
                readTime = (grossTime - deletedTime).coerceAtLeast(0),
                deletedTime = deletedTime,
                readWords = (grossWords - deletedWords).coerceAtLeast(0),
                deletedWords = deletedWords,
            )
        }
    val hourlyDays = mergedHourly.groupBy { it.date }.values.map { rows ->
        val first = rows.first()
        ReadRecordDaily(first.deviceId, first.bookName, first.author, first.date,
            rows.fold(0L) { total, row -> Math.addExact(total, row.readTime) },
            Math.addExact(first.resetTime, rows.sumOf { it.deletedTime }), first.resetTime,
            rows.fold(0L) { total, row -> Math.addExact(total, row.readWords) },
            rows.fold(0L) { total, row -> Math.addExact(total, row.deletedWords) })
    }
    val mergedDaily = (currentDaily + incomingDaily.orEmpty() + hourlyDays)
        .groupBy { it.date }
        .values.map { rows ->
            val deletedTime = rows.maxOf { it.deletedTime }
            val grossTime = rows.maxOf { Math.addExact(it.readTime, it.deletedTime) }
            val deletedWords = rows.maxOf { it.deletedWords }
            val grossWords = rows.maxOf { Math.addExact(it.readWords, it.deletedWords) }
            rows.first().copy(
                readTime = (grossTime - deletedTime).coerceAtLeast(0),
                deletedTime = deletedTime,
                resetTime = rows.maxOf { it.resetTime },
                readWords = (grossWords - deletedWords).coerceAtLeast(0),
                deletedWords = deletedWords,
            )
        }
    val dailyTotal = mergedDaily.sumOf { it.readTime }
    val dailyWords = mergedDaily.sumOf { it.readWords }
    val record = mergeRestoredReadRecord(current, incoming, localDevice)
    val history = listOfNotNull(currentHistory, incomingHistory).maxOrNull()
    val knownTotal = Math.addExact(history ?: 0L, dailyTotal)
    val knownWords = dailyWords
    val removed = mergedDaily.sumOf { it.deletedTime }
    val removedWords = mergedDaily.sumOf { it.deletedWords }
    val currentGross = Math.addExact(current?.readTime ?: 0, currentDaily.sumOf { it.deletedTime })
    val incomingGross = Math.addExact(incoming.readTime, incomingDaily.orEmpty().sumOf { it.deletedTime })
    val gross = if (incomingDaily == null && !localDevice && removed == 0L) incomingGross
        else maxOf(currentGross, incomingGross)
    val total = maxOf((gross - removed).coerceAtLeast(0), knownTotal)
    val currentGrossWords = Math.addExact(current?.readWords ?: 0L, currentDaily.sumOf { it.deletedWords })
    val incomingGrossWords = Math.addExact(incoming.readWords, incomingDaily.orEmpty().sumOf { it.deletedWords })
    // Legacy backups have no word counters. Keep any newer local value when
    // restoring one of those backups, while still accepting newer word data.
    val grossWords = maxOf(currentGrossWords, incomingGrossWords)
    val totalWords = maxOf((grossWords - removedWords).coerceAtLeast(0L), knownWords)
    return RestoredReadStatistics(record.copy(readTime = total, readWords = totalWords),
        mergedDaily, history, mergedHourly)
}

enum class ReadStatisticsPeriod { ALL, YEAR, MONTH, DAY }

data class ReadHourBucket(
    val startHour: Int,
    val endHour: Int,
    val books: List<ReadRecordShow>,
) {
    val duration: Long get() = books.sumOf { it.readTime }
}

data class ReadHourlySummary(
    val buckets: List<ReadHourBucket>,
    val duration: Long,
    val unassignedDuration: Long,
)

internal fun readStatisticsPrefix(period: ReadStatisticsPeriod, year: Int, month: Int, day: Int): String =
    when (period) {
        ReadStatisticsPeriod.ALL -> ""
        ReadStatisticsPeriod.YEAR -> String.format(Locale.ROOT, "%04d-", year)
        ReadStatisticsPeriod.MONTH -> String.format(Locale.ROOT, "%04d-%02d-", year, month)
        ReadStatisticsPeriod.DAY -> String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)
    }

/** Apply a threshold after aggregating each book, without changing stored records. */
internal fun List<ReadRecordShow>.visibleReadStatisticsBooks(minimum: Long): List<ReadRecordShow> =
    filter { it.readTime >= minimum.coerceAtLeast(0) }

data class ReadStatisticsSummary(
    val duration: Long,
    val datedDuration: Long,
    val historyDuration: Long,
    val days: Map<String, Long>,
    val books: List<ReadRecordShow>,
    val words: Long = 0L,
    val datedWords: Long = 0L,
) {
    val average: Long get() = if (days.isEmpty()) 0L else datedDuration / days.size
    val historyWords: Long get() = (words - datedWords).coerceAtLeast(0L)
    /** Old records may have time without word details; speed uses the dated portion. */
    val speed: Long get() = readWordsSpeed(datedWords, datedDuration)
}

internal fun readWordsSpeed(words: Long, duration: Long): Long {
    if (words <= 0 || duration <= 0) return 0L
    return (words.toDouble() * 60_000.0 / duration).toLong().coerceAtLeast(0L)
}

/** A single immutable database snapshot supplies totals, charts, calendar and rankings. */
data class ReadStatisticsSnapshot(
    val records: List<ReadRecordShow>,
    val daily: List<ReadRecordDailyShow>,
    val startedAt: Long,
    val hourly: List<ReadRecordHourlyShow> = emptyList(),
) {
    /** Restrict an immutable snapshot to one book while preserving its stored identity. */
    fun forBook(bookName: String, author: String): ReadStatisticsSnapshot {
        fun matches(candidateName: String, candidateAuthor: String): Boolean =
            candidateName == bookName && (candidateAuthor == author ||
                (ReadRecordAuthors.isCombined(candidateAuthor) &&
                    ReadRecordAuthors.decode(candidateAuthor).contains(author)))
        return copy(
            records = records.filter { matches(it.bookName, it.author) },
            daily = daily.filter { matches(it.bookName, it.author) },
            hourly = hourly.filter { matches(it.bookName, it.author) },
        )
    }

    /** Daily rankings use the day's complete records, independently of the month threshold. */
    fun booksForDay(date: String): List<ReadRecordShow> {
        val metrics = daily.filter { it.date == date }
            .groupBy { it.bookName to it.author }
            .mapValues { (_, rows) -> rows.sumOf { it.readTime } to rows.sumOf { it.readWords } }
        return records.mapNotNull { record ->
            metrics[record.bookName to record.author]?.takeIf { it.first > 0 }?.let {
                record.copy(readTime = it.first, readWords = it.second)
            }
        }.sortedWith(compareByDescending<ReadRecordShow> { it.readTime }
            .thenBy { it.bookName }.thenBy { it.author })
    }

    fun summary(
        period: ReadStatisticsPeriod,
        year: Int,
        month: Int,
        minimum: Long = 0,
        day: Int = 1,
    ): ReadStatisticsSummary {
        val prefix = readStatisticsPrefix(period, year, month, day)
        val selected = daily.filter { it.date.startsWith(prefix) }
        val bookMetrics = selected.groupBy { it.bookName to it.author }
            .mapValues { (_, rows) -> rows.sumOf { it.readTime } to rows.sumOf { it.readWords } }
        val ranked = if (period == ReadStatisticsPeriod.ALL) records else records.mapNotNull { record ->
            bookMetrics[record.bookName to record.author]?.let {
                record.copy(readTime = it.first, readWords = it.second)
            }
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
        val datedWords = details.sumOf { it.readWords }
        val duration = if (period == ReadStatisticsPeriod.ALL) books.sumOf { it.readTime } else dated
        val words = if (period == ReadStatisticsPeriod.ALL) books.sumOf { it.readWords } else datedWords
        return ReadStatisticsSummary(
            duration, dated, (duration - dated).coerceAtLeast(0),
            details.groupBy { it.date }.mapValues { (_, rows) -> rows.sumOf { it.readTime } },
            books, words, datedWords,
        )
    }

    fun hourlySummary(
        period: ReadStatisticsPeriod,
        year: Int,
        month: Int,
        minimum: Long = 0,
        day: Int = 1,
        intervalHours: Int = 1,
    ): ReadHourlySummary {
        require(intervalHours in 1..24)
        val summary = summary(period, year, month, minimum, day)
        val identities = summary.books.mapTo(hashSetOf()) { it.bookName to it.author }
        val prefix = readStatisticsPrefix(period, year, month, day)
        val selected = hourly.filter {
            it.date.startsWith(prefix) && (it.bookName to it.author) in identities && it.hour in 0..23
        }
        val buckets = (0 until 24 step intervalHours).map { start ->
            val end = minOf(start + intervalHours, 24)
            val times = selected.filter { it.hour in start until end }
                .groupBy { it.bookName to it.author }.mapValues { (_, rows) -> rows.sumOf { it.readTime } }
            val books = summary.books.mapNotNull { record ->
                times[record.bookName to record.author]?.takeIf { it > 0 }?.let { record.copy(readTime = it) }
            }.sortedWith(compareByDescending<ReadRecordShow> { it.readTime }.thenBy { it.bookName }.thenBy { it.author })
            ReadHourBucket(start, end, books)
        }
        val duration = buckets.sumOf { it.duration }
        return ReadHourlySummary(buckets, duration, (summary.duration - duration).coerceAtLeast(0))
    }
}
