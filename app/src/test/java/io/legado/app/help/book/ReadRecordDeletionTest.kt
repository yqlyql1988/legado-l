package io.legado.app.help.book

import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordDaily
import io.legado.app.data.entities.ReadRecordDailyShow
import io.legado.app.data.entities.ReadRecordHourly
import io.legado.app.data.entities.ReadRecordHourlyShow
import io.legado.app.data.entities.ReadRecordShow
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReadRecordDeletionTest {
    private val hour = 3_600_000L
    private val record = ReadRecord("phone", "Book", "Author", 19 * hour, 123,
        "Saved chapter", 8, 19, "/saved/cover", "Resolved")
    private fun day(date: String, time: Long) = ReadRecordDaily("phone", "Book", "Author", date, time * hour)
    private fun clock(date: String, clock: Int, time: Long) =
        ReadRecordHourly("phone", "Book", "Author", date, clock, time * hour)
    private val daily = listOf(day("2026-01-15", 1), day("2026-01-18", 2),
        day("2026-02-02", 4), day("2025-12-31", 7))
    private val hourly = listOf(clock("2026-01-15", 9, 1), clock("2026-01-18", 10, 1),
        clock("2026-01-18", 20, 1), clock("2026-02-02", 8, 4), clock("2025-12-31", 23, 7))
    private fun scope(range: ReadRecordTimeRange, date: String = "2026-01-18", hour: Int = 0) =
        ReadRecordDeleteScope(range, LocalDate.parse(date), hour)
    private fun remove(range: ReadRecordTimeRange, date: String = "2026-01-18", clock: Int = 0) =
        deleteReadRecordPeriod(record, daily, hourly, scope(range, date, clock))

    @Test fun deletingJanuaryRemovesOneHourOn15thAndTwoOn18thOnly() {
        val result = remove(ReadRecordTimeRange.MONTH)
        assertEquals(3 * hour, result.removed)
        assertEquals(record.copy(readTime = 16 * hour), result.record)
        assertEquals(listOf(0L, 0L, 4 * hour, 7 * hour), result.daily.map { it.readTime })
        assertEquals(listOf(hour, 2 * hour, 0L, 0L), result.daily.map { it.deletedTime })
        assertTrue(result.hourly.filter { it.date.startsWith("2026-01") }.all { it.readTime == 0L })
        assertEquals(hourly.filterNot { it.date.startsWith("2026-01") },
            result.hourly.filterNot { it.date.startsWith("2026-01") })
        val snapshot = snapshot(result)
        assertEquals(0L, snapshot.summary(ReadStatisticsPeriod.MONTH, 2026, 1).duration)
        assertEquals(0L, snapshot.summary(ReadStatisticsPeriod.DAY, 2026, 1, day = 15).duration)
        assertEquals(0L, snapshot.summary(ReadStatisticsPeriod.DAY, 2026, 1, day = 18).duration)
        assertEquals(4 * hour, snapshot.summary(ReadStatisticsPeriod.YEAR, 2026, 1).duration)
        assertEquals(16 * hour, snapshot.summary(ReadStatisticsPeriod.ALL, 2026, 1).duration)
        assertEquals(5 * hour, snapshot.summary(ReadStatisticsPeriod.ALL, 2026, 1).historyDuration)
    }

    @Test fun deletingDayRemovesBothHoursAndPreservesOtherDays() {
        val result = remove(ReadRecordTimeRange.DAY)
        assertEquals(2 * hour, result.removed)
        assertEquals(17 * hour, result.record.readTime)
        assertEquals(listOf(hour, 0L, 4 * hour, 7 * hour), result.daily.map { it.readTime })
        assertEquals(hourly.first(), result.hourly.first())
        assertTrue(result.hourly.filter { it.date == "2026-01-18" }.all { it.readTime == 0L })
    }

    @Test fun deletingHourReducesDayMonthYearAndTotalOnce() {
        val result = remove(ReadRecordTimeRange.HOUR, clock = 10)
        assertEquals(hour, result.removed)
        assertEquals(18 * hour, result.record.readTime)
        assertEquals(hour, result.daily.single { it.date == "2026-01-18" }.readTime)
        assertEquals(hour, result.hourly.single { it.date == "2026-01-18" && it.hour == 20 }.readTime)
        assertEquals(0L, result.hourly.single { it.date == "2026-01-18" && it.hour == 10 }.readTime)
        assertEquals(2 * hour, snapshot(result).summary(ReadStatisticsPeriod.MONTH, 2026, 1).duration)
        assertEquals(6 * hour, snapshot(result).summary(ReadStatisticsPeriod.YEAR, 2026, 1).duration)
    }

    @Test fun deletingYearKeepsOtherYearsAndUndatedHistory() {
        val result = remove(ReadRecordTimeRange.YEAR)
        assertEquals(7 * hour, result.removed)
        assertEquals(12 * hour, result.record.readTime)
        assertEquals(listOf(0L, 0L, 0L, 7 * hour), result.daily.map { it.readTime })
        assertEquals(5 * hour, snapshot(result).summary(ReadStatisticsPeriod.ALL, 2026, 1).historyDuration)
    }

    @Test fun noDatedOrHourlyEvidenceNeverGuessesWhatToRemove() {
        val undated = deleteReadRecordPeriod(record, emptyList(), emptyList(), scope(ReadRecordTimeRange.YEAR))
        assertEquals(record, undated.record)
        assertEquals(0L, undated.removed)
        val noHour = deleteReadRecordPeriod(record, daily, emptyList(), scope(ReadRecordTimeRange.HOUR, hour = 10))
        assertEquals(daily, noHour.daily)
        assertEquals(record, noHour.record)
        assertEquals(0L, noHour.removed)
    }

    @Test fun samePeriodDeletedTwiceIsANoOp() {
        val first = remove(ReadRecordTimeRange.MONTH)
        val second = deleteReadRecordPeriod(first.record, first.daily, first.hourly, scope(ReadRecordTimeRange.MONTH))
        assertEquals(first.copy(removed = 0), second)
    }

    @Test fun dateBoundariesAndInvalidHoursAreExplicit() {
        val year = scope(ReadRecordTimeRange.YEAR)
        assertTrue(year.includes("2026-12-31"))
        assertFalse(year.includes("2025-12-31"))
        assertFalse(scope(ReadRecordTimeRange.MONTH).includes("2026-02-01"))
        assertFalse(scope(ReadRecordTimeRange.DAY).includes("2026-01-19"))
        assertThrows(IllegalArgumentException::class.java) { scope(ReadRecordTimeRange.HOUR, hour = 24) }
        assertThrows(IllegalArgumentException::class.java) { remove(ReadRecordTimeRange.ALL) }
    }

    @Test fun inconsistentTotalsFailBeforeAnyDatabaseWrite() {
        assertThrows(IllegalStateException::class.java) {
            deleteReadRecordPeriod(record.copy(readTime = 1), daily, hourly, scope(ReadRecordTimeRange.DAY))
        }
    }

    private fun restore(change: ReadRecordDeletion, oldDaily: List<ReadRecordDaily>? = daily,
        oldHourly: List<ReadRecordHourly>? = hourly) = mergeReadStatistics(
        change.record, record, change.daily, oldDaily, true, 5 * hour, 5 * hour, change.hourly, oldHourly)

    @Test fun olderBackupsCannotReviveDeletedMonthAndRestoreIsIdempotent() {
        val changed = remove(ReadRecordTimeRange.MONTH)
        val restored = restore(changed)
        assertEquals(changed.record, restored.record)
        assertEquals(changed.daily, restored.daily)
        assertEquals(changed.hourly, restored.hourly)
        assertEquals(restored, mergeReadStatistics(restored.record, record, restored.daily, daily, true,
            restored.historyTime, 5 * hour, restored.hourly, hourly))
        assertEquals(restored, restore(changed, null, null))
    }

    @Test fun postDeletionBackupRestoresOnFreshDatabaseWithoutLosingRemovalEvidence() {
        val changed = remove(ReadRecordTimeRange.HOUR, clock = 10)
        val restored = mergeReadStatistics(null, changed.record, emptyList(), changed.daily, false,
            null, 5 * hour, emptyList(), changed.hourly)
        assertEquals(changed.record, restored.record)
        assertEquals(changed.daily, restored.daily)
        assertEquals(changed.hourly, restored.hourly)
    }

    @Test fun oldBackupCannotIntroducePreviouslyUnknownHoursIntoADeletedDay() {
        val partialHours = hourly.filterNot { it.date == "2026-01-18" && it.hour == 20 }
        val changed = deleteReadRecordPeriod(record, daily, partialHours, scope(ReadRecordTimeRange.DAY))
        val restored = restore(changed)
        assertEquals(changed.record, restored.record)
        assertTrue(restored.hourly.none { it.date == "2026-01-18" && it.readTime > 0 })
    }

    @Test fun newHourAfterWholeDayDeletionCountsWhileOldHourDetailsStayDeleted() {
        val changed = remove(ReadRecordTimeRange.DAY)
        val reset = changed.daily.single { it.date == "2026-01-18" }.resetTime
        val continued = changed.copy(record = changed.record.copy(readTime = changed.record.readTime + hour),
            daily = changed.daily.map { if (it.date == "2026-01-18") it.copy(readTime = hour) else it },
            hourly = changed.hourly + clock("2026-01-18", 15, 1).copy(resetTime = reset))
        val restored = restore(continued)
        assertEquals(18 * hour, restored.record.readTime)
        assertEquals(hour, restored.daily.single { it.date == "2026-01-18" }.readTime)
        assertEquals(listOf(15), restored.hourly.filter { it.date == "2026-01-18" && it.readTime > 0 }.map { it.hour })
    }

    @Test fun newReadingInSameHourAfterHourDeletionIsKept() {
        val changed = remove(ReadRecordTimeRange.HOUR, clock = 10)
        val continued = changed.copy(record = changed.record.copy(readTime = 19 * hour),
            daily = changed.daily.map { if (it.date == "2026-01-18") it.copy(readTime = 2 * hour) else it },
            hourly = changed.hourly.map {
                if (it.date == "2026-01-18" && it.hour == 10) it.copy(readTime = hour) else it
            })
        val restored = restore(continued)
        assertEquals(19 * hour, restored.record.readTime)
        assertEquals(hour, restored.hourly.single { it.date == "2026-01-18" && it.hour == 10 }.readTime)
    }

    @Test fun divergentHourDeletionsKeepBothRemovals() {
        val morning = remove(ReadRecordTimeRange.HOUR, clock = 10)
        val evening = remove(ReadRecordTimeRange.HOUR, clock = 20)
        val merged = mergeReadStatistics(morning.record, evening.record, morning.daily, evening.daily, true,
            5 * hour, 5 * hour, morning.hourly, evening.hourly)
        assertEquals(17 * hour, merged.record.readTime)
        assertEquals(0L, merged.daily.single { it.date == "2026-01-18" }.readTime)
        assertTrue(merged.hourly.filter { it.date == "2026-01-18" }.all { it.readTime == 0L })
    }

    private fun snapshot(change: ReadRecordDeletion) = ReadStatisticsSnapshot(
        listOf(ReadRecordShow(record.bookName, change.record.readTime, record.lastRead, record.author)),
        change.daily.filter { it.readTime > 0 }.map { ReadRecordDailyShow(it.bookName, it.author, it.date, it.readTime) }, 0,
        change.hourly.filter { it.readTime > 0 }.map { ReadRecordHourlyShow(it.bookName, it.author, it.date, it.hour, it.readTime) })
}
