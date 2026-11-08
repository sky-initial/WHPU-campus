package com.zzupocket.app.data.whpu

import com.zzupocket.app.data.whpu.WhpuGradeParser.text
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class WhpuExam(
    val courseName: String,
    val courseCode: String?,
    val timeText: String?,
    val location: String?,
    val campus: String?,
    val examName: String?,
    val remark: String?,
    val retake: String?,
    val assessment: String?,
    val mode: String?,
)

data class WhpuExams(val term: WhpuTerm, val account: WhpuAccountId, val exams: List<WhpuExam>)
data class WhpuExamsSnapshot(val term: WhpuTerm, val exams: List<WhpuExam>, val fetchedAt: Long)

object WhpuExamParser {
    fun parse(body: String, term: WhpuTerm, student: WhpuAccountId): WhpuAcademicPage<WhpuExam> {
        val root = Json.parseToJsonElement(body).jsonObject
        val page = root.requiredInt("currentPage")
        val pages = root.requiredInt("totalPage")
        val count = root.requiredInt("totalResult")
        require(page >= 1 && pages >= 0 && count >= 0) { "Invalid exam pagination" }
        val items = root["items"] as? JsonArray ?: error("Missing exam items")
        val exams = items.map { element ->
            val row = element.jsonObject
            require(row.text("xh") == student.value) { "Exam account mismatch" }
            require(row.text("xnm") == term.year.toString() && row.text("xqm") == term.code) { "Exam term mismatch" }
            WhpuExam(
                courseName = requireNotNull(row.text("kcmc")) { "Missing exam course name" },
                courseCode = row.text("kch"), timeText = row.text("kssj"), location = row.text("cdmc"),
                campus = row.text("cdxqmc"), examName = row.text("ksmc"), remark = row.text("ksbz"),
                retake = row.text("cxbj"), assessment = row.text("khfs"), mode = row.text("ksfs"),
            )
        }
        return WhpuAcademicPage(page, pages, count, exams)
    }

    private fun JsonObject.requiredInt(key: String): Int =
        requireNotNull(text(key)?.toIntOrNull()) { "Missing exam pagination: $key" }
}
