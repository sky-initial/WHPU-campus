package com.zzupocket.app.ui.grades

import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.whpu.*
import com.zzupocket.app.ui.components.WhpuTermSelector
import com.zzupocket.app.ui.components.GlassCard
import com.zzupocket.app.ui.login.WhpuAccountChangeDialog
import com.zzupocket.app.ui.widget.Widgets
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WhpuGradeScreen(onBack: () -> Unit, onLogin: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = remember { WhpuCalendar.today() }
    var year by rememberSaveable { mutableIntStateOf(if (today.monthValue >= 9) today.year else today.year - 1) }
    var code by rememberSaveable { mutableStateOf(if (today.monthValue in 2..8) "12" else "3") }
    val term = WhpuTerm(year, code)
    val repository = AppGraph.whpuGradeRepository
    val cacheFlow = remember(term) { repository.observe(term) }
    val cached by cacheFlow.collectAsStateWithLifecycle(initialValue = null)
    val snapshot = cached?.takeIf { it.term == term }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var accountChange by remember { mutableStateOf<WhpuAcademicException.AccountChangeRequired?>(null) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(WhpuCalendar.zone) }

    fun refresh(replace: Boolean = false, legacyAccount: WhpuAccountId? = null) {
        val selected = term
        AppGraph.whpuSessionManager.acceptBrowserSession(
            CookieManager.getInstance().getCookie(WhpuWebPolicy.SCHEDULE), WebSettings.getDefaultUserAgent(context),
            CookieManager.getInstance().getCookie(WhpuWebPolicy.GRADES),
            CookieManager.getInstance().getCookie(WhpuWebPolicy.EXAMS),
        )
        busy = true
        message = "正在读取完整学期成绩…"
        job = scope.launch {
            try {
                val result = repository.refresh(selected, replace, legacyAccount)
                job = null
                message = "已保存 ${result.grades.size} 条成绩，可离线查看。"
                runCatching { Widgets.refreshAll(context) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WhpuAcademicException.AccountChangeRequired) {
                accountChange = e
                message = "尚未替换本机数据。"
            } catch (e: Exception) {
                message = when (e) {
                    is WhpuAcademicException.SessionExpired -> "登录尚未完成或已过期，请点击“学校登录”。"
                    is WhpuAcademicException.MissingAccount -> "学校响应缺少账号信息，暂不能确认成绩归属。"
                    is WhpuAcademicException.SessionChanged -> "登录状态已变化，请重新刷新。"
                    is WhpuAcademicException.Forbidden -> "学校拒绝了查询请求，请稍后重试。"
                    is WhpuAcademicException.Network -> "网络连接失败，请检查网络后重试。"
                    is WhpuAcademicException.Http -> "学校服务暂不可用（${e.code}）。"
                    is WhpuAcademicException.Parse, is IllegalArgumentException -> "成绩响应不完整或格式暂不支持，请稍后重试。"
                    else -> "成绩保存失败，请重试。"
                } + " 原缓存保留。"
            } finally { busy = false }
        }
    }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    LaunchedEffect(term) { message = null }
    accountChange?.let { change ->
        WhpuAccountChangeDialog(change.legacyCache, onConfirm = { accountChange = null; refresh(true) },
            onDismiss = { accountChange = null; message = "已保留原数据。" }, onBindLegacy = change.account?.let { account ->
                { accountChange = null; refresh(legacyAccount = account) }
            })
    }
    Scaffold(topBar = { TopAppBar(title = { Text("成绩查询") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("返回") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                WhpuTermSelector(term, enabled = !busy, onSelect = {
                    year = it.year
                    code = it.code
                })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { refresh() }, enabled = !busy) { Text(if (busy) "刷新中…" else "刷新成绩") }
                    TextButton(onClick = onLogin, enabled = !busy) { Text("学校登录 / 换账号") }
                    if (job?.isActive == true) TextButton(onClick = {
                        job?.cancel(); message = "已取消刷新，原缓存保留。"
                    }) { Text("取消刷新") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) }
            }
            item {
                if (snapshot == null) {
                    Text("本学期尚无成绩缓存。登录学校后点击刷新。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("学校成绩 · 本地缓存 · ${snapshot.grades.size} 条", style = MaterialTheme.typography.titleSmall)
                    Text("更新于 ${dateFormat.format(Instant.ofEpochMilli(snapshot.fetchedAt))}（北京时间）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (snapshot.grades.isEmpty()) Text("学校本次查询返回本学期暂无成绩。", modifier = Modifier.padding(top = 12.dp))
                }
            }
            items(snapshot?.grades.orEmpty()) { grade ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(grade.courseName, style = MaterialTheme.typography.titleMedium)
                        Text(grade.grade ?: "未公布", style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text("学分：${grade.credits ?: "—"}    成绩性质：${grade.examType ?: "未提供"}")
                        Text("绩点：${grade.gradePoint ?: "—"}    学分绩点：${grade.creditGradePoint ?: "—"}")
                        grade.courseNature?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        grade.courseCode?.let { Text("课程代码：$it", style = MaterialTheme.typography.bodySmall) }
                        grade.assessment?.let { Text("考核方式：$it", style = MaterialTheme.typography.bodySmall) }
                        grade.remark?.let { Text("成绩备注：$it") }
                        grade.voided?.takeIf { it != "否" }?.let { Text("成绩作废：$it") }
                    }
                }
            }
        }
    }
}
