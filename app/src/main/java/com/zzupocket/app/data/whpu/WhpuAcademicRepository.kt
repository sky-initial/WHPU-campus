package com.zzupocket.app.data.whpu

import com.zzupocket.app.data.repo.ScheduleRepository
import com.zzupocket.app.domain.Course
import com.zzupocket.app.domain.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WhpuAcademicRepository(
    private val provider: WhpuScheduleProvider,
    private val save: suspend (String, Int, List<Session>, List<Course>, String) -> Unit,
) {
    constructor(provider: WhpuScheduleProvider, repository: ScheduleRepository) : this(
        provider,
        { source, semesterId, sessions, courses, practice ->
            repository.saveTimetable(source, semesterId, sessions, courses, practiceJson = practice)
            Unit
        },
    )

    /** Fetch completely before replacing the cached timetable. */
    suspend fun refresh(term: WhpuTerm, semesterId: Int): WhpuSchedule = withContext(Dispatchers.IO) {
        require(semesterId != WhpuScheduleMapper.DEMO_SEMESTER_ID)
        val schedule = provider.schedule(term)
        val sessions = WhpuScheduleMapper.sessions(schedule, semesterId, SOURCE)
        val practice = kotlinx.serialization.json.JsonArray(schedule.practice.map { it.raw }).toString()
        save(SOURCE, semesterId, sessions, WhpuScheduleMapper.courses(sessions), practice)
        schedule
    }

    companion object {
        const val SOURCE = "whpu"
    }
}
