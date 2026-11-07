package com.zzupocket.app.data.whpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhpuScheduleMapperTest {
    @Test fun demoRetainsWeeksAndExperiment() {
        val schedule = MockWhpuScheduleProvider().schedule(WhpuTerm.YEAR_2026_FIRST)
        val sessions = WhpuScheduleMapper.sessions(schedule, WhpuScheduleMapper.DEMO_SEMESTER_ID, WhpuScheduleMapper.DEMO_SOURCE)
        val experiment = sessions.filter { it.experiment }
        assertEquals((1..15 step 2).toList(), experiment.map { it.weekIndex })
        assertEquals("示例校区 B", experiment.first().campusText)
        assertTrue(sessions.filter { it.courseCode == "demo-code" }.all { it.weekIndex % 2 == 0 })
        assertEquals(3, WhpuScheduleMapper.courses(sessions).size)
        assertEquals(1, schedule.practice.size)
    }
}
