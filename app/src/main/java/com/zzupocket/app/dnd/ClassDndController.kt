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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

class ClassDndController(
    private val context: Context,
    val store: ClassDndStore = ClassDndStore(context),
    private val alarmRequestBase: Int = 4300,
    private val loadPlan: suspend () -> ClassDndPlan = { loadLocalClassDndPlan() },
) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()
    private val conditionId = Uri.parse("condition://${context.packageName}/class-dnd")
    private val _status = MutableStateFlow("尚未开启")
    val status = _status.asStateFlow()
    fun hasPolicyAccess() = notifications.isNotificationPolicyAccessGranted
    fun hasExactAlarmAccess() = alarms.canScheduleExactAlarms()

    fun observe(scope: CoroutineScope) {
        AppGraph.db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(
            "semesters", "course_sessions", "periods", "kv_cache",
        ) {
            override fun onInvalidated(tables: Set<String>) { scope.launch { refresh() } }
        })
        scope.launch { store.flow.collect { refresh() } }
        scope.launch { AppGraph.uiPrefsStore.flow.collect { refresh() } }
    }

    suspend fun setEnabled(enabled: Boolean) = mutex.withLock {
        if (!enabled) {
            disableLocked("已关闭")
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
            try { reconcileLocked() } catch (e: Exception) {
                disableLocked("开启失败，请检查权限后重试。")
                throw e
            }
        }
    }

    suspend fun refresh() = mutex.withLock {
        try {
            reconcileLocked()
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            // A failed schedule read must not leave this app's rule active indefinitely.
            runCatching { store.current().ruleId?.let { if (hasPolicyAccess()) setCondition(it, false) } }
            _status.value = "自动切换暂不可用，请重新打开本页检查。"
        }
    }

    private suspend fun reconcileLocked() {
        val settings = store.current()
        if (!settings.enabled) {
            cancelAlarms()
            if (settings.ruleId != null && hasPolicyAccess()) {
                setCondition(settings.ruleId, false)
            }
            _status.value = "已关闭"
            return
        }
        if (!hasPolicyAccess() || !hasExactAlarmAccess()) {
            disableLocked("权限不足，已暂停；授权后请重新开启。")
            return
        }
        val id = settings.ruleId
        val rule = id?.let(notifications::getAutomaticZenRule)
        if (rule == null || !rule.isEnabled) {
            cancelAlarms()
            store.save(false, id)
            _status.value = "系统中的上课规则已关闭或删除。"
            return
        }
        val plan = loadPlan()
        scheduleBoundary(plan.nextBoundary)
        setCondition(id, plan.active)
        _status.value = when {
            plan.active -> "当前为上课时段；系统中的手动设置优先。"
            plan.nextBoundary != null -> "等待下一次上课，按北京时间自动切换。"
            else -> "没有可调度的课程，请确认真实课表和第一周日期。"
        }
    }

    private suspend fun disableLocked(message: String) {
        val id = store.current().ruleId
        store.save(false, id)
        cancelAlarms()
        if (id != null && hasPolicyAccess()) {
            setCondition(id, false)
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

    private fun cancelAlarms() { alarms.cancel(alarm(alarmRequestBase)); alarms.cancel(alarm(alarmRequestBase + 1)) }
    private fun scheduleBoundary(next: Instant?) {
        cancelAlarms()
        if (next == null) return
        // The inexact backup can still end our rule if exact-alarm permission is later revoked.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli() + 60_000, alarm(alarmRequestBase + 1))
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), alarm(alarmRequestBase))
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
