package com.zzupocket.app.data.whpu

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class WhpuExamTime(val date: LocalDate, val start: LocalTime, val end: LocalTime) {
    fun status(now: Instant): String {
        val current = now.atZone(WhpuCalendar.zone).toLocalDateTime()
        return when {
            current < date.atTime(start) -> "未开始"
            current < date.atTime(end) -> "进行中"
            else -> "已结束"
        }
    }
}

data class TimedWhpuExam(val exam: WhpuExam, val time: WhpuExamTime)

/** 当前课表学期的缓存；null 快照与学校返回空列表分别表示未同步和暂无安排。 */
data class CurrentWhpuExams(val term: WhpuTerm?, val snapshot: WhpuExamsSnapshot?) {
    fun on(date: LocalDate): List<TimedWhpuExam> = snapshot?.exams.orEmpty()
        .mapNotNull { exam -> WhpuExamAgenda.parseTime(exam.timeText)?.let { TimedWhpuExam(exam, it) } }
        .filter { it.time.date == date }
        .sortedBy { it.time.start }

    val unplacedCount: Int get() = snapshot?.exams.orEmpty().count { WhpuExamAgenda.parseTime(it.timeText) == null }
}

object WhpuExamAgenda {
    // 学校已核对的 kssj 格式；无法识别的原文仍保留在考试列表，不推测日期或时长。
    private val pattern = Regex("""(\d{4}-\d{2}-\d{2})\s*[(（]\s*(\d{2}):(\d{2})\s*-\s*(\d{2}):(\d{2})\s*[)）]""")

    fun parseTime(text: String?): WhpuExamTime? {
        val match = pattern.matchEntire(text?.trim().orEmpty()) ?: return null
        return try {
            val date = LocalDate.parse(match.groupValues[1])
            val start = LocalTime.of(match.groupValues[2].toInt(), match.groupValues[3].toInt())
            val end = LocalTime.of(match.groupValues[4].toInt(), match.groupValues[5].toInt())
            if (end <= start) null else WhpuExamTime(date, start, end)
        } catch (_: DateTimeException) { null }
    }
}
