package com.zzupocket.app.dnd

import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import com.zzupocket.app.MainActivity
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.WeekSettings
import com.zzupocket.app.data.whpu.WhpuCalendar
import com.zzupocket.app.ui.nav.AppRoutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.format.DateTimeFormatter

class ClassDndController(
    private val context: Context,
    val store: ClassDndStore = ClassDndStore(context),
    private val alarmRequestBase: Int = 4300,
    private val loadPlan: suspend () -> ClassDndPlan = { loadLocalClassDndPlan() },
) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val countdown = ClassCountdownNotification(context)
    private val mutex = Mutex()
    private val conditionId = Uri.parse("condition://${context.packageName}/class-dnd")
    private val _status = MutableStateFlow("尚未开启")
    val status = _status.asStateFlow()
    fun hasPolicyAccess() = notifications.isNotificationPolicyAccessGranted
    fun hasExactAlarmAccess() = alarms.canScheduleExactAlarms()
    fun hasCountdownNotifications() = countdown.canPost()
    fun systemInterruptionFilter() = notifications.currentInterruptionFilter

    fun observe(scope: CoroutineScope) {
        AppGraph.db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(
            "semesters", "course_sessions", "periods", "kv_cache",
        ) {
            override fun onInvalidated(tables: Set<String>) { scope.launch { refresh() } }
        })
        scope.launch { store.flow.distinctUntilChanged().collect { refresh() } }
        scope.launch { AppGraph.uiPrefsStore.flow.collect { refresh() } }
    }

    suspend fun setEnabled(enabled: Boolean) = mutex.withLock {
        if (!enabled) {
            disableLocked("已关闭")
            reconcileLocked()
        } else {
            check(hasPolicyAccess() && hasExactAlarmAccess()) { "请先授予免打扰访问与准时调度权限。" }
            val old = store.current().ruleId
            if (old != null) notifications.removeAutomaticZenRule(old)
            @Suppress("DEPRECATION")
            val rule = AutomaticZenRule("WHPU 上课免打扰", null,
                ComponentName(context, MainActivity::class.java), conditionId,
                ZenPolicy.Builder().allowAlarms(true).build(), NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
            val id = notifications.addAutomaticZenRule(rule)
            store.save(true, id)
            try { reconcileLocked() } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                disableLocked("开启失败，请检查权限后重试。")
                throw e
            }
        }
    }

    suspend fun setReminderEnabled(enabled: Boolean) {
        store.setReminder(enabled)
        refresh()
    }

    suspend fun setUseSystemAlarmClock(enabled: Boolean) {
        store.setUseSystemAlarmClock(enabled)
        refresh()
    }

    suspend fun hideReminder(start: Long) = mutex.withLock {
        val settings = store.current()
        if (settings.reminderEnabled && loadPlan().nextClass(Instant.now())?.start?.toEpochMilli() == start) {
            store.hideReminder(start)
            countdown.show(null, Instant.now())
        }
    }

    suspend fun refresh() = mutex.withLock {
        try {
            reconcileLocked()
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            // A failed schedule read must not leave this app's rule active indefinitely.
            runCatching { store.current().ruleId?.let { if (hasPolicyAccess()) setCondition(it, false) } }
            countdown.show(null, Instant.now())
            _status.value = "自动切换暂不可用，请重新打开本页检查。"
        }
    }

    private suspend fun reconcileLocked() {
        val settings = store.current()
        val ruleId = resolveRule(settings)
        val showCountdown = settings.reminderEnabled && countdown.canPost()
        if (ruleId == null && !showCountdown) {
            scheduleAlarms(null, null, null)
            countdown.show(null, Instant.now())
            return
        }
        val plan = loadPlan()
        val now = Instant.now()
        val lesson = plan.nextClass(now)
        val hidden = lesson != null && lesson.start.toEpochMilli() == settings.hiddenClassStart
        val reminderStart = lesson?.reminderStart()?.takeIf { showCountdown && !hidden && it > now }
        val end = lesson?.end
        val transition = if (lesson == null) plan.nextBoundary else lesson.start.takeIf { it > now }
        scheduleAlarms(transition, end, reminderStart, settings.useSystemAlarmClock)

        if (ruleId != null) {
            val active = lesson?.let { now >= it.start } ?: plan.active
            setCondition(ruleId, active)
            val boundary = (if (active) end ?: plan.nextBoundary else transition)?.atZone(WhpuCalendar.zone)
                ?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            _status.value = when {
                active -> "当前为上课时段，预计 $boundary 结束（北京时间）。"
                boundary != null -> "等待下一次上课：$boundary（北京时间）。"
                else -> "没有可调度的课程，请确认真实课表和第一周日期。"
            }
        }
        countdown.show(lesson?.countdownAt(now)?.takeIf { showCountdown && !hidden }, now)
    }

    private suspend fun resolveRule(settings: ClassDndSettings): String? {
        if (!settings.enabled) {
            if (settings.ruleId != null && hasPolicyAccess()) {
                if (notifications.getAutomaticZenRule(settings.ruleId) == null) store.save(false, null)
                else setCondition(settings.ruleId, false)
            }
            _status.value = "已关闭"
            return null
        }
        if (!hasPolicyAccess() || !hasExactAlarmAccess()) {
            disableLocked("权限不足，已暂停；授权后请重新开启。")
            return null
        }
        val id = settings.ruleId
        val rule = id?.let(notifications::getAutomaticZenRule)
        if (rule == null || !rule.isEnabled) {
            store.save(false, if (rule == null) null else id)
            _status.value = "系统中的上课规则已关闭或删除。"
            return null
        }
        return id
    }

    private suspend fun disableLocked(message: String) {
        val id = store.current().ruleId
        store.save(false, id)
        if (id != null && hasPolicyAccess()) {
            // Removing our rule also ends it; API 31 throws when setting a deleted rule's condition.
            notifications.removeAutomaticZenRule(id)
            store.save(false, null)
        }
        _status.value = message
    }

    private fun setCondition(id: String, active: Boolean) {
        notifications.setAutomaticZenRuleState(id, Condition(conditionId, "上课免打扰",
            if (active) Condition.STATE_TRUE else Condition.STATE_FALSE))
    }

    private fun alarm(request: Int): PendingIntent = PendingIntent.getBroadcast(context, request,
        Intent(context, ClassDndReceiver::class.java).setAction("${context.packageName}.CLASS_DND_BOUNDARY"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun scheduleAlarms(start: Instant?, end: Instant?, reminder: Instant?, alarmClock: Boolean = false) {
        val exact = hasExactAlarmAccess()
        scheduleAlarm(0, start, exact, alarmClock)
        scheduleAlarm(1, start?.plusSeconds(60), false)
        // Arm the end before the start broadcast. The inexact alarm survives exact-access revocation.
        scheduleAlarm(2, end, exact, alarmClock)
        scheduleAlarm(3, end?.plusSeconds(60), false)
        scheduleAlarm(4, reminder, exact, alarmClock)
    }

    private fun scheduleAlarm(slot: Int, time: Instant?, exact: Boolean, alarmClock: Boolean = false) {
        val operation = alarm(alarmRequestBase + slot)
        if (time == null) {
            alarms.cancel(operation)
            return
        }
        if (exact) {
            try {
                if (alarmClock) {
                    val show = PendingIntent.getActivity(context, alarmRequestBase, Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(AppRoutes.EXTRA_ROUTE, AppRoutes.CLASS_DND), PendingIntent.FLAG_IMMUTABLE)
                    alarms.setAlarmClock(AlarmManager.AlarmClockInfo(time.toEpochMilli(), show), operation)
                } else {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time.toEpochMilli(), operation)
                }
                return
            } catch (_: SecurityException) {
                // Access may have changed since scheduleAlarms checked it.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time.toEpochMilli(), operation)
    }
}

internal suspend fun loadLocalClassDndPlan(now: Instant = Instant.now()): ClassDndPlan = AppGraph.db.withTransaction {
    val repo = AppGraph.scheduleRepository
    val semester = repo.currentSemester()
    ClassDndPlanner.plan(semester?.id,
        semester?.let { AppGraph.uiPrefsStore.weekSettings(it.id) } ?: WeekSettings(),
        semester?.let { repo.sessionsOfSemester(it.id) }.orEmpty(),
        semester?.let { repo.periodsForSemester(it.id) }.orEmpty(), now)
}
