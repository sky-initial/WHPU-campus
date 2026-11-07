package com.zzupocket.app.data.whpu

import org.junit.Assert.assertEquals
import org.junit.Test

class WhpuScheduleParserTest {
    @Test fun parsesSeparateRowsAndPractice() {
        val result = WhpuScheduleParser.parse("""
            {"kbList":[
              {"kcmc":"示例课程","kch":"demo","xqj":1,"jcs":"1-2","jcor":"9-10","zcd":"1-3周(单)","xsdm":"01"},
              {"kcmc":"示例课程","kch":"demo","xqj":"2","jcs":"3-4","zcd":"2周","xsdm":"02","cdmc":"实验室"}
            ],"sjkList":[{"kcmc":"示例实践","zcd":"4-5周"}],"unknown":true}
        """.trimIndent())
        assertEquals(2, result.courses.size)
        assertEquals(1, result.courses[0].startUnit)
        assertEquals(setOf(1, 3), result.courses[0].weeks)
        assertEquals("实验室", result.courses[1].room)
        assertEquals(setOf(4, 5), result.practice.single().weeks)
    }

    @Test fun emptyScheduleIsValid() {
        assertEquals(emptyList<WhpuCourse>(), WhpuScheduleParser.parse("""{"kbList":[]}""").courses)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingScheduleIsInvalid() { WhpuScheduleParser.parse("{}") }

    @Test(expected = IllegalArgumentException::class)
    fun unexpectedFieldTypeIsInvalid() {
        WhpuScheduleParser.parse("""{"kbList":[{"kcmc":[],"xqj":1,"jcs":"1","zcd":"1周"}]}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingCourseWeeksIsInvalid() {
        WhpuScheduleParser.parse("""{"kbList":[{"kcmc":"示例课程","xqj":1,"jcs":"1-2"}]}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun nullCourseWeeksIsInvalid() {
        WhpuScheduleParser.parse("""{"kbList":[{"kcmc":"示例课程","xqj":1,"jcs":"1-2","zcd":null}]}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankCourseWeeksIsInvalid() {
        WhpuScheduleParser.parse("""{"kbList":[{"kcmc":"示例课程","xqj":1,"jcs":"1-2","zcd":" "}]}""")
    }

    @Test fun practiceWithoutScheduledWeeksIsRetained() {
        val schedule = WhpuScheduleParser.parse("""{"kbList":[],"sjkList":[{"kcmc":"示例实践"}]}""")
        assertEquals("示例实践", schedule.practice.single().name)
        assertEquals(emptySet<Int>(), schedule.practice.single().weeks)
    }
}
