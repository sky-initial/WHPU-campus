package com.zzupocket.app.ui.campus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.whpu.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WhpuCardViewModel : ViewModel() {
    private val repo = AppGraph.whpuCardRepository
    val cache = repo.observeCurrent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    fun refresh() {
        if (busy) return
        busy = true; message = null
        viewModelScope.launch {
            try {
                val result = repo.refresh()
                message = "已保存 ${result.transactions.size} 条流水"
                refreshWidgets()
            } catch (e: CancellationException) { throw e
            } catch (e: WhpuCardException) { message = e.message
            } catch (_: Exception) { message = "查询失败，本地缓存保留。"
            } finally { busy = false }
        }
    }
    fun select(owner: WhpuCardOwner, account: String) {
        viewModelScope.launch {
            try {
                repo.selectCard(owner, account)
                refreshWidgets()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { message = "卡片已变化，请重新选择。" }
        }
    }
    private suspend fun refreshWidgets() {
        try { AppGraph.appContextOrNull()?.let { com.zzupocket.app.ui.widget.Widgets.refreshAll(it) }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { message = "校园卡已保存，桌面组件稍后更新。" }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WhpuCardScreen(onBack: () -> Unit, onLogin: () -> Unit, model: WhpuCardViewModel = viewModel()) {
    val cache by model.cache.collectAsStateWithLifecycle()
    val current = cache
    val card = current?.selectedCard
    val rows = card?.let { current.transactionsFor(it.account) }.orEmpty().sortedByDescending { it.displayedAt }
    Scaffold(topBar = { TopAppBar(title = { Text("校园卡") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("返回") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = model::refresh, enabled = !model.busy) { Text(if (model.busy) "正在刷新…" else "刷新") }
                    OutlinedButton(onClick = onLogin, enabled = !model.busy) { Text("学校登录 / 官方网页") }
                }
                model.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (current == null) item {
                Text("尚未同步校园卡", style = MaterialTheme.typography.titleMedium)
                Text("在学校网页完成登录，选择“虚拟校园卡”，再点击“同步余额与账单”。")
            } else {
                item {
                    Text("本地缓存 · 更新于 ${cardDateTime(current.fetchedAt)}", style = MaterialTheme.typography.bodySmall)
                    if (current.cards.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        current.cards.forEach { candidate ->
                            FilterChip(selected = card?.account == candidate.account,
                                onClick = { model.select(current.owner, candidate.account) },
                                label = { Text("${candidate.name.ifBlank { "校园卡" }} · 尾号 ${candidate.account.takeLast(4)}") })
                        }
                    }
                    if (card == null) Text("学校当前未返回校园卡，可在官方网页核对。")
                    else Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(card.name.ifBlank { "校园卡" }, style = MaterialTheme.typography.titleMedium)
                            Text("¥ ${cardMoney(card.balanceFen)}", style = MaterialTheme.typography.headlineMedium)
                            current.totals()?.let { Text("今日支出 ¥ ${cardMoney(it.todaySpendFen)} · 入账 ¥ ${cardMoney(it.todayIncomeFen)}") }
                        }
                    }
                }
                if (card != null) item { Text("账单 · 北京时间", style = MaterialTheme.typography.titleMedium) }
                if (card != null && rows.isEmpty()) item { Text("暂无流水") }
                items(rows, key = { "${it.cardAccount}:${it.orderId}" }) { row ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(row.summary.ifBlank { "校园卡交易" }, modifier = Modifier.weight(1f))
                            Text((if (row.amountFen > 0) "+" else "") + cardMoney(row.amountFen),
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                        Text(cardDateTime(row.displayedAt), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
