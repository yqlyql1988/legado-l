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
import java.time.ZonedDateTime
import java.util.TimeZone

class ReadHourlyStatisticsTest {
    private fun split(end: String, elapsed: Long, zone: String = "Asia/Shanghai") =
        splitReadHourlyInterval(ZonedDateTime.parse(end).toInstant().toEpochMilli(),
            elapsed, TimeZone.getTimeZone(zone))

    @Test fun hourAndMidnightKeepEveryMillisecond() {
        assertEquals(listOf(ReadHourDuration("2026-10-18", 9, 60_000),
            ReadHourDuration("2026-10-18", 10, 90_001)),
            split("2026-10-18T10:01:30.001+08:00", 150_001))
        assertEquals(listOf(ReadHourDuration("2026-12-31", 23, 60_000),
            ReadHourDuration("2027-01-01", 0, 60_000)),
            split("2027-01-01T00:01:00+08:00", 120_000))
        assertEquals(listOf(ReadHourDuration("2028-02-29", 23, 60_000)),
            split("2028-03-01T00:00:00+08:00", 60_000))
    }

    @Test fun invalidAndLongIntervalsAreBoundedAndConserved() {
        assertTrue(splitReadHourlyInterval(100, 0).isEmpty())
        assertTrue(splitReadHourlyInterval(100, -1).isEmpty())
        assertTrue(splitReadHourlyInterval(0, 100).isEmpty())
        assertEquals(100L, splitReadHourlyInterval(100, Long.MAX_VALUE,
            TimeZone.getTimeZone("UTC")).sumOf { it.duration })
        val duration = 370 * 86_400_000L + 12345
        val rows = split("2027-02-12T15:00:00+08:00", duration)
        assertEquals(duration, rows.sumOf { it.duration })
        assertTrue(rows.all { it.hour in 0..23 && it.duration > 0 && it.duration <= 3_600_000 })
    }

    @Test fun daylightSavingSkipsMissingHourAndCombinesRepeatedHour() {
        val spring = split("2026-03-09T00:00:00-04:00", 23 * 3_600_000L, "America/New_York")
        assertEquals((0..23).filter { it != 2 }, spring.map { it.hour })
        assertEquals(23 * 3_600_000L, spring.sumOf { it.duration })
        val fall = split("2026-11-02T00:00:00-05:00", 25 * 3_600_000L, "America/New_York")
        assertEquals(25 * 3_600_000L, fall.sumOf { it.duration })
        assertEquals(7_200_000L, fall.filter { it.hour == 1 }.sumOf { it.duration })
    }

    @Test fun halfHourClockChangesDoNotShiftLabelsOrLoseTime() {
        val spring = split("2026-10-04T04:00:00+11:00", 10_800_000, "Australia/Lord_Howe")
        assertEquals(listOf(0, 1, 2, 3), spring.map { it.hour })
        assertEquals(listOf(1_800_000L, 3_600_000L, 1_800_000L, 3_600_000L),
            spring.map { it.duration })
        val fall = split("2026-04-05T03:00:00+10:30", 10_800_000, "Australia/Lord_Howe")
        assertEquals(10_800_000L, fall.sumOf { it.duration })
        assertEquals(5_400_000L, fall.filter { it.hour == 1 }.sumOf { it.duration })
    }

    private val date = LocalDate.of(2026, 10, 18)
    private val snapshot = ReadStatisticsSnapshot(
        listOf(ReadRecordShow("Same", 10_000, 1, "A", lastChapterIndex = 8),
            ReadRecordShow("Same", 20_000, 2, "B"),
            ReadRecordShow("Old", 30_000, 3, "C"),
            ReadRecordShow("Zero", 0, 4, "D")),
        listOf(ReadRecordDailyShow("Same", "A", "2025-12-31", 1000),
            ReadRecordDailyShow("Same", "A", "2026-09-01", 2000),
            ReadRecordDailyShow("Same", "A", "2026-10-17", 3000),
            ReadRecordDailyShow("Same", "A", "2026-10-18", 4000),
            ReadRecordDailyShow("Same", "B", "2026-10-18", 5000)), 1,
        listOf(ReadRecordHourlyShow("Same", "A", "2025-12-31", 23, 1000),
            ReadRecordHourlyShow("Same", "A", "2026-09-01", 9, 2000),
            ReadRecordHourlyShow("Same", "A", "2026-10-17", 9, 3000),
            ReadRecordHourlyShow("Same", "A", "2026-10-18", 9, 1000, 100),
            ReadRecordHourlyShow("Same", "A", "2026-10-18", 23, 2000, 200),
            ReadRecordHourlyShow("Same", "B", "2026-10-18", 9, 5000, 500)),
    )

    @Test fun hourlyGroupingRespectsScopeAuthorsAndPartialLastBucket() {
        val day = snapshot.hourlySummary(ReadStatisticsPeriod.DAY, 2026, 10, day = 18, intervalHours = 5)
        assertEquals(listOf(0 to 5, 5 to 10, 10 to 15, 15 to 20, 20 to 24),
            day.buckets.map { it.startHour to it.endHour })
        assertEquals(8000L, day.duration)
        assertEquals(1000L, day.unassignedDuration)
        assertEquals(listOf("B", "A"), day.buckets[1].books.map { it.author })
        assertEquals(listOf(500L, 100L), day.buckets[1].books.map { it.readWords })
        assertEquals(600L, day.buckets[1].words)
        assertEquals(8, day.buckets[1].books.last().lastChapterIndex)
        assertEquals(11_000L, snapshot.hourlySummary(ReadStatisticsPeriod.MONTH, 2026, 10).duration)
        assertEquals(13_000L, snapshot.hourlySummary(ReadStatisticsPeriod.YEAR, 2026, 10).duration)
        val all = snapshot.hourlySummary(ReadStatisticsPeriod.ALL, 2026, 10)
        assertEquals(14_000L, all.duration)
        assertEquals(46_000L, all.unassignedDuration)
    }

    @Test fun hourlyQualificationUsesScopeTotalRatherThanIndividualHour() {
        val qualifiedDay = snapshot.hourlySummary(ReadStatisticsPeriod.DAY, 2026, 10,
            minimum = 4000, day = 18)
        assertEquals(8000L, qualifiedDay.duration)
        val onlyB = snapshot.hourlySummary(ReadStatisticsPeriod.DAY, 2026, 10,
            minimum = 4001, day = 18)
        assertEquals(5000L, onlyB.duration)
        assertEquals("B", onlyB.buckets[9].books.single().author)
        assertEquals(0L, onlyB.unassignedDuration)
        val old = snapshot.hourlySummary(ReadStatisticsPeriod.ALL, 2026, 10, minimum = 30_000)
        assertEquals(0L, old.duration)
        assertEquals(30_000L, old.unassignedDuration)
        assertTrue(old.buckets.all { it.books.isEmpty() })
        assertEquals(0L, snapshot.hourlySummary(ReadStatisticsPeriod.DAY, 2026, 10, day = 16).duration)
    }

    @Test fun hourlyBookRowsUseWordsFromTheSelectedHours() {
        val day = snapshot.hourlySummary(ReadStatisticsPeriod.DAY, 2026, 10, day = 18)

        assertEquals(500L, day.buckets[9].books.single { it.author == "B" }.readWords)
        assertEquals(100L, day.buckets[9].books.single { it.author == "A" }.readWords)
        assertEquals(200L, day.buckets[23].books.single { it.author == "A" }.readWords)
    }

    @Test fun intervalRejectsSubHourAndOverDayValues() {
        for (interval in listOf(0, -1, 25)) {
            assertThrows(IllegalArgumentException::class.java) {
                snapshot.hourlySummary(ReadStatisticsPeriod.ALL, 2026, 10, intervalHours = interval)
            }
        }
        assertEquals(1, snapshot.hourlySummary(ReadStatisticsPeriod.ALL, 2026, 10, intervalHours = 24).buckets.size)
    }

    @Test fun filtersUseFiveIndependentScopesAndIncludeExactDurationBounds() {
        fun values(range: ReadRecordTimeRange, low: Long = 0, high: Long? = null) =
            snapshot.filterReadRecords(range, date, 9, low, high, "")
        assertEquals(listOf(1000L, 5000L), values(ReadRecordTimeRange.HOUR).map { it.duration })
        assertEquals(listOf(4000L, 5000L), values(ReadRecordTimeRange.DAY).map { it.duration })
        assertEquals(listOf(5000L, 7000L), values(ReadRecordTimeRange.MONTH).map { it.duration })
        assertEquals(listOf(5000L, 9000L), values(ReadRecordTimeRange.YEAR).map { it.duration })
        assertEquals(listOf(0L, 10_000L, 20_000L, 30_000L), values(ReadRecordTimeRange.ALL).map { it.duration })
        assertEquals("A", values(ReadRecordTimeRange.HOUR, 1000, 1000).single().record.author)
        assertEquals("B", values(ReadRecordTimeRange.DAY, 4001, 5000).single().record.author)
        assertTrue(values(ReadRecordTimeRange.DAY, 5001).isEmpty())
        assertEquals("Zero", values(ReadRecordTimeRange.ALL, 0, 0).single().record.bookName)
    }

    @Test fun searchTrimsWhitespaceAndNeverAssignsUndatedHistoryToADate() {
        assertEquals("B", snapshot.filterReadRecords(ReadRecordTimeRange.DAY, date, 9, 0, null, " b ")
            .single().record.author)
        assertEquals(2, snapshot.filterReadRecords(ReadRecordTimeRange.DAY, date, 9, 0, null, "sAmE").size)
        assertTrue(snapshot.filterReadRecords(ReadRecordTimeRange.DAY, date, 9, 0, null, "Old").isEmpty())
        assertEquals("Old", snapshot.filterReadRecords(ReadRecordTimeRange.ALL, date, 9, 0, null, "Old")
            .single().record.bookName)
        assertTrue(snapshot.filterReadRecords(ReadRecordTimeRange.HOUR, date, 10, 0, null, "").isEmpty())
    }

    @Test fun restoreHourlyCountersUsesMaxAndRaisesDailyFloorIdempotently() {
        val record = ReadRecord("phone", "Book", "A", 1300)
        fun hour(clock: Int, duration: Long) = ReadRecordHourly("phone", "Book", "A", date.toString(), clock, duration)
        val merged = mergeReadStatistics(record, record, emptyList(), emptyList(), true, 1000, 1000,
            listOf(hour(9, 200)), listOf(hour(9, 100), hour(10, 300)))
        assertEquals(1500L, merged.record.readTime)
        assertEquals(listOf(ReadRecordDaily("phone", "Book", "A", date.toString(), 500)), merged.daily)
        assertEquals(listOf(200L, 300L), merged.hourly.map { it.readTime })
        val repeated = mergeReadStatistics(merged.record, record, merged.daily, emptyList(), true,
            merged.historyTime, 1000, merged.hourly, listOf(hour(9, 100), hour(10, 300)))
        assertEquals(merged, repeated)
        val old = mergeReadStatistics(merged.record, record, merged.daily, null, true,
            merged.historyTime, currentHourly = merged.hourly)
        assertEquals(merged, old)
    }

    @Test fun oldDailyTimeRemainsUnassignedWhenHourlyBackupIsPartial() {
        val record = ReadRecord("phone", "Book", "A", 10_000)
        val daily = listOf(ReadRecordDaily("phone", "Book", "A", date.toString(), 5000))
        val hourly = listOf(ReadRecordHourly("phone", "Book", "A", date.toString(), 9, 1000))
        val result = mergeReadStatistics(record, record, daily, daily, true,
            currentHourly = hourly, incomingHourly = hourly)
        assertEquals(10_000L, result.record.readTime)
        assertEquals(daily, result.daily)
        assertEquals(hourly, result.hourly)
    }
}
