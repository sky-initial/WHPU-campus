package com.zzupocket.app.data.whpu

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Only fields needed for the grade list are persisted. No student profile or raw server row. */
@Serializable
data class WhpuGrade(
    val courseName: String,
    val courseCode: String?,
    val grade: String?,
    val credits: String?,
    val courseNature: String?,
    val examType: String?,
    val remark: String?,
    val voided: String?,
    val assessment: String?,
    val gradePoint: String? = null,
    val creditGradePoint: String? = null,
)

data class WhpuGradePage(val currentPage: Int, val totalPages: Int, val totalRecords: Int,
    val grades: List<WhpuGrade>)

data class WhpuGrades(val term: WhpuTerm, val account: WhpuAccountId, val grades: List<WhpuGrade>)
data class WhpuGradesSnapshot(val term: WhpuTerm, val grades: List<WhpuGrade>, val fetchedAt: Long)

object WhpuGradeParser {
    fun parse(body: String, term: WhpuTerm, studentNumber: WhpuAccountId): WhpuGradePage {
        val root = Json.parseToJsonElement(body).jsonObject
        val page = root.requiredInt("currentPage")
        val totalPages = root.requiredInt("totalPage")
        val totalRecords = root.requiredInt("totalResult")
        require(page >= 1 && totalPages >= 0 && totalRecords >= 0) { "Invalid grade pagination" }
        val rows = root["items"] as? JsonArray ?: error("Missing grade items")
        val grades = rows.map { element ->
            val row = element.jsonObject
            require(row.text("xh") == studentNumber.value) { "Grade account mismatch" }
            require(row.text("xnm") == term.year.toString() && row.text("xqm") == term.code) {
                "Grade term mismatch"
            }
            WhpuGrade(
                courseName = requireNotNull(row.text("kcmc")) { "Missing course name" },
                courseCode = row.text("kch"), grade = row.text("cj"), credits = row.text("xf"),
                courseNature = row.text("kcxzmc"), examType = row.text("ksxz"),
                remark = row.text("cjbz"), voided = row.text("cjsfzf"), assessment = row.text("khfsmc"),
                gradePoint = row.text("jd"), creditGradePoint = row.text("xfjd"),
            )
        }
        return WhpuGradePage(page, totalPages, totalRecords, grades)
    }

    private fun JsonObject.requiredInt(key: String): Int =
        requireNotNull(text(key)?.toIntOrNull()) { "Missing grade pagination: $key" }

    internal fun JsonObject.text(key: String): String? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        else -> error("Invalid grade field: $key")
    }
}
