package com.zzupocket.app.dnd

import com.zzupocket.app.core.WeekSettings
import com.zzupocket.app.core.WeekStartDay
import com.zzupocket.app.data.whpu.WhpuCalendar
import com.zzupocket.app.data.whpu.WhpuTerm
import com.zzupocket.app.domain.Session
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ClassDndPlannerTest {
    private val term = WhpuTerm(2026, "3").semesterId
    private val week = WeekSettings(LocalDate.of(2026, 9, 7), confirmed = true)
    private fun lesson(id: String = "synthetic", source: String = "whpu", weekIndex: Int = 1, weekday: Int = 1,
        start: Int = 1, end: Int = 2, date: String? = null, from: String? = null, until: String? = null) =
        Session(id, term, source, "SYN-01", "人工课程", weekIndex, weekday, start, end, date, from, until, null, null, null, null)
    private fun at(value: String) = Instant.parse(value)
    private fun plan(now: String, rows: List<Session> = listOf(lesson()), settings: WeekSettings = week, id: Int? = term) =
        ClassDndPlanner.plan(id, settings, rows, WhpuCalendar.periods(), at(now))

    @Test fun startsAtExactClassBoundaryInShanghaiTime() {
        val before = plan("2026-09-06T23:59:59Z")
        assertFalse(before.active); assertEquals(at("2026-09-07T00:00:00Z"), before.nextBoundary)
        val start = plan("2026-09-07T00:00:00Z")
        assertTrue(start.active); assertEquals(at("2026-09-07T01:40:00Z"), start.nextBoundary)
    }
    @Test fun endsAtClassEndAndDoesNotStayActiveAfterTerm() {
        assertFalse(plan("2026-09-07T01:40:00Z").active)
        assertNull(plan("2027-01-01T00:00:00Z").nextBoundary)
    }
    @Test fun lessonBlockIncludesItsShortBreak() { assertTrue(plan("2026-09-07T00:50:00Z").active) }
    @Test fun separateLessonsReleaseDuringGap() {
        val result = plan("2026-09-07T01:50:00Z", listOf(lesson(), lesson("second", start = 3, end = 4)))
        assertFalse(result.active); assertEquals(at("2026-09-07T02:10:00Z"), result.nextBoundary)
    }
    @Test fun overlappingLessonsDoNotEndDndEarly() {
        val result = plan("2026-09-07T01:00:00Z", listOf(lesson(), lesson("second", start = 2, end = 3)))
        assertEquals(1, result.intervals.size); assertEquals(at("2026-09-07T02:55:00Z"), result.nextBoundary)
    }
    @Test fun touchingLessonsAreOneInterval() {
        val rows = listOf(lesson(from = "08:00", until = "09:00"), lesson("b", from = "09:00", until = "10:00"))
        assertEquals(at("2026-09-07T02:00:00Z"), plan("2026-09-07T00:30:00Z", rows).nextBoundary)
    }
    @Test fun duplicateRowsDoNotCreateExtraTransitions() {
        assertEquals(1, plan("2026-09-07T00:30:00Z", listOf(lesson(), lesson())).intervals.size)
    }
    @Test fun oddWeekRowsDoNotTriggerEvenWeek() {
        val result = plan("2026-09-14T00:30:00Z", listOf(lesson(), lesson("third", weekIndex = 3)))
        assertFalse(result.active); assertEquals(at("2026-09-21T00:00:00Z"), result.nextBoundary)
    }
    @Test fun explicitDateWinsOverGridDate() {
        val rows = listOf(lesson(date = "2026-09-08"))
        assertFalse(plan("2026-09-07T00:30:00Z", rows).active)
        assertTrue(plan("2026-09-08T00:30:00Z", rows).active)
    }
    @Test fun demoCustomAndOtherSchoolDataNeverTrigger() {
        val rows = listOf("whpu-demo", "custom", "eas", "gms").map { lesson(source = it) }
        assertTrue(plan("2026-09-07T00:30:00Z", rows).intervals.isEmpty())
        assertTrue(plan("2026-09-07T00:30:00Z", id = -20001).intervals.isEmpty())
    }
    @Test fun anotherSemesterIsExcluded() {
        assertTrue(plan("2026-09-07T00:30:00Z", listOf(lesson().copy(semesterId = term + 1))).intervals.isEmpty())
    }
    @Test fun unconfirmedOrMissingFirstWeekNeverTriggers() {
        assertFalse(plan("2026-09-07T00:30:00Z", settings = week.copy(confirmed = false)).active)
        assertTrue(plan("2026-09-07T00:30:00Z", settings = week.copy(weekOneStart = null)).intervals.isEmpty())
    }
    @Test fun emptyScheduleRemovesAllBoundaries() {
        val result = plan("2026-09-07T00:30:00Z", emptyList())
        assertFalse(result.active); assertNull(result.nextBoundary)
    }
    @Test fun invalidDateOrPeriodDoesNotInventTimes() {
        assertTrue(plan("2026-09-07T00:30:00Z", listOf(lesson(date = "invalid"), lesson(start = 99), lesson(weekday = 0))).intervals.isEmpty())
    }
    @Test fun reversedTimesAndUnspecifiedPracticeTimeDoNotTrigger() {
        val rows = listOf(lesson(from = "10:00", until = "08:00"), lesson(start = 0, end = 0))
        assertTrue(plan("2026-09-07T00:30:00Z", rows).intervals.isEmpty())
    }
    @Test fun dateAdjustmentReschedulesCourse() {
        val result = plan("2026-09-07T00:30:00Z", settings = week.copy(weekOneStart = week.weekOneStart!!.plusWeeks(1)))
        assertFalse(result.active); assertEquals(at("2026-09-14T00:00:00Z"), result.nextBoundary)
    }
    @Test fun declaredWeekLimitExcludesLaterGridRows() {
        assertTrue(plan("2026-09-14T00:30:00Z", listOf(lesson(weekIndex = 2)), week.copy(totalWeeks = 1)).intervals.isEmpty())
    }
    @Test fun sundayWeekStartUsesSameDateMappingAsTimetable() {
        val settings = week.copy(weekOneStart = LocalDate.of(2026, 9, 6), startDay = WeekStartDay.SUNDAY)
        assertTrue(plan("2026-09-07T00:30:00Z", settings = settings).active)
    }
    @Test fun nextSemesterCourseCanBeScheduledBeforeItsFirstWeek() {
        assertEquals(at("2026-09-07T00:00:00Z"), plan("2026-08-01T00:00:00Z").nextBoundary)
    }
}
