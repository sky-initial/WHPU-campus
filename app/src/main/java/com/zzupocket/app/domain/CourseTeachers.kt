package com.zzupocket.app.domain

/** A timetable tap identifies an exact lesson; an overview uses the displayed teaching week. */
fun Course.teachingSessions(weekIndex: Int, selectedSession: Session? = null): List<Session> =
    if (selectedSession != null) listOf(selectedSession) else sessions.filter { it.weekIndex == weekIndex }

fun Course.currentTeachers(weekIndex: Int, selectedSession: Session? = null): List<String> =
    teachingSessions(weekIndex, selectedSession).mapNotNull { it.teacherName?.takeIf(String::isNotBlank) }.distinct()
