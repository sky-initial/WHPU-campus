package com.zzupocket.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.whpu.CurrentWhpuExams
import com.zzupocket.app.data.whpu.WhpuCalendar
import com.zzupocket.app.ui.components.GlassCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.time.format.DateTimeFormatter

data class TodayExamsUi(val cache: CurrentWhpuExams, val now: Instant)

class TodayExamsViewModel : ViewModel() {
    private val clock = flow {
        while (true) {
            emit(Instant.now())
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    val state = combine(AppGraph.whpuExamRepository.observeCurrent(), clock, ::TodayExamsUi)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), null)
}

@Composable
fun TodayExamsCard(onOpenExams: () -> Unit, vm: TodayExamsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val current = state ?: return
    TodayExamsContent(current, onOpenExams)
}

@Composable
internal fun TodayExamsContent(state: TodayExamsUi, onOpenExams: () -> Unit) {
    val term = state.cache.term ?: return
    val today = state.now.atZone(WhpuCalendar.zone).toLocalDate()
    val exams = state.cache.on(today)
    val fetchedAt = state.cache.snapshot?.fetchedAt
    val dateFormat = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(WhpuCalendar.zone) }
    GlassCard(Modifier.fillMaxWidth().clickable(onClick = onOpenExams)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (fetchedAt == null) "今日考试" else "今日考试 · ${exams.size} 场",
                style = MaterialTheme.typography.titleMedium)
            Text("${term.year}—${term.year + 1} ${if (term.code == "3") "第一" else "第二"}学期 · 北京时间",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (fetchedAt == null) {
                Text("本学期尚未同步考试，点击查询。")
            } else {
                if (exams.isEmpty()) Text("本地缓存中暂无今日考试。")
                exams.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.exam.courseName, style = MaterialTheme.typography.titleSmall)
                        Text("${item.time.start}–${item.time.end} · ${item.time.status(state.now)}",
                            color = MaterialTheme.colorScheme.primary)
                        Text("考场：${item.exam.location ?: "未公布"}", style = MaterialTheme.typography.bodyMedium)
                        item.exam.campus?.let { Text("考试校区：$it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (state.cache.unplacedCount > 0) Text("${state.cache.unplacedCount} 场考试时间未公布或暂无法识别，请查看完整列表。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text("缓存更新于 ${dateFormat.format(Instant.ofEpochMilli(fetchedAt))} · 查看全部考试 ›",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
