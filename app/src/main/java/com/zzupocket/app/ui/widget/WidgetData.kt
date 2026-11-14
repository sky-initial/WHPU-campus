package com.zzupocket.app.ui.widget

import androidx.room.withTransaction
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.data.repo.ElectricityRepository
import com.zzupocket.app.domain.EnergySample
import com.zzupocket.app.domain.EnergyStats
import com.zzupocket.app.data.whpu.totals
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime

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

/** 「电量与校园卡」组件要用的数据 */
data class WidgetBalanceBundle(
    val roomLabel: String,
    val lightKwh: Double?,
    val acKwh: Double?,
    /** 近 7 天日均用量（0 = 还没有样本） */
    val lightAvgDaily: Double,
    val acAvgDaily: Double,
    val lightAt: Long?,
    val acAt: Long?,
    val cardYuan: Double?,
    val cardAt: Long?,
    /** 校园卡今日消费；null = 本地还没有流水（从没同步过） */
    val cardTodaySpend: Double?,
    /** 校园卡本周消费与笔数；null = 本地还没有流水 */
    val cardWeekSpend: Double?,
    val cardWeekCount: Int?,
    val nowMillis: Long,
    /** 只有一路表计（亿达学生公寓那种整间一个表）→ 左半只显示一个「电量」块 */
    val singleMeter: Boolean = false,
) {
    fun plan(): WidgetBalancePlan = WidgetBalance.build(
        roomLabel = roomLabel,
        lightKwh = lightKwh,
        acKwh = acKwh,
        lightAvgDaily = lightAvgDaily,
        acAvgDaily = acAvgDaily,
        lightAt = lightAt,
        acAt = acAt,
        cardYuan = cardYuan,
        cardAt = cardAt,
        cardTodaySpend = cardTodaySpend,
        cardWeekSpend = cardWeekSpend,
        cardWeekCount = cardWeekCount,
        nowMillis = nowMillis,
        singleMeter = singleMeter,
    )
}

/**
 * 小组件的取数。
 *
 * 两条铁律（沿用上一版组件的口径，别"顺手优化"掉）：
 *
 *  1. **只读本地数据**（课表、电量读数、校园卡流水都在 Room；校园卡余额在上次查到的缓存里）。
 *     小组件里不打任何网络请求 —— 桌面是系统在后台唤醒我们去画的，在那里发请求
 *     等于让用户莫名其妙地耗电，而且网络一慢组件就白屏。拉数据交给 App 打开时
 *     （`Widgets.refreshAll`）和已有的定时任务，组件只负责把库里已有的东西画出来。
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

    /**
     * 电量、可用天数与校园卡余额/消费。
     *
     * 全部来自本地：
     *  · 电表余额 = Room 里最近一条读数；
     *  · 「可用约 N 天」= 余额 ÷ 近 7 天日均，日均由本机采样算（和电费页同一套 `EnergyStats`）；
     *  · 校园卡余额 = 上次成功查询时的缓存（当前 WHPU 账号/卡快照）；
     *  · 校园卡今日/本周消费 = 本地流水里算。
     * 这里**不发任何请求**。
     */
    suspend fun loadBalance(prefs: UiPrefs): WidgetBalanceBundle {
        val repo = AppGraph.electricityRepository
        val nowMillis = System.currentTimeMillis()
        val cardCache = AppGraph.whpuCardRepository.current()
        val card = cardCache?.selectedCard?.let {
            com.zzupocket.app.data.prefs.CachedCardBalance(it.balanceFen / 100.0, cardCache.fetchedAt)
        }
        val cardTotals = cardCache?.totals()?.let {
            CardTotals(it.todaySpendFen / 100.0, it.weekSpendFen / 100.0, it.weekCount)
        }

        val (lightRoom, acRoom) = runCatching { repo.effectiveRooms() }.getOrDefault("" to "")
        if (lightRoom.isBlank() && acRoom.isBlank()) {
            return WidgetBalanceBundle(
                roomLabel = (prefs.lightRoomLabel ?: prefs.acRoomLabel).orEmpty(),
                lightKwh = null, acKwh = null,
                lightAvgDaily = 0.0, acAvgDaily = 0.0,
                lightAt = null, acAt = null,
                cardYuan = card?.yuan, cardAt = card?.at,
                cardTodaySpend = cardTotals?.today, cardWeekSpend = cardTotals?.week,
                cardWeekCount = cardTotals?.weekCount,
                nowMillis = nowMillis,
            )
        }

        // 房间名：组件只有一行位置，所以先取照明那一路的名字（两路不同楼时电费页会标出来），
        // 其次用房表按**那一个**房间 ID 反查（得到 "柳园20号楼101" 这种）
        val label = (prefs.lightRoomLabel ?: prefs.acRoomLabel)?.takeIf { it.isNotBlank() }
            ?: runCatching { AppGraph.roomCatalog.labelForRoom(lightRoom.ifBlank { acRoom }) }
                .getOrNull().orEmpty()

        val light = if (lightRoom.isBlank()) null
        else runCatching { repo.latest(ElectricityRepository.ACCOUNT_TYPE_LIGHT, lightRoom) }.getOrNull()
        val ac = if (acRoom.isBlank()) null
        else runCatching { repo.latest(ElectricityRepository.ACCOUNT_TYPE_AC, acRoom) }.getOrNull()

        // 「可用约 N 天」的窗口与算法都在 EnergyStats 里（DAYS_LEFT_WINDOW_DAYS / recentDailyAverage），
        // App 电费页用的是同一个 —— 两处各写一份窗口正是"组件说 28 天、App 说 15.9 天"的来源。
        val since = EnergyStats.dayStart(
            com.zzupocket.app.data.whpu.WhpuCalendar.today().minusDays((EnergyStats.DAYS_LEFT_WINDOW_DAYS - 1).toLong()),
        )
        val lightAvg = if (lightRoom.isBlank()) 0.0 else runCatching {
            EnergyStats.recentDailyAverage(
                repo.readingsSince(ElectricityRepository.ACCOUNT_TYPE_LIGHT, lightRoom, since)
                    .map { EnergySample(it.ts, it.kwh) },
            )
        }.getOrDefault(0.0)
        val acAvg = if (acRoom.isBlank()) 0.0 else runCatching {
            EnergyStats.recentDailyAverage(
                repo.readingsSince(ElectricityRepository.ACCOUNT_TYPE_AC, acRoom, since)
                    .map { EnergySample(it.ts, it.kwh) },
            )
        }.getOrDefault(0.0)

        return WidgetBalanceBundle(
            roomLabel = label,
            lightKwh = light?.kwh,
            acKwh = ac?.kwh,
            lightAvgDaily = lightAvg,
            acAvgDaily = acAvg,
            lightAt = light?.ts,
            acAt = ac?.ts,
            cardYuan = card?.yuan,
            cardAt = card?.at,
            cardTodaySpend = cardTotals?.today,
            cardWeekSpend = cardTotals?.week,
            cardWeekCount = cardTotals?.weekCount,
            nowMillis = nowMillis,
            // 只有一路设了房间号 = 整间一个表那种楼 → 组件也只显示一个「电量」
            singleMeter = (lightRoom.isNotBlank() && acRoom.isBlank()) ||
                (lightRoom.isBlank() && acRoom.isNotBlank()),
        )
    }

    /** 校园卡的今日/本周消费（元）与本周笔数 */
    private data class CardTotals(val today: Double, val week: Double, val weekCount: Int)

}
