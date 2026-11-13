package com.zzupocket.app.data.whpu

import java.time.LocalDate

enum class WhpuQuery { GRADES, EXAMS }

/** 普通查询恢复独立选择，今日与组件入口优先定位当前课表学期。 */
fun initialWhpuQueryTerm(
    query: WhpuQuery,
    saved: WhpuTerm?,
    current: WhpuTerm?,
    today: LocalDate,
    preferCurrent: Boolean = false,
): WhpuTerm {
    if (preferCurrent && current != null) return current
    if (saved != null) return saved
    if (query == WhpuQuery.EXAMS && current != null) return current
    return WhpuTerm(if (today.monthValue >= 9) today.year else today.year - 1,
        if (today.monthValue in 2..8) "12" else "3")
}
