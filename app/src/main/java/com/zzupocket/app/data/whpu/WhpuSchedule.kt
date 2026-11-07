package com.zzupocket.app.data.whpu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class WhpuCourse(
    val name: String,
    val code: String?,
    val weekday: Int,
    val startUnit: Int,
    val endUnit: Int,
    val weeks: Set<Int>,
    val teacher: String?,
    val room: String?,
    val campus: String?,
    val teachingType: String?,
    val raw: JsonObject,
)

data class WhpuPracticeCourse(val name: String?, val weeks: Set<Int>, val raw: JsonObject)
data class WhpuSchedule(val courses: List<WhpuCourse>, val practice: List<WhpuPracticeCourse>)

object WhpuScheduleParser {
    private val periods = Regex("^(?:第)?\\s*(\\d+)\\s*(?:[-~－—]\\s*(\\d+))?\\s*节?$")

    fun parse(body: String): WhpuSchedule {
        val root = Json.parseToJsonElement(body).jsonObject
        val courses = root.array("kbList", required = true).map { element ->
            val row = element.jsonObject
            val name = requireNotNull(row.text("kcmc")?.takeIf { it.isNotBlank() })
            val weekday = requireNotNull(row.text("xqj")?.toIntOrNull())
            require(weekday in 1..7)
            val match = requireNotNull(periods.matchEntire(row.text("jcs").orEmpty().trim()))
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].ifEmpty { match.groupValues[1] }.toInt()
            require(start > 0 && end >= start)
            val weekText = requireNotNull(row.text("zcd")?.takeIf { it.isNotBlank() }) {
                "Missing course weeks"
            }
            WhpuCourse(name, row.text("kch"), weekday, start, end,
                WeekParser.parse(weekText), row.text("xm"), row.text("cdmc"),
                row.text("xqmc"), row.text("xsdm"), row)
        }
        val practice = root.array("sjkList").map { element ->
            val row = element.jsonObject
            WhpuPracticeCourse(row.text("kcmc"), WeekParser.parse(row.text("zcd").orEmpty()), row)
        }
        return WhpuSchedule(courses, practice)
    }

    private fun JsonObject.text(key: String): String? = when (val value = this[key]) {
        null -> null
        is JsonPrimitive -> value.contentOrNull
        else -> throw IllegalArgumentException("Invalid $key")
    }

    private fun JsonObject.array(key: String, required: Boolean = false): JsonArray {
        val value = this[key]
        if (value == null && !required) return JsonArray(emptyList())
        return value as? JsonArray ?: throw IllegalArgumentException("Invalid $key")
    }
}
