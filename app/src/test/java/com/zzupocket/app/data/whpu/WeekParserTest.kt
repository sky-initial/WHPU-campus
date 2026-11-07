package com.zzupocket.app.data.whpu

import org.junit.Assert.assertEquals
import org.junit.Test

class WeekParserTest {
    @Test fun parsesRangesAndSingleWeeks() {
        assertEquals(setOf(4, 5, 6, 7, 8), WeekParser.parse("4-8周"))
        assertEquals(setOf(9), WeekParser.parse("9周"))
    }

    @Test fun parsesMultipleSegmentsAndParity() {
        assertEquals(setOf(4, 7, 9, 10), WeekParser.parse("4周,7-9周(单),10周"))
        assertEquals(setOf(5, 6, 7, 10, 12), WeekParser.parse("5-7周,10-12周(双)"))
    }

    @Test fun blankIsEmpty() {
        assertEquals(emptySet<Int>(), WeekParser.parse(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSegmentIsRejected() {
        WeekParser.parse("1-3周,未知")
    }
}
