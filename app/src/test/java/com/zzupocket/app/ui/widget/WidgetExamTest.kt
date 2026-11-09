package com.zzupocket.app.ui.widget

import com.zzupocket.app.data.whpu.*
import com.zzupocket.app.ui.nav.AppRoutes
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WidgetExamTest {
    private val day = LocalDate.of(2030, 1, 10)
    private fun exam() = WidgetSchedule.toExam(TimedWhpuExam(
        WhpuExam("人工考试", "SYN-01", "2030-01-10(09:00-11:00)", "示例考场", "示例校区", null, null, null, null, null),
        WhpuExamAgenda.parseTime("2030-01-10(09:00-11:00)")!!))
    private fun next(today: List<WidgetCourse>, tomorrow: List<WidgetCourse> = emptyList(), now: Int = 500) =
        WidgetSchedule.nextClassPlan(false, null, today, tomorrow, now)

    @Test fun mapsExamLabelLocationAndDestination() {
        val item = exam()
        assertEquals("考试 · 人工考试", item.displayName)
        assertEquals("示例校区 · 示例考场", item.place)
        assertEquals("09:00-11:00", item.rangeText)
        assertEquals(AppRoutes.EXAMS, item.route)
        assertEquals(AppRoutes.SCHEDULE, item.copy(isExam = false).route)
    }
    @Test fun noTeachingWeekDoesNotHideDatedExam() {
        val plan = next(listOf(exam()))
        assertTrue(plan.hasCourse)
        assertFalse(plan.isVacation)
        assertTrue(plan.isExam)
        assertEquals("考试 · 人工考试", plan.name)
    }
    @Test fun tomorrowExamBypassesVacationOverlayInBothWidgets() {
        val plan = next(emptyList(), listOf(exam()))
        assertTrue(plan.header.startsWith("明日预告"))
        val two = WidgetSchedule.twoDayPlan(false, null, day, day.plusDays(1), emptyList(), listOf(exam()), 500)
        assertFalse(two.hasOverlay)
        assertTrue(two.tomorrow.rows.single().course.isExam)
        assertEquals("共有 1 项 · 考试 1 场", two.tomorrow.footer)
    }
    @Test fun ordersByTimeAndPrioritizesExamAtEqualStart() {
        val course = WidgetCourse("SYN-02", "人工课程", "", 540, 620)
        val two = WidgetSchedule.twoDayPlan(true, 1, day, day.plusDays(1), listOf(course, exam()), emptyList(), 500)
        assertEquals(listOf(true, false), two.today.rows.map { it.course.isExam })
        assertEquals(2, next(listOf(course, exam())).remaining)
        assertTrue(next(listOf(course, exam())).isExam)
        assertFalse(next(listOf(course.copy(startMin = 530), exam())).isExam)
    }
    @Test fun removesExamAtEndAndRestoresCourseDestination() {
        val course = WidgetCourse("SYN-02", "人工课程", "", 700, 750)
        assertTrue(next(listOf(exam()), now = 659).isExam)
        val plan = next(listOf(exam(), course), now = 660)
        assertEquals("人工课程", plan.name)
        assertFalse(plan.isExam)
    }
    @Test fun endedExamDoesNotLeaveVacationOrExamNavigation() {
        val plan = next(listOf(exam()), now = 660)
        assertFalse(plan.hasCourse)
        assertFalse(plan.isExam)
        assertEquals("暂无待办安排", plan.statusTitle)
    }
    @Test fun examBoundaryParticipatesInWidgetRefresh() {
        assertEquals(2_400_000L, WidgetSchedule.nextBoundaryMillis(listOf(exam()), 500, 0))
        assertEquals(7_200_000L, WidgetSchedule.nextBoundaryMillis(listOf(exam()), 540, 0))
        assertNull(WidgetSchedule.nextBoundaryMillis(listOf(exam()), 660, 0))
    }
}
