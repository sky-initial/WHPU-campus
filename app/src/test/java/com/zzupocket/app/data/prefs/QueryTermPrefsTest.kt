package com.zzupocket.app.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zzupocket.app.core.WeekSettings
import com.zzupocket.app.data.whpu.WhpuQuery
import com.zzupocket.app.data.whpu.WhpuTerm
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QueryTermPrefsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun independentChoicesSurviveStoreRecreationWithoutChangingExistingPreferences() = runBlocking {
        val file = folder.newFile("query.preferences_pb")
        val grades = WhpuTerm(2028, "12")
        val exams = WhpuTerm(2029, "3")
        val semester = WhpuTerm(2030, "3").semesterId
        val week = WeekSettings(LocalDate.of(2030, 9, 2), confirmed = true)
        suspend fun withStore(block: suspend (UiPrefsStore) -> Unit) {
            val job = SupervisorJob()
            val store = UiPrefsStore(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file }))
            try { block(store) } finally { job.cancelAndJoin() }
        }
        withStore {
            assertNull(it.queryTerm(WhpuQuery.GRADES))
            assertNull(it.queryTerm(WhpuQuery.EXAMS))
            it.setDarkMode(DarkMode.DARK)
            it.saveWeekSettings(semester, week)
            it.saveQueryTerm(WhpuQuery.GRADES, grades)
            it.saveQueryTerm(WhpuQuery.EXAMS, exams)
            it.saveQueryTerm(WhpuQuery.GRADES, grades.copy(code = "3"))
        }
        withStore {
            assertEquals(grades.copy(code = "3"), it.queryTerm(WhpuQuery.GRADES))
            assertEquals(exams, it.queryTerm(WhpuQuery.EXAMS))
            assertEquals(DarkMode.DARK, it.flow.first().darkMode)
            assertEquals(week, it.weekSettings(semester))
        }
    }
}
