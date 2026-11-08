package com.zzupocket.app.data.whpu

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import com.zzupocket.app.domain.currentTeachers

/** Regression against the minimized, pseudonymized school response documented beside the fixture. */
class WhpuObservedScheduleTest {
    private fun schedule() = WhpuScheduleParser.parse(fixture())

    @Test fun teacherSummaryFollowsTheDisplayedWeekInsteadOfTheWholeTerm() {
        val course = WhpuScheduleMapper.courses(WhpuScheduleMapper.sessions(schedule(), 123, "whpu"))
            .single { it.code == "课程1" }
        assertEquals(listOf("教师2"), course.currentTeachers(9))
        assertEquals(listOf("教师3"), course.currentTeachers(14))
        assertTrue(course.currentTeachers(1).isEmpty())
        assertEquals(3, course.teachers.size)
    }

    @Test fun tappingOneLessonDoesNotShowOtherTeachersWhoTeachThatWeek() {
        val course = WhpuScheduleMapper.courses(WhpuScheduleMapper.sessions(schedule(), 123, "whpu"))
            .single { it.code == "课程1" }
        assertEquals(setOf("教师1", "教师2"), course.currentTeachers(8).toSet())
        val monday = course.sessions.single { it.weekIndex == 8 && it.weekday == 1 }
        val friday = course.sessions.single { it.weekIndex == 8 && it.weekday == 5 }
        assertEquals(listOf("教师1"), course.currentTeachers(8, monday))
        assertEquals(listOf("教师2"), course.currentTeachers(8, friday))
    }

    @Test fun alternatingLabTeachersFollowTheirActualWeeks() {
        val course = WhpuScheduleMapper.courses(WhpuScheduleMapper.sessions(schedule(), 123, "whpu"))
            .single { it.code == "课程6" }
        assertEquals(listOf("教师9"), course.currentTeachers(6))
        assertEquals(listOf("教师10"), course.currentTeachers(8))
        assertTrue(course.currentTeachers(9).isEmpty())
    }

    @Test fun parsesEveryCourseAndBothPracticeCategories() {
        val result = schedule()
        assertEquals(27, result.courses.size)
        assertEquals(3, result.practice.size)
        assertEquals(listOf(setOf(1, 2, 3), setOf(19), setOf(16)), result.practice.map { it.weeks })
        assertEquals("教师3,教师13,教师4,教师14,教师15", result.practice[0].teacher)
        assertEquals("教师1", result.practice[1].teacher)
        assertEquals("金银湖", result.practice[1].campus)
        // Course designs have no sfsjk flag but still belong to sjkList.
        assertFalse(result.practice[1].raw.containsKey("sfsjk"))
    }

    @Test fun mixedParityAppliesOnlyToItsOwnSegment() {
        val courses = schedule().courses
        assertEquals(setOf(4, 7, 9, 10), courses.single {
            it.code == "课程3" && it.weekday == 2
        }.weeks)
        assertEquals(setOf(5, 6, 7, 10, 12), courses.single {
            it.code == "课程2" && it.weekday == 4 && it.teacher == "教师4"
        }.weeks)
    }

    @Test fun experimentUsesActualPeriodsAndTypeCode() {
        val sessions = WhpuScheduleMapper.sessions(schedule(), 123, "whpu")
        val lab = sessions.filter { it.courseCode == "课程3" && it.experiment }
        assertEquals(listOf(9, 11, 13), lab.map { it.weekIndex })
        assertEquals(listOf(5 to 6, 5 to 7, 5 to 7), lab.map { it.startUnit to it.endUnit })
        // These rows say 面授讲课 in skfsmc, but xsdm=02 identifies them as experiments.
        val otherLab = sessions.filter { it.courseCode == "课程6" }
        assertEquals(6, otherLab.size)
        assertTrue(otherLab.all { it.experiment })
        assertEquals(7 to 8, otherLab.single { it.weekIndex == 6 }.let { it.startUnit to it.endUnit })
        assertEquals(5 to 8, otherLab.single { it.weekIndex == 14 }.let { it.startUnit to it.endUnit })
        assertTrue(sessions.filter { it.courseCode == "课程5" }.all { it.startUnit == 9 && it.endUnit == 11 })
    }

    @Test fun mappingRetainsTeacherAndRoomChangesWithinSameCourse() {
        val sessions = WhpuScheduleMapper.sessions(schedule(), 123, "whpu")
        val monday = sessions.filter { it.courseCode == "课程1" && it.weekday == 1 }
        assertEquals((4..17).toList(), monday.map { it.weekIndex })
        assertEquals("教师1", monday.single { it.weekIndex == 8 }.teacherName)
        assertEquals("教师2", monday.single { it.weekIndex == 9 }.teacherName)
        assertEquals("教师3", monday.single { it.weekIndex == 14 }.teacherName)
        val changingRoom = sessions.filter { it.courseCode == "课程5" }
        assertEquals("教室5", changingRoom.single { it.weekIndex == 4 }.roomText)
        assertEquals("教室6", changingRoom.single { it.weekIndex == 5 }.roomText)
        assertEquals("教室5", changingRoom.single { it.weekIndex == 14 }.roomText)
        assertEquals(6, WhpuScheduleMapper.courses(sessions).size)
        assertEquals(sessions.size, sessions.map { it.id }.toSet().size)
    }

    @Test fun repositoryPayloadRetainsPracticeFieldsForOfflineRead() = runTest {
        var savedPractice: String? = null
        val provider = object : WhpuScheduleProvider {
            override fun schedule(term: WhpuTerm) = schedule()
        }
        val repo = WhpuAcademicRepository(provider) { source, term, sessions, courses, practice ->
            assertEquals("whpu", source)
            assertEquals(123, term)
            assertEquals(6, courses.size)
            assertTrue(sessions.isNotEmpty())
            savedPractice = practice
        }
        val result = repo.refresh(WhpuTerm.YEAR_2026_FIRST, 123)
        val reread = WhpuScheduleParser.parse("{\"kbList\":[],\"sjkList\":$savedPractice}")
        assertEquals(result.practice, reread.practice)
        assertEquals(setOf(19), reread.practice[1].weeks)
        assertEquals("教师1", reread.practice[1].teacher)
    }

    @Test fun fixtureContainsOnlyReviewedFieldsAndAliases() {
        val root = Json.parseToJsonElement(fixture()).jsonObject
        assertEquals(setOf("kbList", "sjkList", "xsbjList"), root.keys)
        val allowed = setOf("kcmc", "kch", "jxb_id", "jcs", "jcor", "jc", "xqj", "zcd",
            "xm", "cdmc", "xqmc", "xsdm", "xslxbj", "skfsmc", "jxbsftkbj")
        schedule().courses.forEach { course ->
            assertEquals(allowed, course.raw.keys)
            assertTrue(course.name.matches(Regex("课程\\d+")))
            assertTrue(course.teacher!!.matches(Regex("教师\\d+")))
            assertTrue(course.room!!.matches(Regex("教室\\d+")))
            assertTrue(course.raw.getValue("jxb_id").jsonPrimitive.content.matches(Regex("教学班\\d+")))
        }
    }

    companion object {
        fun fixture(): String = requireNotNull(
            WhpuObservedScheduleTest::class.java.getResourceAsStream("/whpu/schedule-2026-first-redacted.json"),
        ).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
