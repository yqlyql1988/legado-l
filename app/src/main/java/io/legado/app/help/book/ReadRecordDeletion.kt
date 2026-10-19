package io.legado.app.help.book

import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordDaily
import io.legado.app.data.entities.ReadRecordHourly
import java.time.LocalDate

/** A single filter scope, shared by the confirmation and the database transaction. */
data class ReadRecordDeleteScope(
    val range: ReadRecordTimeRange,
    val date: LocalDate,
    val hour: Int = 0,
) {
    init { require(hour in 0..23) }

    fun includes(day: String): Boolean = when (range) {
        ReadRecordTimeRange.ALL -> true
        ReadRecordTimeRange.YEAR -> day.startsWith("${date.year.toString().padStart(4, '0')}-")
        ReadRecordTimeRange.MONTH -> day.startsWith(date.toString().take(7) + "-")
        else -> day == date.toString()
    }
}

internal data class ReadRecordDeletion(
    val record: ReadRecord,
    val daily: List<ReadRecordDaily>,
    val hourly: List<ReadRecordHourly>,
    val removed: Long,
)

/** Only net dated time is removed. Undated history and reading-position snapshots stay intact. */
internal fun deleteReadRecordPeriod(
    record: ReadRecord,
    daily: List<ReadRecordDaily>,
    hourly: List<ReadRecordHourly>,
    scope: ReadRecordDeleteScope,
): ReadRecordDeletion {
    require(scope.range != ReadRecordTimeRange.ALL)
    var removed = 0L
    val changedHours = hourly.associateBy { it.date to it.hour }.toMutableMap()
    val hoursByDay = hourly.groupBy { it.date }
    val days = daily.map { day ->
        if (!scope.includes(day.date)) return@map day
        val hours = hoursByDay[day.date].orEmpty()
        if (scope.range == ReadRecordTimeRange.HOUR) {
            val target = hours.singleOrNull { it.hour == scope.hour } ?: return@map day
            check(target.readTime in 0..day.readTime)
            if (target.readTime == 0L) return@map day
            changedHours[day.date to target.hour] = target.copy(
                readTime = 0, deletedTime = Math.addExact(target.deletedTime, target.readTime))
            removed = Math.addExact(removed, target.readTime)
            day.copy(readTime = day.readTime - target.readTime,
                deletedTime = Math.addExact(day.deletedTime, target.readTime))
        } else {
            if (day.readTime == 0L) return@map day
            val reset = Math.addExact(day.deletedTime, day.readTime)
            hours.forEach { old ->
                changedHours[old.date to old.hour] = old.copy(readTime = 0, deletedTime = 0, resetTime = reset)
            }
            removed = Math.addExact(removed, day.readTime)
            day.copy(readTime = 0, deletedTime = reset, resetTime = reset)
        }
    }
    check(removed in 0..record.readTime)
    return ReadRecordDeletion(record.copy(readTime = record.readTime - removed),
        days, changedHours.values.toList(), removed)
}
