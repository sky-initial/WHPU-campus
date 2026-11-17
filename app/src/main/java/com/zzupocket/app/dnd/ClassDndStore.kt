package com.zzupocket.app.dnd

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.classDndData by preferencesDataStore("class_dnd")
data class ClassDndSettings(
    val enabled: Boolean = false,
    val ruleId: String? = null,
    val reminderEnabled: Boolean = false,
    val hiddenClassStart: Long? = null,
    val useSystemAlarmClock: Boolean = false,
)

class ClassDndStore(private val data: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.classDndData)
    private val enabledKey = booleanPreferencesKey("enabled")
    private val ruleKey = stringPreferencesKey("rule_id")
    private val reminderKey = booleanPreferencesKey("reminder_enabled")
    private val hiddenKey = longPreferencesKey("hidden_class_start")
    private val alarmClockKey = booleanPreferencesKey("use_system_alarm_clock")
    val flow = data.data.map {
        ClassDndSettings(it[enabledKey] ?: false, it[ruleKey], it[reminderKey] ?: false,
            it[hiddenKey], it[alarmClockKey] ?: false)
    }
    suspend fun current() = flow.first()
    suspend fun save(enabled: Boolean, ruleId: String?) {
        data.edit { it[enabledKey] = enabled; if (ruleId == null) it.remove(ruleKey) else it[ruleKey] = ruleId }
    }
    suspend fun setReminder(enabled: Boolean) {
        data.edit { it[reminderKey] = enabled; it.remove(hiddenKey) }
    }
    suspend fun hideReminder(start: Long) {
        data.edit { it[hiddenKey] = start }
    }
    suspend fun setUseSystemAlarmClock(enabled: Boolean) {
        data.edit { it[alarmClockKey] = enabled }
    }
}
