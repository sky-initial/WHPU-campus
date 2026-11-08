package com.zzupocket.app.data.whpu

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WhpuExamParserTest {
    private val term = WhpuTerm(2025, "3")
    private fun row(extra: String = "") = """{"kcmc":"人工课程","kch":"SYN-01",
        "kssj":"2030-01-10(09:00-11:00)","cdmc":"人工考场","cdxqmc":"考试校区",
        "ksmc":"示例期末考试","ksfs":"笔试","cxbj":"否",
        "xh":"synthetic-student","xnm":"2025","xqm":"3"$extra}"""
    private fun page(rows: String = row(), count: Int = 1) =
        """{"currentPage":1,"totalPage":1,"totalResult":$count,"items":[$rows]}"""
    private fun parse(body: String) = WhpuExamParser.parse(body, term, WhpuAccountId("synthetic-student"))

    @Test fun usesExamFieldsAndKeepsOriginalTime() {
        val exam = parse(page(row(""", "sksj":"上课时间", "jxdd":"上课教室", "xqmc":"上课校区""""))).items.single()
        assertEquals("2030-01-10(09:00-11:00)", exam.timeText)
        assertEquals("人工考场", exam.location)
        assertEquals("考试校区", exam.campus)
        assertEquals("笔试", exam.mode)
        assertEquals("示例期末考试", exam.examName)
    }
    @Test fun unpublishedExamDoesNotFallBackToLessonFields() {
        val exam = parse(page(row(""", "sksj":"上课时间", "jxdd":"上课教室", "xqmc":"上课校区"""" )
            .replace("\"2030-01-10(09:00-11:00)\"", "null")
            .replace("\"人工考场\"", "\" \"").replace("\"考试校区\"", "null"))).items.single()
        assertNull(exam.timeText); assertNull(exam.location); assertNull(exam.campus)
    }
    @Test fun keepsPendingTimeAndRemarksWithoutGuessingStatus() {
        val exam = parse(page(row(""", "ksbz":"另行通知", "khfs":"考试"""" )
            .replace("2030-01-10(09:00-11:00)", "待定"))).items.single()
        assertEquals("待定", exam.timeText); assertEquals("另行通知", exam.remark)
        assertEquals("考试", exam.assessment)
    }
    @Test fun serializationExcludesStudentProfileAndServerIds() {
        val cache = Json.encodeToString(parse(page(row(""", "xm":"人工姓名", "xh_id":"synthetic-id", "sjbh":"synthetic-paper", "jsxx":"synthetic-teacher""""))).items)
        listOf("synthetic-student", "人工姓名", "synthetic-id", "synthetic-paper", "synthetic-teacher").forEach {
            assertFalse(cache.contains(it))
        }
    }
    @Test fun separateExamsForSameCourseArePreserved() {
        val result = parse(page(row() + "," + row().replace("示例期末考试", "示例补考"), 2))
        assertEquals(listOf("示例期末考试", "示例补考"), result.items.map { it.examName })
    }
    @Test fun wrongAccountOrTermAndMissingCourseFail() {
        listOf(page().replace("synthetic-student", "synthetic-other"), page().replace("2025", "2024"),
            page().replace("\"xqm\":\"3\"", "\"xqm\":\"12\""), page().replace("\"人工课程\"", "null"))
            .forEach { assertTrue(runCatching { parse(it) }.isFailure) }
    }
    @Test fun missingRootPaginationIsNotReplacedByRowTotal() {
        assertTrue(runCatching { parse("""{"currentPage":1,"items":[${row(""", "totalresult":1""")}]}""") }.isFailure)
    }
    @Test fun emptyResultRequiresItemsAndValidPagination() = runTest {
        assertTrue(collectWhpuAcademicPages { parse("""{"currentPage":"1","totalPage":"0","totalResult":"0","items":[]}""") }.isEmpty())
        assertTrue(runCatching { parse("""{"currentPage":1,"totalPage":0,"totalResult":0}""") }.isFailure)
        assertTrue(runCatching { collectWhpuAcademicPages { parse(page(count = 2)) } }.isFailure)
    }
}
