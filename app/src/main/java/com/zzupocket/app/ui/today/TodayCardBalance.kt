package com.zzupocket.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.Card
import com.zzupocket.app.ui.components.GlassCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.whpu.totals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** 「今天」页的校园卡状态 */
data class TodayCardUi(
    val loggedIn: Boolean = false,
    /** 余额（元）；拿不到就是 null */
    val balance: Double? = null,
    val todaySpend: Double = 0.0,
    val todayIncome: Double = 0.0,
    val todayCount: Int = 0,
    /** "食堂 · -12.50" 这类最近一笔 */
    val lastTrade: String? = null,
    val loading: Boolean = false,
    val note: String? = null,
)

/** Today reads the same selected-card snapshot as the native list and widgets. No network requests. */
class TodayCardViewModel : ViewModel() {
    private val repo = AppGraph.whpuCardRepository
    private val _state = MutableStateFlow(TodayCardUi())
    val state: StateFlow<TodayCardUi> = _state.asStateFlow()
    init { viewModelScope.launch { repo.observeCurrent().collect { _state.value = it.toTodayCardUi() } } }
    fun reload() { viewModelScope.launch { _state.value = repo.current().toTodayCardUi() } }
}

private fun com.zzupocket.app.data.whpu.WhpuCardCache?.toTodayCardUi(): TodayCardUi {
    val cache = this ?: return TodayCardUi(note = "登录校园卡后手动同步余额与账单")
    val selected = cache.selectedCard ?: return TodayCardUi(note = "学校当前未返回校园卡")
    val total = cache.totals()!!
    val today = com.zzupocket.app.data.whpu.WhpuCalendar.today()
    val last = cache.transactionsFor(selected.account).filter {
        java.time.Instant.ofEpochMilli(it.displayedAt).atZone(com.zzupocket.app.data.whpu.WhpuCalendar.zone).toLocalDate() == today
    }.maxByOrNull { it.displayedAt }
    return TodayCardUi(loggedIn = true, balance = selected.balanceFen / 100.0,
        todaySpend = total.todaySpendFen / 100.0, todayIncome = total.todayIncomeFen / 100.0,
        todayCount = total.todayCount,
        lastTrade = last?.let { "${it.summary.ifBlank { "校园卡交易" }} ${com.zzupocket.app.data.whpu.cardMoney(it.amountFen)}" },
        note = "本地缓存 · 更新于 ${com.zzupocket.app.data.whpu.cardDateTime(cache.fetchedAt)}")
}

@Composable
fun TodayCardBalanceCard(
    state: TodayCardUi,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CreditCard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("校园卡", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else if (state.balance != null) {
                    Text(
                        text = "¥ ${formatMoney(state.balance)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                } else {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            }

            if (state.balance != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = buildString {
                        append("今日支出 ¥ ${formatMoney(state.todaySpend)}")
                        if (state.todayCount > 0) append(" · ${state.todayCount} 笔交易")
                        if (state.todayIncome > 0) {
                            append(" · 入账 ¥ ${formatMoney(state.todayIncome)}")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.lastTrade?.let {
                Text(
                    text = "最近：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 12.5 → "12.50"；-3 → "-3.00" */
private fun formatMoney(v: Double): String = String.format(Locale.US, "%.2f", v)
