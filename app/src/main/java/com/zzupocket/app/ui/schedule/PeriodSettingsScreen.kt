package com.zzupocket.app.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.whpu.WhpuPeriodSettings
import com.zzupocket.app.data.whpu.WhpuPeriodTime
import com.zzupocket.app.data.whpu.WhpuTerm
import com.zzupocket.app.ui.components.NumberWheel
import com.zzupocket.app.ui.widget.Widgets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalTime

private val PeriodTimesSaver = listSaver<List<WhpuPeriodTime>, Int>(
    save = { times -> times.flatMap { listOf(it.start, it.end) } },
    restore = { values -> values.chunked(2).map { WhpuPeriodTime(it[0], it[1]) } },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodSettingsScreen(semesterId: Int, onBack: () -> Unit) {
    val term = WhpuTerm.fromSemesterId(semesterId)
    val repo = AppGraph.scheduleRepository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var times by rememberSaveable(semesterId, stateSaver = PeriodTimesSaver) { mutableStateOf(emptyList()) }
    var editing by rememberSaveable(semesterId) { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(semesterId) {
        if (term != null && times.isEmpty()) times = repo.periodsForSemester(semesterId).map { WhpuPeriodTime(it.startTime, it.endTime) }
    }
    val error = times.takeIf { it.isNotEmpty() }?.let(WhpuPeriodSettings::error)
    Scaffold(topBar = {
        TopAppBar(title = { Text("上课作息") }, navigationIcon = {
            TextButton(onClick = onBack, enabled = !saving) { Text("返回") }
        }, actions = {
            TextButton(enabled = term != null && times.isNotEmpty() && error == null && !saving, onClick = {
                saving = true
                scope.launch {
                    try {
                        repo.saveWhpuPeriods(semesterId, times)
                        runCatching { Widgets.refreshAll(context) }
                        AppGraph.classDndController.refresh()
                        onBack()
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { message = "保存失败，请重试。"
                    } finally { saving = false }
                }
            }) { Text(if (saving) "保存中…" else "保存作息") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                if (term == null) Text("请先导入学校课表。") else {
                    Text("${term.year}—${term.year + 1} ${if (term.code == "3") "第一" else "第二"}学期",
                        style = MaterialTheme.typography.titleMedium)
                    Text("北京时间 · 24 小时制。点击时间，用滚轮调整。本学期的课表、今日安排、小组件和上课免打扰同步使用这份作息。",
                        modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    Text("考试使用学校公布的独立考试时间。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            itemsIndexed(times) { index, time ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("第 ${index + 1} 节", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(enabled = !saving, onClick = { editing = index * 2 }) { Text(clockText(time.start)) }
                    Text("—")
                    TextButton(enabled = !saving, onClick = { editing = index * 2 + 1 }) { Text(clockText(time.end)) }
                }
                HorizontalDivider()
            }
            if (term != null && times.isNotEmpty()) item {
                OutlinedButton(enabled = !saving, onClick = { times = WhpuPeriodSettings.defaults(); message = null }) {
                    Text("恢复默认作息")
                }
                Text("修改或恢复默认后，点击右上角“保存作息”生效。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    editing?.let { field ->
        val index = field / 2
        val start = field % 2 == 0
        val time = times.getOrNull(index) ?: return@let
        TimeWheelDialog("第 ${index + 1} 节${if (start) "上课" else "下课"}时间", if (start) time.start else time.end,
            onDismiss = { editing = null }, onConfirm = { value ->
                times = times.mapIndexed { i, old -> if (i != index) old else if (start) old.copy(start = value) else old.copy(end = value) }
                message = null
                editing = null
            })
    }
}

private fun clockText(value: Int) = LocalTime.of(value / 100, value % 100).toString()

@Composable
private fun TimeWheelDialog(title: String, initial: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var hour by rememberSaveable(initial) { mutableIntStateOf(initial / 100) }
    var minute by rememberSaveable(initial) { mutableIntStateOf(initial % 100) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth()) {
                NumberWheel("时", hour, 0, 23, Modifier.weight(1f), true) { hour = it }
                NumberWheel("分", minute, 0, 59, Modifier.weight(1f), true) { minute = it }
            }
            Text(clockText(hour * 100 + minute), style = MaterialTheme.typography.titleLarge)
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(hour * 100 + minute) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
