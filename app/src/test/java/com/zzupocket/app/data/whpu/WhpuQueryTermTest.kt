package com.zzupocket.app.data.whpu

import com.zzupocket.app.ui.nav.AppRoutes
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class WhpuQueryTermTest {
    private val today = LocalDate.of(2030, 11, 13)
    private val current = WhpuTerm(2030, "3")
    private val previous = WhpuTerm(2029, "12")

    @Test fun normalQueriesRestoreSavedTermAheadOfCurrentSchedule() {
        WhpuQuery.entries.forEach {
            assertEquals(previous, initialWhpuQueryTerm(it, previous, current, today))
        }
    }

    @Test fun dailyExamEntryUsesCurrentScheduleEvenAfterHistoricalQuery() {
        assertEquals(current, initialWhpuQueryTerm(WhpuQuery.EXAMS, previous, current, today, true))
        assertEquals(previous, initialWhpuQueryTerm(WhpuQuery.EXAMS, previous, null, today, true))
    }

    @Test fun firstOpenPreservesExistingDefaults() {
        assertEquals(previous, initialWhpuQueryTerm(WhpuQuery.EXAMS, null, previous, today))
        assertEquals(current, initialWhpuQueryTerm(WhpuQuery.GRADES, null, previous, today))
        assertEquals(WhpuTerm(2029, "3"), initialWhpuQueryTerm(WhpuQuery.GRADES, null, null, LocalDate.of(2030, 1, 15)))
        assertEquals(WhpuTerm(2029, "12"), initialWhpuQueryTerm(WhpuQuery.EXAMS, null, null, LocalDate.of(2030, 2, 15)))
    }

    @Test fun existingWidgetIntentsKeepOpeningCurrentTerm() {
        assertEquals(AppRoutes.CURRENT_EXAMS, AppRoutes.widgetDestination(AppRoutes.EXAMS))
        assertEquals(AppRoutes.CURRENT_EXAMS, AppRoutes.widgetDestination(AppRoutes.CURRENT_EXAMS))
        assertEquals(AppRoutes.SCHEDULE, AppRoutes.widgetDestination(AppRoutes.SCHEDULE))
    }
}
