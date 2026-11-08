package com.zzupocket.app.dnd

import com.zzupocket.app.core.WeekMath
import com.zzupocket.app.core.WeekSettings
import com.zzupocket.app.data.whpu.WhpuCalendar
import com.zzupocket.app.data.whpu.WhpuTerm
import com.zzupocket.app.domain.Period
import com.zzupocket.app.domain.Session
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class ClassInterval(val start: Instant, val end: Instant)
data class ClassDndPlan(val active: Boolean, val nextBoundary: Instant?, val intervals: List<ClassInterval>)

object ClassDndPlanner {
    fun plan(semesterId: Int?, settings: WeekSettings, sessions: List<Session>, periods: List<Period>, now: Instant): ClassDndPlan {
        val firstDay = settings.weekOneStart
        if (semesterId == null || WhpuTerm.fromSemesterId(semesterId) == null || !settings.confirmed || firstDay == null) {
            return ClassDndPlan(false, null, emptyList())
        }
        val byUnit = periods.associateBy { it.indexNo }
        val intervals = sessions.filter { it.semesterId == semesterId && it.source == "whpu" }.mapNotNull { session ->
            val date = if (session.date != null) runCatching { LocalDate.parse(session.date) }.getOrNull()
            else if (session.weekday in 1..7 && session.weekIndex >= 1 &&
                (settings.totalWeeks == null || session.weekIndex <= settings.totalWeeks)) {
                WeekMath.dateOf(firstDay, session.weekIndex, session.weekday, settings.startDay)
            } else null
            date ?: return@mapNotNull null
            val start = session.startTime?.let(::parseTime) ?: byUnit[session.startUnit]?.startTime?.let(::hhmm)
            val end = session.endTime?.let(::parseTime) ?: byUnit[session.endUnit]?.endTime?.let(::hhmm)
            if (start == null || end == null || end <= start) return@mapNotNull null
            ClassInterval(date.atTime(start).atZone(WhpuCalendar.zone).toInstant(), date.atTime(end).atZone(WhpuCalendar.zone).toInstant())
        }.sortedBy { it.start }
        val merged = mutableListOf<ClassInterval>()
        for (interval in intervals) {
            val previous = merged.lastOrNull()
            if (previous != null && interval.start <= previous.end) {
                merged[merged.lastIndex] = previous.copy(end = maxOf(previous.end, interval.end))
            } else merged += interval
        }
        val active = merged.firstOrNull { now >= it.start && now < it.end }
        return ClassDndPlan(active != null, active?.end ?: merged.firstOrNull { it.start > now }?.start, merged)
    }

    private fun parseTime(value: String): LocalTime? = runCatching { LocalTime.parse(value) }.getOrNull()
    private fun hhmm(value: Int): LocalTime? = runCatching { LocalTime.of(value / 100, value % 100) }.getOrNull()
}
