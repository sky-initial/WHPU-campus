package com.zzupocket.app.ui.components

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.prefs.UiPrefsStore
import com.zzupocket.app.data.whpu.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WhpuQueryTermUi(
    val term: WhpuTerm,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val enabled: Boolean get() = loaded && !saving
}

class WhpuQueryTermViewModel(
    private val prefs: UiPrefsStore,
    private val query: WhpuQuery,
    private val currentTerm: suspend () -> WhpuTerm?,
    private val preferCurrent: Boolean = false,
    private val today: LocalDate = WhpuCalendar.today(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(WhpuQueryTermUi(initialWhpuQueryTerm(query, null, null, today)))
    val state = mutableState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(error = null)
            try {
                val term = initialWhpuQueryTerm(query, prefs.queryTerm(query), currentTerm(), today, preferCurrent)
                mutableState.value = WhpuQueryTermUi(term, loaded = true)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(error = "学期选择读取失败，请重试。")
            }
        }
    }

    fun select(term: WhpuTerm) {
        if (!mutableState.value.enabled || term == mutableState.value.term) return
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                prefs.saveQueryTerm(query, term)
                mutableState.value = WhpuQueryTermUi(term, loaded = true)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(saving = false, error = "学期选择保存失败，请重新选择。")
            }
        }
    }
}

@Composable
fun whpuQueryTermViewModel(query: WhpuQuery, preferCurrent: Boolean = false): WhpuQueryTermViewModel =
    viewModel(key = "query-term-$query-$preferCurrent", factory = viewModelFactory {
        initializer {
            WhpuQueryTermViewModel(AppGraph.uiPrefsStore, query,
                { AppGraph.scheduleRepository.currentSemester()?.id?.let(WhpuTerm::fromSemesterId) }, preferCurrent)
        }
    })
