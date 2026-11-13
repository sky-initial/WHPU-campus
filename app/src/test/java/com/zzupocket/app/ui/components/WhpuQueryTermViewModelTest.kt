package com.zzupocket.app.ui.components

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import com.zzupocket.app.data.prefs.UiPrefsStore
import com.zzupocket.app.data.whpu.*
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WhpuQueryTermViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val current = WhpuTerm(2030, "3")
    private val previous = WhpuTerm(2029, "12")
    private val data = MemoryStore()
    private val prefs = UiPrefsStore(data)

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { models.clear(); Dispatchers.resetMain() }

    private fun model(query: WhpuQuery = WhpuQuery.EXAMS, preferCurrent: Boolean = false,
        currentTerm: suspend () -> WhpuTerm? = { current }): WhpuQueryTermViewModel =
        WhpuQueryTermViewModel(prefs, query, currentTerm, preferCurrent, LocalDate.of(2030, 11, 13)).also {
            models.put("$query-$preferCurrent", it)
        }

    @Test fun delayedRestoreDoesNotAllowSelectingOrOverwriteSavedChoice() = runTest(dispatcher) {
        prefs.saveQueryTerm(WhpuQuery.EXAMS, previous)
        val gate = CompletableDeferred<WhpuTerm?>()
        val vm = model(currentTerm = { gate.await() })
        runCurrent()
        assertFalse(vm.state.value.enabled)
        vm.select(current)
        gate.complete(current)
        advanceUntilIdle()
        assertTrue(vm.state.value.enabled)
        assertEquals(previous, vm.state.value.term)
        assertEquals(previous, prefs.queryTerm(WhpuQuery.EXAMS))
    }

    @Test fun currentTermEntryDoesNotOverwriteLastExplicitExamChoice() = runTest(dispatcher) {
        prefs.saveQueryTerm(WhpuQuery.EXAMS, previous)
        val vm = model(preferCurrent = true)
        advanceUntilIdle()
        assertEquals(current, vm.state.value.term)
        assertEquals(previous, prefs.queryTerm(WhpuQuery.EXAMS))
    }

    @Test fun selectingAndReopeningRestoresEachBusinessIndependently() = runTest(dispatcher) {
        val gradeModel = model(WhpuQuery.GRADES)
        val examModel = model()
        advanceUntilIdle()
        gradeModel.select(previous)
        examModel.select(WhpuTerm(2028, "3"))
        advanceUntilIdle()
        models.clear()
        val reopenedGrade = model(WhpuQuery.GRADES)
        val reopenedExam = model()
        advanceUntilIdle()
        assertEquals(previous, reopenedGrade.state.value.term)
        assertEquals(WhpuTerm(2028, "3"), reopenedExam.state.value.term)
    }

    @Test fun failedSaveKeepsDisplayedAndPersistedTermThenAllowsRetry() = runTest(dispatcher) {
        prefs.saveQueryTerm(WhpuQuery.EXAMS, current)
        val vm = model()
        advanceUntilIdle()
        data.failWrites = true
        vm.select(previous)
        advanceUntilIdle()
        assertEquals(current, vm.state.value.term)
        assertEquals(current, prefs.queryTerm(WhpuQuery.EXAMS))
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.enabled)
        data.failWrites = false
        vm.select(previous)
        advanceUntilIdle()
        assertEquals(previous, vm.state.value.term)
        assertEquals(previous, prefs.queryTerm(WhpuQuery.EXAMS))
        assertNull(vm.state.value.error)
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        var failWrites = false
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("synthetic write failure")
            return transform(data.value).also { data.value = it }
        }
    }
}
