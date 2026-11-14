package com.zzupocket.app.ui.campus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.whpu.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WhpuElectricityViewModel(
    private val repository: WhpuElectricityRepository = AppGraph.whpuElectricityRepository,
    private val provider: WhpuElectricityProvider = AppGraph.whpuElectricityProvider,
    private val session: WhpuCardSession = AppGraph.whpuCardSession,
    private val onCacheChanged: suspend () -> Unit = {
        AppGraph.appContextOrNull()?.let { com.zzupocket.app.ui.widget.Widgets.refreshAll(it) }
    },
) : ViewModel() {
    val cache = repository.observeCurrent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var needsLogin by mutableStateOf(false)
        private set
    var pickerOpen by mutableStateOf(false)
        private set
    var picker by mutableStateOf<WhpuElectricityDirectorySnapshot?>(null)
        private set
    private var generation = 0L
    private var job: Job? = null

    fun startSelection() {
        pickerOpen = true; picker = null
        loadDirectory(emptyList(), null)
    }

    fun previousLevel() {
        val current = picker ?: return
        if (!busy && current.steps.isNotEmpty()) loadDirectory(current.steps.dropLast(1), current.owner)
    }

    fun choose(option: WhpuElectricityOption) {
        val current = picker ?: return
        if (busy || option !in current.directory.options) return
        val field = current.directory.levels[current.steps.size]
        val steps = current.steps + WhpuElectricityStep(field, option)
        if (steps.size < current.directory.levels.size) loadDirectory(steps, current.owner)
        else refresh(WhpuElectricityRoom(current.owner, current.feeItemId, steps))
    }

    private fun loadDirectory(steps: List<WhpuElectricityStep>, owner: WhpuCardOwner?) = request { revision ->
        val result = provider.directory(steps = steps, expectedOwner = owner)
        session.withCurrent(result.sessionRevision) {
            if (revision != generation) throw CancellationException()
            repository.acceptOwner(result.owner)
        }
        if (revision == generation) {
            picker = result
            refreshWidgets()
        }
    }

    fun refresh(room: WhpuElectricityRoom) = request { revision ->
        repository.refresh(room) { revision == generation }
        if (revision == generation) {
            pickerOpen = false; picker = null
            message = "已保存电费余额"
            refreshWidgets()
        }
    }

    fun select(room: WhpuElectricityRoom) = request {
        repository.select(room)
        refreshWidgets()
    }

    fun clear(owner: WhpuCardOwner) = request {
        repository.clear(owner)
        message = "已清除该账号的本地电费缓存"
        refreshWidgets()
    }

    fun closePicker() {
        generation++; job?.cancel(); job = null
        busy = false; pickerOpen = false; picker = null
    }

    private fun request(block: suspend (Long) -> Unit) {
        job?.cancel()
        val revision = ++generation
        busy = true; message = null; needsLogin = false
        job = viewModelScope.launch {
            try { block(revision)
            } catch (e: CancellationException) { throw e
            } catch (e: WhpuElectricityException) {
                if (revision == generation) {
                    message = e.message
                    needsLogin = e is WhpuElectricityException.SessionExpired || e is WhpuElectricityException.SessionChanged
                }
            } catch (_: WhpuCardException.SessionChanged) {
                if (revision == generation) { message = "登录状态已变化，请重新登录"; needsLogin = true }
            } catch (_: Exception) {
                if (revision == generation) message = "电费查询未完成，已有缓存保留。"
            } finally { if (revision == generation) busy = false }
        }
    }

    private suspend fun refreshWidgets() {
        try { onCacheChanged()
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { message = "电费缓存已更新，桌面组件稍后更新。" }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WhpuElectricityScreen(onBack: () -> Unit, onLogin: () -> Unit, model: WhpuElectricityViewModel = viewModel()) {
    val cache by model.cache.collectAsStateWithLifecycle()
    var groups by rememberSaveable { mutableStateOf(false) }
    var clearOwner by remember { mutableStateOf<WhpuCardOwner?>(null) }
    val openSchool = { model.closePicker(); onLogin() }
    Scaffold(topBar = { TopAppBar(title = { Text("宿舍电费") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("返回") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { cache?.selectedRoom?.let(model::refresh) }, enabled = !model.busy && cache?.selected != null) {
                        Text(if (model.busy) "正在查询…" else "刷新所选房间")
                    }
                    OutlinedButton(onClick = { groups = true }, enabled = !model.busy) { Text("选择房间") }
                    TextButton(onClick = openSchool, enabled = !model.busy) { Text("学校登录 / 官方网页") }
                }
                model.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("显示学校返回的人民币余额；照明与空调分别选择。缴费请进入官方网页。",
                    style = MaterialTheme.typography.bodySmall)
            }
            val current = cache
            if (current == null || current.readings.isEmpty()) item {
                Text("尚未保存电费余额", style = MaterialTheme.typography.titleMedium)
                Text("先在学校网页登录并返回，再选择自己的房间查询。")
            } else {
                items(current.readings) { reading ->
                    OutlinedCard(onClick = { model.select(reading.room) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (current.selectedRoom == reading.room) Text("当前选择", color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge)
                            Text(reading.room.label, style = MaterialTheme.typography.titleMedium)
                            Text("¥ ${cardMoney(reading.balanceFen)}", style = MaterialTheme.typography.headlineMedium)
                            Text("本地缓存 · 更新于 ${cardDateTime(reading.fetchedAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { TextButton(onClick = { clearOwner = current.owner }, enabled = !model.busy) { Text("清除本地电费缓存") } }
            }
        }
    }
    if (groups) AlertDialog(onDismissRequest = { groups = false }, title = { Text("查询分组") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { groups = false; model.startSelection() }, modifier = Modifier.fillMaxWidth()) { Text("其他楼栋") }
            Text("以下分组请在学校网页查询：", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { groups = false; openSchool() }) { Text("金银湖 6、7、8 栋照明") }
            TextButton(onClick = { groups = false; openSchool() }) { Text("常青 1 号楼、金湖 10、11 栋") }
        } }, confirmButton = { TextButton(onClick = { groups = false }) { Text("关闭") } })

    if (model.pickerOpen) {
        val current = model.picker
        val field = current?.directory?.levels?.getOrNull(current.steps.size)
        AlertDialog(onDismissRequest = model::closePicker, title = { Text(field?.let { "选择${it.name}" } ?: "选择房间") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current != null && current.steps.isNotEmpty()) Text(current.steps.joinToString(" · ") { it.option.name },
                    style = MaterialTheme.typography.bodySmall)
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                model.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (model.needsLogin) TextButton(onClick = openSchool) { Text("前往学校登录") }
                if (current != null && current.directory.options.isEmpty()) Text("学校暂未返回可选项，可返回上一层或使用官方网页。")
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(current?.directory?.options.orEmpty()) { option ->
                        TextButton(onClick = { model.choose(option) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(option.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            } }, confirmButton = { TextButton(onClick = model::closePicker) { Text("关闭") } },
            dismissButton = {
                if (current?.steps?.isNotEmpty() == true) TextButton(onClick = model::previousLevel, enabled = !model.busy) { Text("上一层") }
            })
    }
    clearOwner?.let { owner ->
        AlertDialog(onDismissRequest = { clearOwner = null }, title = { Text("清除本地电费缓存？") },
            text = { Text("删除该账号在本机保存的电费余额与房间选择，不影响校园卡、课表或学校账户中的绑定。") },
            confirmButton = { TextButton(onClick = { clearOwner = null; model.clear(owner) }) { Text("清除") } },
            dismissButton = { TextButton(onClick = { clearOwner = null }) { Text("取消") } })
    }
}
