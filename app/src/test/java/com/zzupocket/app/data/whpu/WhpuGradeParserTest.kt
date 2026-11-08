package com.zzupocket.app.data.whpu

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WhpuGradeParserTest {
    private val term = WhpuTerm(2025, "3")
    private val identity = WhpuAccountId("synthetic-student")
    private fun row(extra: String = "") = """{"kcmc":"示例课程","kch":"SYN-01","cj":"良好","xf":"2.5",
        "ksxz":"补考一","kcxzmc":"必修课","cjsfzf":"否","xh":"synthetic-student","xnm":"2025","xqm":"3"$extra}"""
    private fun page(rows: String = row(), count: Int = 1) =
        """{"currentPage":1,"totalPage":1,"totalResult":$count,"items":[$rows]}"""
    private fun parse(body: String) = WhpuGradeParser.parse(body, term, identity)

    @Test fun preservesTextGradeCreditsAndExamType() {
        val grade = parse(page()).grades.single()
        assertEquals("良好", grade.grade)
        assertEquals("2.5", grade.credits)
        assertEquals("补考一", grade.examType)
    }
    @Test fun preservesSchoolGradePointsWithoutCalculatingOrRounding() {
        val grade = parse(page(row(",\"jd\":\"3.50\",\"xfjd\":\"8.75\""))).grades.single()
        assertEquals("3.50", grade.gradePoint)
        assertEquals("8.75", grade.creditGradePoint)
        val zero = parse(page(row(",\"jd\":\"0.00\",\"xfjd\":0"))).grades.single()
        assertEquals("0.00", zero.gradePoint)
        assertEquals("0", zero.creditGradePoint)
    }
    @Test fun existingCacheWithoutGradePointFieldsRemainsReadable() {
        val old = """[{"courseName":"人工课程","courseCode":null,"grade":"优秀","credits":"2",
            "courseNature":null,"examType":null,"remark":null,"voided":null,"assessment":null}]"""
        val grade = Json.decodeFromString<List<WhpuGrade>>(old).single()
        assertNull(grade.gradePoint)
        assertNull(grade.creditGradePoint)
    }
    @Test fun blankAndNullGradesAreNotZero() {
        assertNull(parse(page(row().replace("\"良好\"", "null"))).grades.single().grade)
        assertNull(parse(page(row().replace("\"良好\"", "\" \""))).grades.single().grade)
        assertEquals("0", parse(page(row().replace("\"良好\"", "0"))).grades.single().grade)
    }
    @Test fun cacheSerializationDropsStudentProfileAndServerIdentifiers() {
        val result = parse(page(row(",\"xm\":\"人工姓名\",\"xh_id\":\"synthetic-encrypted\",\"key\":\"synthetic-key\"")))
        val cache = Json.encodeToString(result.grades)
        listOf("synthetic-student", "人工姓名", "synthetic-encrypted", "synthetic-key", "xh_id").forEach {
            assertFalse(cache.contains(it))
        }
    }
    @Test fun retakesAreSeparateRowsEvenForTheSameCourse() {
        val result = parse(page(row() + "," + row().replace("补考一", "正常考试"), 2))
        assertEquals(2, result.grades.size)
        assertEquals(listOf("补考一", "正常考试"), result.grades.map { it.examType })
    }
    @Test fun missingPaginationIsNotAnEmptySuccessfulResult() {
        assertTrue(runCatching { parse("""{"items":[]}""") }.isFailure)
    }
    @Test fun missingItemsAndMalformedRowFail() {
        assertTrue(runCatching { parse("""{"currentPage":1,"totalPage":0,"totalResult":0}""") }.isFailure)
        assertTrue(runCatching { parse(page(row().replace("\"示例课程\"", "null"))) }.isFailure)
    }
    @Test fun differentAccountOrTermIsRejected() {
        listOf(page().replace("synthetic-student", "synthetic-other"),
            page().replace("2025", "2024"), page().replace("\"xqm\":\"3\"", "\"xqm\":\"12\""))
            .forEach { assertTrue(runCatching { parse(it) }.isFailure) }
    }
    @Test fun acceptsStringPaginationAndEmptyTerm() {
        val result = parse("""{"currentPage":"1","totalPage":"0","totalResult":"0","items":[]}""")
        assertTrue(result.grades.isEmpty())
    }
    @Test fun collectsAllPagesInOrder() = runTest {
        val record = parse(page()).grades.single()
        val requested = mutableListOf<Int>()
        val result = collectWhpuGradePages { n -> requested += n; WhpuGradePage(n, 3, 3, listOf(record.copy(grade = "$n"))) }
        assertEquals(listOf(1, 2, 3), requested)
        assertEquals(listOf("1", "2", "3"), result.map { it.grade })
    }
    @Test fun laterPageFailureDoesNotReturnPartialResults() = runTest {
        val record = parse(page()).grades.single()
        assertTrue(runCatching { collectWhpuGradePages { n ->
            if (n == 2) throw WhpuAcademicException.Network(java.io.IOException("synthetic failure"))
            WhpuGradePage(1, 2, 2, listOf(record))
        } }.exceptionOrNull() is WhpuAcademicException.Network)
    }
    @Test fun repeatedPageChangingTotalsAndTruncationAreRejected() = runTest {
        val record = parse(page()).grades.single()
        for (mode in 0..2) {
            assertTrue(runCatching { collectWhpuGradePages { n ->
                when {
                    mode == 2 -> WhpuGradePage(1, 1, 2, listOf(record))
                    n == 1 -> WhpuGradePage(1, 2, 2, listOf(record))
                    mode == 0 -> WhpuGradePage(1, 2, 2, listOf(record))
                    else -> WhpuGradePage(2, 2, 3, listOf(record))
                }
            } }.isFailure)
        }
    }
    @Test fun emptyTermRequiresConsistentTotals() = runTest {
        assertTrue(collectWhpuGradePages { WhpuGradePage(1, 0, 0, emptyList()) }.isEmpty())
        assertTrue(runCatching { collectWhpuGradePages { WhpuGradePage(1, 2, 0, emptyList()) } }.isFailure)
    }
}
