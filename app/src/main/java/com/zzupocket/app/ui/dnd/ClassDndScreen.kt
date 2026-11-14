package com.zzupocket.app.ui.dnd

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.dnd.ClassDndSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassDndScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = AppGraph.classDndController
    val scope = rememberCoroutineScope()
    val prefs by controller.store.flow.collectAsStateWithLifecycle(initialValue = ClassDndSettings())
    val status by controller.status.collectAsStateWithLifecycle()
    var policyAccess by remember { mutableStateOf(controller.hasPolicyAccess()) }
    var exactAccess by remember { mutableStateOf(controller.hasExactAlarmAccess()) }
    var systemFilter by remember { mutableIntStateOf(controller.systemInterruptionFilter()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(context, controller) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                systemFilter = controller.systemInterruptionFilter()
            }
        }
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        policyAccess = controller.hasPolicyAccess()
        exactAccess = controller.hasExactAlarmAccess()
        systemFilter = controller.systemInterruptionFilter()
        scope.launch { controller.refresh() }
    }
    fun openSettings(action: String, packageUri: Boolean = false) {
        val intent = Intent(action).apply { if (packageUri) data = Uri.parse("package:${context.packageName}") }
        runCatching { context.startActivity(intent) }.onFailure { error = "无法打开此设置，请在系统设置中搜索相应权限。" }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("上课免打扰") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("按真实课表自动切换", style = MaterialTheme.typography.titleMedium)
            Text("上课时允许闹钟，并沿用系统设置的优先通知；下课只结束 WHPU 的规则，不关闭你手动开启的免打扰。")
            Text("使用当前课表学期、已确认的第一周日期和北京时间作息。演示课表、自定义日程、无具体时间的实践安排不会触发。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("开启上课免打扰", modifier = Modifier.weight(1f))
                Switch(checked = prefs.enabled, enabled = !busy && (prefs.enabled || (policyAccess && exactAccess)),
                    onCheckedChange = { enabled ->
                        busy = true; error = null
                        scope.launch {
                            try { controller.setEnabled(enabled) }
                            catch (e: Exception) { error = "设置未完成，请检查系统权限后重试。" }
                            finally { busy = false }
                        }
                    })
            }
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val systemDndOn = systemFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
                systemFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS ||
                systemFilter == NotificationManager.INTERRUPTION_FILTER_NONE
            Text(when {
                systemDndOn -> "系统免打扰：已开启"
                systemFilter == NotificationManager.INTERRUPTION_FILTER_ALL -> "系统免打扰：已关闭"
                else -> "系统免打扰：暂时无法读取"
            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!prefs.enabled && systemDndOn) {
                Text("上课自动切换已关闭，系统免打扰仍开启。请在系统设置中查看正在生效的模式或规则。",
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent("android.settings.ZEN_MODE_SETTINGS")) }
                    .onFailure { openSettings(Settings.ACTION_SOUND_SETTINGS) }
            }, modifier = Modifier.fillMaxWidth()) { Text("查看系统免打扰") }
            OutlinedButton(onClick = { openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (policyAccess) "免打扰访问：已授权" else "授予免打扰访问")
            }
            OutlinedButton(onClick = { openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (exactAccess) "准时调度：已授权" else "授予准时调度权限")
            }
            Text("需要允许应用后台运行。强行停止应用或系统限制后台时，自动切换可能延迟；可以在系统免打扰设置中手动结束规则。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { openSettings(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS) }) { Text("系统优先通知设置") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
