package com.zzupocket.app.data.whpu

import com.zzupocket.app.core.WeekSettings
import com.zzupocket.app.dnd.ClassDndPlanner
import com.zzupocket.app.domain.Session
import com.zzupocket.app.ui.widget.WidgetSchedule
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class WhpuPeriodSettingsTest {
    private fun changed(first: WhpuPeriodTime) = WhpuPeriodSettings.defaults().toMutableList().apply { this[0] = first }
    private val term = WhpuTerm(2030, "3").semesterId
    private fun session(source: String = "whpu") = Session("synthetic", term, source, "SYN-01", "人工课程",
        1, 1, 1, 2, null, "08:00", "09:40", null, null, null, null)

    @Test fun defaultTimesAreElevenValidPeriods() {
        assertNull(WhpuPeriodSettings.error(WhpuPeriodSettings.defaults()))
        assertEquals(WhpuCalendar.periods(), WhpuPeriodSettings.periods(WhpuPeriodSettings.defaults()))
    }
    @Test fun requiresAllElevenPeriods() {
        assertNotNull(WhpuPeriodSettings.error(WhpuPeriodSettings.defaults().dropLast(1)))
        assertNotNull(WhpuPeriodSettings.error(WhpuPeriodSettings.defaults() + WhpuPeriodTime(2200, 2245)))
    }
    @Test fun rejectsInvalidClockValues() {
        listOf(-1, 860, 2400).forEach { assertNotNull(WhpuPeriodSettings.error(changed(WhpuPeriodTime(it, 845)))) }
        assertNotNull(WhpuPeriodSettings.error(changed(WhpuPeriodTime(800, 860))))
    }
    @Test fun rejectsEmptyOrReversedLesson() {
        assertNotNull(WhpuPeriodSettings.error(changed(WhpuPeriodTime(800, 800))))
        assertNotNull(WhpuPeriodSettings.error(changed(WhpuPeriodTime(845, 800))))
    }
    @Test fun rejectsOverlappingPeriods() {
        assertNotNull(WhpuPeriodSettings.error(changed(WhpuPeriodTime(800, 856))))
    }
    @Test fun acceptsTouchingPeriodsAndVariableDurations() {
        assertNull(WhpuPeriodSettings.error(changed(WhpuPeriodTime(810, 855))))
        assertNull(WhpuPeriodSettings.error(changed(WhpuPeriodTime(800, 830))))
    }
    @Test fun updatesTimesWithoutChangingPeriodIdentity() {
        val periods = WhpuPeriodSettings.periods(changed(WhpuPeriodTime(810, 850)))
        assertEquals(WhpuCalendar.periods().first().copy(startTime = 810, endTime = 850), periods.first())
        assertEquals(WhpuCalendar.periods().drop(1), periods.drop(1))
    }
    @Test fun schoolAndCustomSessionsUseNewTimesWithoutChangingDatesOrSubjects() {
        val original = listOf(session(), session("custom"))
        val times = WhpuPeriodSettings.periods(changed(WhpuPeriodTime(810, 850)))
        assertEquals(original.map { it.copy(startTime = "08:10") }, WhpuPeriodSettings.apply(original, times))
    }
    @Test fun widgetAndDndUseSameEditedStartInsteadOfImportedTime() {
        val periods = WhpuPeriodSettings.periods(changed(WhpuPeriodTime(810, 850)))
        val sessions = WhpuPeriodSettings.apply(listOf(session()), periods)
        val plan = ClassDndPlanner.plan(term, WeekSettings(LocalDate.of(2030, 9, 2), confirmed = true),
            sessions, periods, Instant.parse("2030-09-02T00:05:00Z"))
        assertFalse(plan.active)
        assertEquals(Instant.parse("2030-09-02T00:10:00Z"), plan.nextBoundary)
        assertEquals("08:10", WidgetSchedule.toCourse(sessions.single(), periods)!!.startText)
        assertEquals("09:40", WidgetSchedule.toCourse(sessions.single(), periods)!!.endText)
    }
}
