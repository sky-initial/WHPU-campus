package com.zzupocket.app.ui.dnd

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    var countdownAccess by remember { mutableStateOf(controller.hasCountdownNotifications()) }
    var systemFilter by remember { mutableIntStateOf(controller.systemInterruptionFilter()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        countdownAccess = controller.hasCountdownNotifications()
        scope.launch { controller.refresh() }
    }
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
        countdownAccess = controller.hasCountdownNotifications()
        systemFilter = controller.systemInterruptionFilter()
        scope.launch { controller.refresh() }
    }
    fun openSettings(action: String, packageUri: Boolean = false) {
        val intent = Intent(action).apply { if (packageUri) data = Uri.parse("package:${context.packageName}") }
        runCatching { context.startActivity(intent) }.onFailure { error = "无法打开此设置，请在系统设置中搜索相应权限。" }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("上课免打扰与提醒") }, navigationIcon = {
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
            Text("请在系统应用设置中允许后台运行。强行停止后，需要重新打开应用以恢复调度。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) { Text("打开应用后台设置") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("使用系统闹钟调度", modifier = Modifier.weight(1f))
                Switch(checked = prefs.useSystemAlarmClock, enabled = exactAccess || prefs.useSystemAlarmClock,
                    onCheckedChange = { enabled -> scope.launch { controller.setUseSystemAlarmClock(enabled) } })
            }
            Text("如果后台切换延迟，可尝试开启。系统可能显示下一次闹钟时间，应用不会播放闹钟铃声。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { openSettings(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS) }) { Text("系统优先通知设置") }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("开启上课倒计时", modifier = Modifier.weight(1f))
                Switch(checked = prefs.reminderEnabled, onCheckedChange = { enabled ->
                    scope.launch { controller.setReminderEnabled(enabled) }
                    if (enabled && !countdownAccess && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                })
            }
            Text("上课前 10 分钟显示倒计时，上课后切换为下课倒计时。支持实时通知的设备可在状态栏或灵动岛显示，也可在通知栏查看。")
            if (prefs.reminderEnabled && !countdownAccess) {
                Text("请在系统设置中允许 WHPU Pocket 和上课倒计时通知。")
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }.onFailure { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }
                }) { Text("打开通知设置") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
