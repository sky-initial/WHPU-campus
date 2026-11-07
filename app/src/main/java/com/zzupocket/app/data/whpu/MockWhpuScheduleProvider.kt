package com.zzupocket.app.data.whpu

import com.zzupocket.app.domain.Course
import com.zzupocket.app.domain.Session

/** Entirely fictional data for demonstrating the timetable. */
class MockWhpuScheduleProvider : WhpuScheduleProvider {
    override fun schedule(term: WhpuTerm): WhpuSchedule = WhpuScheduleParser.parse(
        """{"kbList":[
            {"kcmc":"示例高等数学","kch":"demo-math","xqj":"1","jcs":"1-2","zcd":"1-16周","xm":"示例教师","cdmc":"示例教学楼 101","xqmc":"示例校区 A","xsdm":"01"},
            {"kcmc":"示例实验","kch":"demo-lab","xqj":"3","jcs":"3-4","zcd":"1-15周(单)","xm":"示例教师","cdmc":"示例实验室","xqmc":"示例校区 B","xsdm":"02"},
            {"kcmc":"示例程序设计","kch":"demo-code","xqj":"5","jcs":"5-6","zcd":"2-16周(双)","xm":"示例教师","cdmc":"示例机房","xqmc":"示例校区 A","xsdm":"04"}
        ],"sjkList":[{"kcmc":"示例实践安排","zcd":"10-12周"}]}"""
    )
}

object WhpuScheduleMapper {
    const val DEMO_SEMESTER_ID = -20_001
    const val DEMO_SOURCE = "whpu-demo"

    fun sessions(schedule: WhpuSchedule, semesterId: Int, source: String): List<Session> =
        schedule.courses.flatMapIndexed { index, course ->
            course.weeks.map { week ->
                Session(
                    id = "$source:$semesterId:$index:$week",
                    semesterId = semesterId,
                    source = source,
                    courseCode = course.code?.takeIf { it.isNotBlank() } ?: "row-$index",
                    courseName = course.name,
                    weekIndex = week,
                    weekday = course.weekday,
                    startUnit = course.startUnit,
                    endUnit = course.endUnit,
                    date = null,
                    startTime = null,
                    endTime = null,
                    teacherName = course.teacher,
                    roomText = course.room,
                    campusText = course.campus,
                    color = null,
                    experiment = course.teachingType == "02",
                )
            }
        }

    fun courses(sessions: List<Session>): List<Course> = sessions.groupBy { it.courseCode }.map { (code, rows) ->
        val first = rows.first()
        Course(
            key = "${first.semesterId}:$code",
            semesterId = first.semesterId,
            code = code,
            nameZh = first.courseName,
            credits = 0.0,
            openDepartment = null,
            teachers = rows.mapNotNull { it.teacherName }.distinct(),
            sessions = rows,
        )
    }
}
