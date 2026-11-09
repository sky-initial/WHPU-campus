package com.zzupocket.app.data.whpu

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.TimeZone

class WhpuExamAgendaTest {
    private fun exam(time: String?, name: String = "人工课程") = WhpuExam(name, "SYN-01", time,
        "示例考场", "示例校区", null, null, null, null, null)
    private val day = LocalDate.of(2030, 1, 10)
    private fun cache(vararg exams: WhpuExam): CurrentWhpuExams {
        val term = WhpuTerm(2029, "3")
        return CurrentWhpuExams(term, WhpuExamsSnapshot(term, exams.toList(), 1))
    }

    @Test fun parsesSchoolTimeWithoutUsingTeachingWeek() {
        assertEquals(WhpuExamTime(day, LocalTime.of(9, 0), LocalTime.of(11, 0)),
            WhpuExamAgenda.parseTime("2030-01-10(09:00-11:00)"))
    }
    @Test fun acceptsWhitespaceAndFullWidthParentheses() {
        assertEquals(WhpuExamAgenda.parseTime("2030-01-10(09:00-11:00)"),
            WhpuExamAgenda.parseTime(" 2030-01-10 （09:00 - 11:00） "))
    }
    @Test fun rejectsInvalidDateAndClockInsteadOfNormalizing() {
        listOf("2030-02-29(09:00-11:00)", "2030-01-10(24:00-25:00)",
            "2030-01-10(09:60-11:00)", "2030-01-10(09:00-09:00)",
            "2030-01-10(23:00-01:00)").forEach { assertNull(it, WhpuExamAgenda.parseTime(it)) }
    }
    @Test fun incompleteOrAnnotatedTimeRemainsUnplaced() {
        listOf(null, "", "待定", "2030-01-10", "2030-01-10(09:00-11:00)取消").forEach {
            assertNull(WhpuExamAgenda.parseTime(it))
        }
    }
    @Test fun filtersByActualDateAndSortsWithoutDroppingOverlaps() {
        val result = cache(exam("2030-01-10(14:00-16:00)", "下午"),
            exam("2030-01-11(09:00-11:00)", "明天"),
            exam("2030-01-10(09:00-11:00)", "上午一"),
            exam("2030-01-10(09:00-10:00)", "上午二"), exam("待定"))
        assertEquals(listOf("上午一", "上午二", "下午"), result.on(day).map { it.exam.courseName })
        assertEquals(1, result.on(day.plusDays(1)).size)
        assertEquals(1, result.unplacedCount)
    }
    @Test fun keepsMissingCacheDistinctFromSyncedEmpty() {
        assertNull(CurrentWhpuExams(WhpuTerm(2029, "3"), null).snapshot)
        assertNotNull(cache().snapshot)
        assertTrue(cache().on(day).isEmpty())
    }
    @Test fun statusUsesShanghaiAndHalfOpenBoundaries() {
        val time = WhpuExamAgenda.parseTime("2030-01-10(09:00-11:00)")!!
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Halifax"))
            assertEquals("未开始", time.status(Instant.parse("2030-01-10T00:59:59Z")))
            assertEquals("进行中", time.status(Instant.parse("2030-01-10T01:00:00Z")))
            assertEquals("进行中", time.status(Instant.parse("2030-01-10T02:59:59Z")))
            assertEquals("已结束", time.status(Instant.parse("2030-01-10T03:00:00Z")))
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun shanghaiMidnightMovesExamToNextDay() {
        val instant = Instant.parse("2030-01-09T16:00:00Z")
        val exams = cache(exam("2030-01-10(09:00-11:00)"))
        assertEquals(1, exams.on(instant.atZone(WhpuCalendar.zone).toLocalDate()).size)
        assertTrue(exams.on(instant.minusSeconds(1).atZone(WhpuCalendar.zone).toLocalDate()).isEmpty())
    }
}
