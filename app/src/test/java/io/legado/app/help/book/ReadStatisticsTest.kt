package io.legado.app.help.book

import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordDaily
import io.legado.app.data.entities.ReadRecordDailyShow
import io.legado.app.data.entities.ReadRecordHourlyShow
import io.legado.app.data.entities.ReadRecordShow
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.util.TimeZone

class ReadStatisticsTest {
    private fun time(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()
    private fun split(end: String, elapsed: Long, zone: String = "Asia/Shanghai") =
        splitReadInterval(time(end), elapsed, TimeZone.getTimeZone(zone))

    @Test fun midnightKeepsBothDaysAndEveryMillisecond() {
        assertEquals(listOf(ReadDayDuration("2026-10-16", 60_000), ReadDayDuration("2026-10-17", 90_001)),
            split("2026-10-17T00:01:30.001+08:00", 150_001))
        assertEquals(listOf(ReadDayDuration("2026-10-16", 60_000)),
            split("2026-10-17T00:00:00+08:00", 60_000))
    }

    @Test fun monthAndYearBoundariesAreSplit() {
        assertEquals(listOf("2026-12-31", "2027-01-01"),
            split("2027-01-01T00:01:00+08:00", 120_000).map { it.date })
        assertEquals(listOf("2028-02-29", "2028-03-01"),
            split("2028-03-01T00:01:00+08:00", 120_000).map { it.date })
    }

    @Test fun daylightSavingDaysUseTheirActualLength() {
        val spring = split("2026-03-09T00:00:00-04:00", 23 * 3_600_000L, "America/New_York")
        val fall = split("2026-11-02T00:00:00-05:00", 25 * 3_600_000L, "America/New_York")
        assertEquals(listOf(ReadDayDuration("2026-03-08", 23 * 3_600_000L)), spring)
        assertEquals(listOf(ReadDayDuration("2026-11-01", 25 * 3_600_000L)), fall)
    }

    @Test fun emptyIntervalsDoNotCreateReadingDays() {
        assertTrue(splitReadInterval(100, 0).isEmpty())
        assertTrue(splitReadInterval(100, -1).isEmpty())
        assertTrue(splitReadInterval(0, 100).isEmpty())
        assertEquals(100L, splitReadInterval(100, 200, TimeZone.getTimeZone("UTC")).sumOf { it.duration })
    }

    @Test fun longIntervalsRetainTotalsAcrossManyDays() {
        val elapsed = 370 * 86_400_000L + 12345
        val days = split("2027-02-12T15:00:00+08:00", elapsed)
        assertEquals(elapsed, days.sumOf { it.duration })
        assertEquals(days.size, days.map { it.date }.distinct().size)
    }

    @Test fun malformedDatesCannotEnterStatistics() {
        assertTrue(isReadStatisticsDate("2028-02-29"))
        listOf("2026-02-29", "2026-13-01", "2026-10-32", "2026-1-01", "2026-01-01x", "").forEach {
            assertFalse(it, isReadStatisticsDate(it))
        }
    }

    private fun daily(date: String, duration: Long, words: Long = 0L) =
        ReadRecordDaily("phone", "Book", "A", date, duration, readWords = words)
    private val parent = ReadRecord("phone", "Book", "A", 1000, 100, lastChapterTitle = "Saved chapter", lastChapterIndex = 7)

    @Test fun allocateWordsKeepsTheWholeDeltaAcrossDateBoundaries() {
        val shares = allocateReadWords(10, listOf(1L, 2L, 3L))
        assertEquals(10L, shares.sum())
        assertEquals(listOf(1L, 3L, 6L), shares)
    }

    @Test fun restoringNewBackupTwiceKeepsHistoryAndDatesOnce() {
        val rows = listOf(daily("2026-10-16", 200), daily("2026-10-17", 300))
        val first = mergeReadStatistics(null, parent, emptyList(), rows, true)
        val second = mergeReadStatistics(first.record, parent, first.daily, rows, true)
        assertEquals(parent, first.record)
        assertEquals(first, second)
        assertEquals(500L, first.record.readTime - first.daily.sumOf { it.readTime })
    }

    @Test fun restoringLegacyBackupDoesNotEraseNewWordCount() {
        val current = parent.copy(readWords = 600)
        val restored = mergeReadStatistics(
            current = current,
            incoming = parent.copy(readWords = 0),
            currentDaily = emptyList(),
            incomingDaily = null,
            localDevice = false,
        )

        assertEquals(600L, restored.record.readWords)
    }

    @Test fun differentDaysFromDivergentBackupsMergeWithoutDuplicatingOldTotal() {
        val current = parent.copy(readTime = 1200, lastRead = 200)
        val incoming = parent.copy(readTime = 1300, lastRead = 300, lastChapterIndex = 9)
        val merged = mergeReadStatistics(current, incoming,
            listOf(daily("2026-10-16", 200)), listOf(daily("2026-10-17", 300)), true, 1000, 1000)
        assertEquals(1500L, merged.record.readTime)
        assertEquals(9, merged.record.lastChapterIndex)
        assertEquals(500L, merged.daily.sumOf { it.readTime })
        assertEquals(merged, mergeReadStatistics(merged.record, incoming, merged.daily,
            listOf(daily("2026-10-17", 300)), true, 1000, 1000))
    }

    @Test fun addingDetailsAfterAnOldFormatRestoreNeverCountsThoseDetailsTwice() {
        val oldOnly = mergeReadStatistics(null, parent, emptyList(), null, true)
        val enriched = mergeReadStatistics(oldOnly.record, parent, emptyList(),
            listOf(daily("2026-10-16", 200)), true, incomingHistory = 800)
        assertEquals(1000L, enriched.record.readTime)
        val more = parent.copy(readTime = 2000)
        val complete = mergeReadStatistics(enriched.record, more, enriched.daily,
            listOf(daily("2026-10-16", 200), daily("2026-10-17", 1000)), true,
            enriched.historyTime, 800)
        assertEquals(2000L, complete.record.readTime)
        assertEquals(800L, complete.historyTime)
    }

    @Test fun oldFormatTotalRemainsAFloorUntilItsMissingDailyCoverageArrives() {
        val oldOnly = parent.copy(readTime = 2000)
        val partial = mergeReadStatistics(oldOnly, parent, emptyList(),
            listOf(daily("2026-10-16", 200)), true, incomingHistory = 800)
        assertEquals(2000L, partial.record.readTime)
        val complete = mergeReadStatistics(partial.record, oldOnly, partial.daily,
            listOf(daily("2026-10-16", 200), daily("2026-10-17", 1000)), true,
            partial.historyTime, 800)
        assertEquals(2000L, complete.record.readTime)
    }
    @Test fun sameDayCounterUsesMaximumAndRetainsNewerPosition() {
        val current = parent.copy(readTime = 1400, lastRead = 300)
        val merged = mergeReadStatistics(current, parent.copy(readTime = 1200),
            listOf(daily("2026-10-17", 400)), listOf(daily("2026-10-17", 200)), false)
        assertEquals(current, merged.record)
        assertEquals(listOf(daily("2026-10-17", 400)), merged.daily)
    }

    @Test fun oldBackupKeepsKnownDatesAndExistingDeviceMergeRules() {
        val current = parent.copy(readTime = 1200)
        val rows = listOf(daily("2026-10-17", 200))
        val local = mergeReadStatistics(current, parent, rows, null, true)
        assertEquals(current, local.record)
        assertEquals(rows, local.daily)
        val remote = mergeReadStatistics(current, parent, rows, null, false)
        assertEquals(parent, remote.record)
        assertEquals(rows, remote.daily)
    }

    @Test fun cumulativeKeepsOldBooksWhilePeriodsOnlyUseDatedDetails() {
        val records = listOf(ReadRecordShow("Book", 1000, 100, "A", lastChapterIndex = 8),
            ReadRecordShow("Old only", 9000, 10, "B"))
        val rows = listOf(ReadRecordDailyShow("Book", "A", "2025-12-31", 100),
            ReadRecordDailyShow("Book", "A", "2026-09-01", 200),
            ReadRecordDailyShow("Book", "A", "2026-10-17", 300))
        val snapshot = ReadStatisticsSnapshot(records, rows, 1)
        val all = snapshot.summary(ReadStatisticsPeriod.ALL, 2026, 10)
        assertEquals(10000L, all.duration)
        assertEquals(9400L, all.historyDuration)
        assertEquals(200L, all.average)
        assertEquals("Old only", all.books.first().bookName)
        val year = snapshot.summary(ReadStatisticsPeriod.YEAR, 2026, 10)
        assertEquals(500L, year.duration)
        assertEquals(0L, year.historyDuration)
        assertEquals(2, year.days.size)
        assertEquals(8, year.books.single().lastChapterIndex)
        val month = snapshot.summary(ReadStatisticsPeriod.MONTH, 2026, 10)
        assertEquals(300L, month.duration)
        assertEquals(listOf("2026-10-17"), month.days.keys.toList())
        assertTrue(snapshot.summary(ReadStatisticsPeriod.YEAR, 2024, 10).books.isEmpty())
    }

    @Test fun summaryAggregatesWordsAndCalculatesDatedSpeed() {
        val snapshot = ReadStatisticsSnapshot(
            listOf(ReadRecordShow("Book", 600_000, 100, "A", readWords = 600)),
            listOf(
                ReadRecordDailyShow("Book", "A", "2026-10-17", 600_000, 600),
            ),
            1,
        )

        val summary = snapshot.summary(ReadStatisticsPeriod.DAY, 2026, 10, day = 17)

        assertEquals(600L, summary.words)
        assertEquals(600L, summary.datedWords)
        assertEquals(600_000L, summary.wordDuration)
        assertEquals(60L, summary.speed)
        assertEquals(600L, snapshot.booksForDay("2026-10-17").single().readWords)
    }

    @Test fun wordlessLegacyDaysDoNotDiluteSpeed() {
        val snapshot = ReadStatisticsSnapshot(
            listOf(ReadRecordShow("Book", 7_200_000, 100, "A", readWords = 600)),
            listOf(
                ReadRecordDailyShow("Book", "A", "2025-01-01", 6_600_000, 0),
                ReadRecordDailyShow("Book", "A", "2026-10-17", 600_000, 600),
            ),
            1,
        )

        val summary = snapshot.summary(ReadStatisticsPeriod.ALL, 2026, 10)

        assertEquals(7_200_000L, summary.datedDuration)
        assertEquals(600L, summary.datedWords)
        assertEquals(600_000L, summary.wordDuration)
        assertEquals(60L, summary.speed)
        assertEquals(60L, summary.bookSpeeds["Book" to "A"])
    }

    @Test fun sameTitleDifferentAuthorsRemainSeparateInPeriodRanking() {
        val snapshot = ReadStatisticsSnapshot(
            listOf(ReadRecordShow("Same", 1000, 0, "A"), ReadRecordShow("Same", 2000, 0, "B")),
            listOf(ReadRecordDailyShow("Same", "A", "2026-10-17", 100),
                ReadRecordDailyShow("Same", "B", "2026-10-17", 200)), 1,
        )
        val summary = snapshot.summary(ReadStatisticsPeriod.MONTH, 2026, 10)
        assertEquals(listOf("B", "A"), summary.books.map { it.author })
        assertEquals(300L, summary.duration)
        assertEquals(1, summary.days.size)
    }

    @Test fun singleBookSnapshotFiltersAllDetailsAndKeepsCombinedAuthorHistory() {
        val snapshot = ReadStatisticsSnapshot(
            listOf(
                ReadRecordShow("Book", 1000, 0, "A"),
                ReadRecordShow("Book", 2000, 0, "B"),
                ReadRecordShow("Other", 3000, 0, "A"),
                ReadRecordShow("Legacy", 4000, 0, "\u001Eauthors:[\"A\",\"B\"]"),
            ),
            listOf(
                ReadRecordDailyShow("Book", "A", "2026-10-17", 100),
                ReadRecordDailyShow("Book", "B", "2026-10-17", 200),
                ReadRecordDailyShow("Other", "A", "2026-10-17", 300),
                ReadRecordDailyShow("Legacy", "\u001Eauthors:[\"A\",\"B\"]", "2026-10-18", 400),
            ),
            1,
            listOf(
                ReadRecordHourlyShow("Book", "A", "2026-10-17", 9, 100),
                ReadRecordHourlyShow("Book", "B", "2026-10-17", 10, 200),
                ReadRecordHourlyShow("Other", "A", "2026-10-17", 11, 300),
                ReadRecordHourlyShow("Legacy", "\u001Eauthors:[\"A\",\"B\"]", "2026-10-18", 12, 400),
            ),
        )

        val filtered = snapshot.forBook("Book", "A")
        assertEquals(listOf("Book"), filtered.records.map { it.bookName })
        assertEquals(listOf(100L), filtered.daily.map { it.readTime })
        assertEquals(listOf(100L), filtered.hourly.map { it.readTime })
        assertEquals(100L, filtered.summary(ReadStatisticsPeriod.MONTH, 2026, 10).duration)

        val legacy = snapshot.forBook("Legacy", "A")
        assertEquals(listOf(4000L), legacy.records.map { it.readTime })
        assertEquals(listOf("2026-10-18"), legacy.daily.map { it.date })
    }

    @Test fun thresholdsUseTheSelectedPeriodsAggregatedBookTime() {
        val snapshot = ReadStatisticsSnapshot(
            listOf(ReadRecordShow("Book", 20_000, 0, "A")),
            listOf(
                ReadRecordDailyShow("Book", "A", "2025-12-31", 1000),
                ReadRecordDailyShow("Book", "A", "2026-09-01", 2000),
                ReadRecordDailyShow("Book", "A", "2026-10-16", 3000),
                ReadRecordDailyShow("Book", "A", "2026-10-17", 3000),
            ), 1,
        )
        val month = snapshot.summary(ReadStatisticsPeriod.MONTH, 2026, 10)
        assertEquals(6000L, month.books.visibleReadStatisticsBooks(6000).single().readTime)
        assertTrue(month.books.visibleReadStatisticsBooks(6001).isEmpty())
        val year = snapshot.summary(ReadStatisticsPeriod.YEAR, 2026, 10)
        assertEquals(8000L, year.books.visibleReadStatisticsBooks(8000).single().readTime)
        assertTrue(year.books.visibleReadStatisticsBooks(8001).isEmpty())
        val all = snapshot.summary(ReadStatisticsPeriod.ALL, 2026, 10)
        assertEquals(20_000L, all.books.visibleReadStatisticsBooks(20_000).single().readTime)
        assertTrue(snapshot.summary(ReadStatisticsPeriod.MONTH, 2025, 12)
            .books.visibleReadStatisticsBooks(1001).isEmpty())
    }

    @Test fun filteringKeepsTimeDaysAverageAndHistoricalBooksIntact() {
        val snapshot = ReadStatisticsSnapshot(
            listOf(ReadRecordShow("Book", 1000, 0, "A"), ReadRecordShow("Old", 9000, 0, "B")),
            listOf(ReadRecordDailyShow("Book", "A", "2026-10-17", 300)), 1,
        )
        val all = snapshot.summary(ReadStatisticsPeriod.ALL, 2026, 10)
        assertEquals(listOf("Old"), all.books.visibleReadStatisticsBooks(9000).map { it.bookName })
        assertTrue(all.books.visibleReadStatisticsBooks(Long.MAX_VALUE).isEmpty())
        assertEquals(10_000L, all.duration)
        assertEquals(9700L, all.historyDuration)
        assertEquals(300L, all.average)
        assertEquals(mapOf("2026-10-17" to 300L), all.days)
        assertEquals(2, all.books.size)
        assertEquals(all, snapshot.summary(ReadStatisticsPeriod.ALL, 2026, 10))
    }

    @Test fun zeroThresholdKeepsRankingAndEqualityIncludesTheBook() {
        val ranked = listOf(ReadRecordShow("Same", 2000, 0, "B"), ReadRecordShow("Same", 1000, 0, "A"))
        assertEquals(ranked, ranked.visibleReadStatisticsBooks(0))
        assertEquals(ranked, ranked.visibleReadStatisticsBooks(-1))
        assertEquals(listOf("B"), ranked.visibleReadStatisticsBooks(2000).map { it.author })
        assertTrue(ranked.visibleReadStatisticsBooks(2001).isEmpty())
        assertTrue(emptyList<ReadRecordShow>().visibleReadStatisticsBooks(0).isEmpty())
    }

    private val scopeSnapshot = ReadStatisticsSnapshot(
        listOf(ReadRecordShow("Same", 1_200_000, 0, "A", lastChapterIndex = 8),
            ReadRecordShow("Same", 480_000, 0, "B"),
            ReadRecordShow("Historical", 3_600_000, 0, "C")),
        listOf(
            ReadRecordDailyShow("Same", "A", "2026-09-01", 480_000),
            ReadRecordDailyShow("Same", "A", "2026-10-16", 360_000),
            ReadRecordDailyShow("Same", "A", "2026-10-17", 360_000),
            ReadRecordDailyShow("Same", "B", "2026-10-15", 480_000),
        ), 1,
    )

    @Test fun dailyRankingKeepsAuthorsAndAggregatesBeforeApplyingItsOwnThreshold() {
        val snapshot = ReadStatisticsSnapshot(
            listOf(ReadRecordShow("Same", 1000, 10, "A", lastChapterIndex = 8),
                ReadRecordShow("Same", 900, 20, "B"), ReadRecordShow("Old", 5000, 30, "C")),
            listOf(ReadRecordDailyShow("Same", "A", "2026-10-17", 200),
                ReadRecordDailyShow("Same", "A", "2026-10-17", 100),
                ReadRecordDailyShow("Same", "B", "2026-10-17", 200),
                ReadRecordDailyShow("Same", "A", "2026-10-16", 700)), 1,
        )
        val day = snapshot.booksForDay("2026-10-17")
        assertEquals(listOf("A", "B"), day.map { it.author })
        assertEquals(listOf(300L, 200L), day.map { it.readTime })
        assertEquals(8, day.first().lastChapterIndex)
        assertEquals("A", day.visibleReadStatisticsBooks(300).single().author)
        assertTrue(day.visibleReadStatisticsBooks(301).isEmpty())
        assertTrue(snapshot.booksForDay("2026-10-18").isEmpty())
        assertEquals(1000L, snapshot.records.first().readTime)
    }

    @Test fun dailyRankingIsIndependentOfMonthEligibilityAndNeverAddsHistoricalTime() {
        assertTrue(scopeSnapshot.summary(ReadStatisticsPeriod.MONTH, 2026, 10, 900_000).books.isEmpty())
        val day = scopeSnapshot.booksForDay("2026-10-15").visibleReadStatisticsBooks(480_000)
        assertEquals("B", day.single().author)
        assertEquals(480_000L, day.single().readTime)
        assertFalse(scopeSnapshot.booksForDay("2026-10-17").any { it.bookName == "Historical" })
        assertEquals(listOf("A"), scopeSnapshot.summary(ReadStatisticsPeriod.MONTH, 2026, 10, 600_000)
            .books.map { it.author })
        assertEquals("B", scopeSnapshot.booksForDay("2026-10-15").single().author)
    }

    @Test fun qualifiedMonthKeepsWholeBooksAndRecomputesDaysAndAverage() {
        val all = scopeSnapshot.summary(ReadStatisticsPeriod.MONTH, 2026, 10)
        val qualified = scopeSnapshot.summary(ReadStatisticsPeriod.MONTH, 2026, 10, 600_000)
        assertEquals(1_200_000L, all.duration)
        assertEquals(3, all.days.size)
        assertEquals(400_000L, all.average)
        assertEquals(720_000L, qualified.duration)
        assertEquals(720_000L, qualified.datedDuration)
        assertEquals(0L, qualified.historyDuration)
        assertEquals(mapOf("2026-10-16" to 360_000L, "2026-10-17" to 360_000L), qualified.days)
        assertEquals(360_000L, qualified.average)
        assertEquals("A", qualified.books.single().author)
        assertEquals(8, qualified.books.single().lastChapterIndex)
        assertEquals(all, scopeSnapshot.summary(ReadStatisticsPeriod.MONTH, 2026, 10))
    }

    @Test fun qualifyingUsesCurrentPeriodAndIncludesExactThreshold() {
        assertTrue(scopeSnapshot.summary(ReadStatisticsPeriod.MONTH, 2026, 10, 1_200_000).books.isEmpty())
        val year = scopeSnapshot.summary(ReadStatisticsPeriod.YEAR, 2026, 10, 1_200_000)
        assertEquals("A", year.books.single().author)
        assertEquals(1_200_000L, year.duration)
        assertEquals(3, year.days.size)
        val all = scopeSnapshot.summary(ReadStatisticsPeriod.ALL, 2026, 10, 1_200_000)
        assertEquals(listOf("Historical", "Same"), all.books.map { it.bookName })
        assertEquals(4_800_000L, all.duration)
        assertEquals(3_600_000L, all.historyDuration)
    }

    @Test fun historicalOnlyQualifiedBookNeverCreatesDatesOrDailyAverage() {
        val result = scopeSnapshot.summary(ReadStatisticsPeriod.ALL, 2026, 10, 3_600_000)
        assertEquals("Historical", result.books.single().bookName)
        assertEquals(3_600_000L, result.duration)
        assertEquals(3_600_000L, result.historyDuration)
        assertEquals(0L, result.datedDuration)
        assertTrue(result.days.isEmpty())
        assertEquals(0L, result.average)
    }

    @Test fun noQualifiedBooksProducesZeroStatisticsAcrossAllPeriods() {
        ReadStatisticsPeriod.entries.forEach { period ->
            val result = scopeSnapshot.summary(period, 2026, 10, Long.MAX_VALUE)
            assertEquals(0L, result.duration)
            assertEquals(0L, result.datedDuration)
            assertEquals(0L, result.historyDuration)
            assertEquals(0L, result.average)
            assertTrue(result.days.isEmpty())
            assertTrue(result.books.isEmpty())
        }
    }

    @Test fun zeroAndNegativeThresholdsKeepAllStatisticsIdentical() {
        ReadStatisticsPeriod.entries.forEach { period ->
            val all = scopeSnapshot.summary(period, 2026, 10)
            assertEquals(all, scopeSnapshot.summary(period, 2026, 10, 0))
            assertEquals(all, scopeSnapshot.summary(period, 2026, 10, -1))
        }
    }
}
