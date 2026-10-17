package io.legado.app.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadStatisticsMinimumTest {
    @Test fun blankAndZeroDisableFiltering() {
        assertEquals(0L, readStatisticsMinimumMillis("", "", ""))
        assertEquals(0L, readStatisticsMinimumMillis("0", "00", "0"))
        assertEquals(5_000L, readStatisticsMinimumMillis("", "", "5"))
    }

    @Test fun combinesUnitsAndAllowsMoreThanOneDay() {
        assertEquals(3_723_000L, readStatisticsMinimumMillis("1", "2", "3"))
        assertEquals(360_000_000L, readStatisticsMinimumMillis("100", "", ""))
        assertEquals(3_599_000L, readStatisticsMinimumMillis("", "59", "59"))
    }

    @Test fun rejectsOutOfRangeOrNonNumericComponents() {
        for (value in listOf("-1", "+1", "1.5", "abc", " ", "9223372036854775808")) {
            assertNull(readStatisticsMinimumMillis(value, "", ""))
            assertNull(readStatisticsMinimumMillis("", value, ""))
            assertNull(readStatisticsMinimumMillis("", "", value))
        }
        assertNull(readStatisticsMinimumMillis("", "60", ""))
        assertNull(readStatisticsMinimumMillis("", "", "60"))
    }

    @Test fun checksTheCombinedDurationForOverflow() {
        val maximumSeconds = Long.MAX_VALUE / 1000
        val hours = maximumSeconds / 3600
        val minutes = maximumSeconds / 60 % 60
        val seconds = maximumSeconds % 60
        assertEquals(maximumSeconds * 1000, readStatisticsMinimumMillis(
            hours.toString(), minutes.toString(), seconds.toString()))
        assertNull(readStatisticsMinimumMillis(hours.toString(), minutes.toString(), (seconds + 1).toString()))
        assertNull(readStatisticsMinimumMillis((hours + 1).toString(), "", ""))
        assertNull(readStatisticsMinimumMillis(Long.MAX_VALUE.toString(), "59", "59"))
    }
}
