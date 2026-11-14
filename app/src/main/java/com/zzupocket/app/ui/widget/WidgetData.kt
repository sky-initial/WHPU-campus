package com.zzupocket.app.ui.widget

import androidx.room.withTransaction
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.data.whpu.totals
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * 小组件一次刷新要用到的全部课表数据。
 *
 * 打包成一个结构而不是让几个组件各查一遍库：它们看的是同一批数据，
 * 一次读到内存里，判断口径也不可能出现两边不一致。
 */
data class WidgetScheduleBundle(
    /** 库里到底有没有课表数据（区分"今天没课"和"还没同步过课表"） */
    val hasSchedule: Boolean,
    /** 今天属于第几教学周；假期/学期外为 null */
    val week: Int?,
    /** 今天 / 明天（用日期拼「今天 9.29 周二」这种列头） */
    val todayDate: LocalDate,
    val tomorrowDate: LocalDate,
    /** 今天的课 */
    val today: List<WidgetCourse>,
    /** 明天的课 */
    val tomorrow: List<WidgetCourse>,
    /** 现在几点（当天第几分钟） */
    val nowMin: Int,
    /** 下一个上下课时间点（毫秒时间戳），用来定"该变的时候变一次"的闹钟 */
    val nextBoundaryMillis: Long?,
) {
    /** 4×2 课表：左列今天、右列明天 */
    fun twoDayPlan(maxRows: Int): WidgetTwoDayPlan = WidgetSchedule.twoDayPlan(
        hasSchedule = hasSchedule,
        week = week,
        todayDate = todayDate,
        tomorrowDate = tomorrowDate,
        today = today,
        tomorrow = tomorrow,
        nowMin = nowMin,
        maxRows = maxRows,
    )

    /** 2×2：一节课大字（今天没了就顶明天的第一节） */
    fun nextClassPlan(previewRows: Int): WidgetNextClassPlan = WidgetSchedule.nextClassPlan(
        hasSchedule = hasSchedule,
        week = week,
        today = today,
        tomorrow = tomorrow,
        nowMin = nowMin,
        previewRows = previewRows,
    )
}

/** 当前 WHPU 账号和卡片的本地快照；电费目前通过官方页面查询。 */
data class WidgetBalanceBundle(
    val cardYuan: Double?,
    val cardAt: Long?,
    val cardTodaySpend: Double?,
    val cardWeekSpend: Double?,
    val cardWeekCount: Int?,
    val nowMillis: Long,
) {
    fun plan(): WidgetBalancePlan = WidgetBalance.build(
        roomLabel = "",
        lightKwh = null, acKwh = null,
        lightAvgDaily = 0.0, acAvgDaily = 0.0,
        lightAt = null, acAt = null,
        cardYuan = cardYuan, cardAt = cardAt,
        cardTodaySpend = cardTodaySpend, cardWeekSpend = cardWeekSpend,
        cardWeekCount = cardWeekCount, nowMillis = nowMillis,
        electricityAvailable = false,
    )
}

/**
 * 小组件的取数。
 *
 * 两条铁律（沿用上一版组件的口径，别"顺手优化"掉）：
 *
 *  1. **只读本地数据**（课表、考试、当前 WHPU 账号/卡的缓存）。
 *     小组件里不打任何网络请求 —— 桌面是系统在后台唤醒我们去画的，在那里发请求
 *     等于让用户莫名其妙地耗电，而且网络一慢组件就白屏。查询由 App 内的手动刷新完成，组件只展示已保存的数据。
 *  2. 判"第几周、今天有哪些课"全部走 [com.zzupocket.app.data.repo.ScheduleRepository]，
 *     也就是和 App 课表页同一套口径（优先按真实日期匹配，学校调休自动正确）。
 */
object WidgetData {

    /** 读一次界面偏好（主题、深浅色、课程配色、房间名） */
    suspend fun prefs(): UiPrefs = runCatching { AppGraph.uiPrefsStore.flow.first() }
        .getOrDefault(UiPrefs())

    suspend fun loadSchedule(): WidgetScheduleBundle {
        val repo = AppGraph.scheduleRepository
        repo.ensureDemoTimetable()
        return AppGraph.db.withTransaction {
            val prefsStore = AppGraph.uiPrefsStore
            val now = java.time.Instant.now().atZone(com.zzupocket.app.data.whpu.WhpuCalendar.zone)
            val today = now.toLocalDate()
            val tomorrow = today.plusDays(1)
            val nowMin = now.hour * 60 + now.minute
            val nowMillis = now.toInstant().toEpochMilli()
            val exams = AppGraph.whpuExamRepository.cachedCurrent()

            // 本科 + 研究生统一口径（研究生账号没有学期行，也照样能取到今天/明天的课）
            val hasSchedule = com.zzupocket.app.data.repo.TodayTimetable
                .hasAnySchedule(repo, prefsStore)
            val periods = repo.currentSemester()?.let { repo.periodsForSemester(it.id) } ?: repo.periods()
            val week = com.zzupocket.app.data.repo.TodayTimetable.currentWeekAt(repo, prefsStore, today)

            val todayCourses = com.zzupocket.app.data.repo.TodayTimetable
                .sessionsOn(repo, prefsStore, today)
                .mapNotNull { WidgetSchedule.toCourse(it, periods) }

            val tomorrowCourses = com.zzupocket.app.data.repo.TodayTimetable
                .sessionsOn(repo, prefsStore, tomorrow)
                .mapNotNull { WidgetSchedule.toCourse(it, periods) }

            val todayAgenda = todayCourses + exams.on(today).map(WidgetSchedule::toExam)
            val tomorrowAgenda = tomorrowCourses + exams.on(tomorrow).map(WidgetSchedule::toExam)
            WidgetScheduleBundle(
                hasSchedule = hasSchedule,
                week = week,
                todayDate = today,
                tomorrowDate = tomorrow,
                today = todayAgenda,
                tomorrow = tomorrowAgenda,
                nowMin = nowMin,
                nextBoundaryMillis = WidgetSchedule.nextBoundaryMillis(todayAgenda, nowMin, nowMillis),
            )
        }
    }

    /** 只读当前 WHPU 账号/卡缓存，不读取原项目的电费房间和采样。 */
    suspend fun loadBalance(): WidgetBalanceBundle {
        val nowMillis = System.currentTimeMillis()
        val cache = AppGraph.whpuCardRepository.current()
        val totals = cache?.totals()
        return WidgetBalanceBundle(
            cardYuan = cache?.selectedCard?.balanceFen?.div(100.0),
            cardAt = cache?.selectedCard?.let { cache.fetchedAt },
            cardTodaySpend = totals?.todaySpendFen?.div(100.0),
            cardWeekSpend = totals?.weekSpendFen?.div(100.0),
            cardWeekCount = totals?.weekCount,
            nowMillis = nowMillis,
        )
    }
}
