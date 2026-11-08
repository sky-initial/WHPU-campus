package com.zzupocket.app.dnd

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.classDndData by preferencesDataStore("class_dnd")
data class ClassDndSettings(val enabled: Boolean = false, val ruleId: String? = null)

class ClassDndStore(private val data: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.classDndData)
    private val enabledKey = booleanPreferencesKey("enabled")
    private val ruleKey = stringPreferencesKey("rule_id")
    val flow = data.data.map { ClassDndSettings(it[enabledKey] ?: false, it[ruleKey]) }
    suspend fun current() = flow.first()
    suspend fun save(enabled: Boolean, ruleId: String?) {
        data.edit { it[enabledKey] = enabled; if (ruleId == null) it.remove(ruleKey) else it[ruleKey] = ruleId }
    }
}
