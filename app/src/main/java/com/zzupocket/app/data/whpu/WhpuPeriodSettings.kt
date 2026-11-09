package com.zzupocket.app.data.whpu

import com.zzupocket.app.domain.Period
import com.zzupocket.app.domain.Session
import kotlinx.serialization.Serializable

@Serializable
data class WhpuPeriodTime(val start: Int, val end: Int)

object WhpuPeriodSettings {
    fun defaults(): List<WhpuPeriodTime> = WhpuCalendar.periods().map { WhpuPeriodTime(it.startTime, it.endTime) }

    fun error(times: List<WhpuPeriodTime>): String? {
        if (times.size != 11) return "请完整设置第 1—11 节。"
        times.forEachIndexed { index, time ->
            if (!validClock(time.start) || !validClock(time.end)) return "第 ${index + 1} 节时间无效。"
            if (time.end <= time.start) return "第 ${index + 1} 节下课时间须晚于上课时间。"
            if (index > 0 && time.start < times[index - 1].end) return "第 ${index + 1} 节与上一节时间重叠。"
        }
        return null
    }

    fun periods(times: List<WhpuPeriodTime>): List<Period> {
        require(error(times) == null) { error(times).orEmpty() }
        return WhpuCalendar.periods().mapIndexed { index, period ->
            period.copy(startTime = times[index].start, endTime = times[index].end)
        }
    }

    fun apply(sessions: List<Session>, periods: List<Period>): List<Session> {
        val byIndex = periods.associateBy { it.indexNo }
        return sessions.map { session ->
            session.copy(startTime = byIndex[session.startUnit]?.startText ?: session.startTime,
                endTime = byIndex[session.endUnit]?.endText ?: session.endTime)
        }
    }

    private fun validClock(value: Int) = value in 0..2359 && value % 100 in 0..59
}
