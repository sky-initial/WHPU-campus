package com.zzupocket.app.ui.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.WeekSource
import com.zzupocket.app.data.whpu.*
import com.zzupocket.app.ui.widget.Widgets
import com.zzupocket.app.ui.components.WeekStartWheelDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import android.webkit.WebStorage
import kotlinx.coroutines.launch
import java.time.LocalDate

/** School-owned sign-in UI. No password field, JavaScript bridge or credential logging in the app. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhpuLoginScreen(onBack: () -> Unit, onImported: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var academicPage by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("jwglxt.whpu.edu.cn") }
    var message by remember { mutableStateOf("先在下方学校页面登录，再点击“同步到课表”。导入成功后选择第一周日期。") }
    var busy by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf(false) }
    var syncJob by remember { mutableStateOf<Job?>(null) }
    var accountChange by remember { mutableStateOf<Boolean?>(null) }
    var pendingSemesterId by rememberSaveable { mutableStateOf<Int?>(null) }
    var pickerInitial by rememberSaveable { mutableStateOf(WhpuCalendar.today().toString()) }
    var dateError by remember { mutableStateOf<String?>(null) }
    var year by rememberSaveable { mutableStateOf("2026") }
    var termCode by rememberSaveable { mutableStateOf("3") }
    val term = year.toIntOrNull()?.takeIf { it in 2000..2199 }?.let { WhpuTerm(it, termCode) }
    fun startSync(replaceAccount: Boolean = false) {
        val selectedTerm = term
        if (selectedTerm == null) { message = "请填写有效的学年。"; return }
        val view = webView ?: return
        if (!WhpuWebPolicy.isAcademic(view.url.orEmpty())) { message = "请先返回教务系统页面。"; return }
        AppGraph.whpuSessionManager.acceptBrowserSession(
            CookieManager.getInstance().getCookie(WhpuWebPolicy.SCHEDULE), view.settings.userAgentString,
        )
        busy = true
        imported = false
        message = "正在读取并保存课表…"
        syncJob = scope.launch {
            try {
                val schedule = AppGraph.whpuImportCoordinator.import(selectedTerm, replaceAccount)
                syncJob = null // The complete snapshot is committed; subsequent date setup is a separate step.
                val previous = AppGraph.uiPrefsStore.weekSettings(selectedTerm.semesterId)
                pickerInitial = (previous.weekOneStart
                    ?: AppGraph.scheduleRepository.semester(selectedTerm.semesterId)?.startDate
                    ?: WhpuCalendar.today()).toString()
                imported = true
                message = "已保存 ${schedule.courses.size} 条排课和 ${schedule.practice.size} 条实践安排，可离线查看。"
                runCatching { Widgets.refreshAll(context) }
                pendingSemesterId = selectedTerm.semesterId
            } catch (e: CancellationException) {
                throw e
            } catch (e: WhpuAcademicException.AccountChangeRequired) {
                accountChange = e.legacyCache
                message = "尚未替换本机课表，原缓存保留。"
            } catch (e: Exception) {
                message = when (e) {
                    is WhpuAcademicException.SessionExpired -> "登录尚未完成或会话已过期，请返回学校页面重新登录。原缓存保留。"
                    is WhpuAcademicException.SessionChanged -> "登录状态已变化，请使用当前账号重新同步。原缓存保留。"
                    is WhpuAcademicException.MissingAccount -> "学校响应缺少账号标识，暂不能核对课表归属。原缓存保留。"
                    is WhpuAcademicException.Forbidden -> "学校拒绝了同步请求，请稍后重试。原缓存保留。"
                    is WhpuAcademicException.Network -> "网络连接失败，请稍后重试。原缓存保留。"
                    is WhpuAcademicException.Http -> "学校服务暂不可用（${e.code}）。原缓存保留。"
                    is WhpuAcademicException.Parse -> "课表格式暂不支持。原缓存保留。"
                    else -> "课表保存失败，请稍后重试。原缓存保留。"
                }
            } finally { busy = false }
        }
    }
    accountChange?.let { legacy ->
        AlertDialog(
            onDismissRequest = { accountChange = null },
            title = { Text(if (legacy) "确认旧版课表归属" else "检测到不同账号") },
            text = { Text((if (legacy) "旧版缓存未记录账号，无法确认与当前登录账号是否一致。" else "当前登录账号与本机学校课表所属账号不同。") +
                "继续会重新获取课表，成功后替换所有学期的旧学校课表和课程显示修改；自定义日程、演示课表保留。获取失败时不替换。") },
            confirmButton = { TextButton(onClick = { accountChange = null; startSync(true) }) { Text("替换并导入") } },
            dismissButton = { TextButton(onClick = { accountChange = null }) { Text("保留原课表") } },
        )
    }
    DisposableEffect(Unit) {
        onDispose { syncJob?.cancel(); webView?.stopLoading(); webView?.destroy() }
    }
    pendingSemesterId?.let { semesterId ->
        WeekStartWheelDialog(
            initialDate = LocalDate.parse(pickerInitial), saving = busy, error = dateError,
            onConfirm = { monday ->
                busy = true
                dateError = null
                scope.launch {
                    try {
                        val previous = AppGraph.uiPrefsStore.weekSettings(semesterId)
                        AppGraph.uiPrefsStore.saveWeekSettings(semesterId,
                            previous.copy(weekOneStart = monday, startDay = com.zzupocket.app.core.WeekStartDay.MONDAY,
                                confirmed = true, source = WeekSource.USER))
                        runCatching { Widgets.refreshAll(context) }
                        pendingSemesterId = null
                        onImported()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        dateError = "日期保存失败，请重试。课表已保存在本机。"
                    } finally { busy = false }
                }
            },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("学校登录与课表同步") }, navigationIcon = {
            TextButton(onClick = onBack) { Text("返回") }
        }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(year, { year = it }, label = { Text("学年起始年") },
                        modifier = Modifier.weight(1f), singleLine = true, enabled = !busy)
                    FilterChip(selected = termCode == "3", onClick = { termCode = "3" }, label = { Text("第一学期") }, enabled = !busy)
                    FilterChip(selected = termCode == "12", onClick = { termCode = "12" }, label = { Text("第二学期") }, enabled = !busy)
                }
                Text(message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && academicPage, onClick = { startSync() }) {
                        Text(if (busy) "同步中…" else "同步到课表")
                    }
                    if (syncJob?.isActive == true) TextButton(onClick = {
                        syncJob?.cancel()
                        message = "已取消同步，原缓存保留。"
                    }) { Text("取消同步") }
                    if (imported) TextButton(onClick = onImported) { Text("查看课表") }
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        webView?.stopLoading()
                        AppGraph.whpuSessionManager.clear()
                        imported = false
                        WebStorage.getInstance().deleteAllData()
                        CookieManager.getInstance().removeAllCookies {
                            webView?.post {
                                CookieManager.getInstance().flush()
                                webView?.clearHistory(); webView?.loadUrl(WhpuWebPolicy.LOGIN)
                                busy = false
                            }
                        }
                        message = "已清除本机网页登录状态，离线课表保留。"
                    }) { Text("清除登录态 / 换账号") }
                }
                Text("学校页面：$host", style = MaterialTheme.typography.labelSmall)
                TextButton(enabled = !busy, onClick = {
                    message = "正在返回教务系统，已保留学校登录状态。"
                    webView?.loadUrl(WhpuWebPolicy.LOGIN)
                }) { Text("返回教务") }
            }
            AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.WHITE)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val secureReturn = WhpuWebPolicy.academicHttpsRedirect(
                                request.url.toString(), request.method, request.isForMainFrame,
                            )
                            if (secureReturn != null) {
                                academicPage = false
                                message = "正在通过 HTTPS 返回教务系统…"
                                view.loadUrl(secureReturn)
                                return true
                            }
                            val allowed = WhpuWebPolicy.allows(request.url.toString())
                            if (!allowed && request.isForMainFrame) {
                                message = "登录跳转暂未支持：${WhpuWebPolicy.navigationOrigin(request.url.toString())}。可点击“返回教务”重试。"
                            }
                            return !allowed
                        }
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            if (syncJob?.isActive == true) {
                                syncJob?.cancel()
                                message = "学校页面已跳转，本次同步已取消，原缓存保留。"
                            }
                            academicPage = false
                            if (!WhpuWebPolicy.allows(url)) { view.stopLoading(); return }
                            host = android.net.Uri.parse(url).host.orEmpty()
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            academicPage = WhpuWebPolicy.isAcademic(url)
                            if (academicPage && message == "正在通过 HTTPS 返回教务系统…") {
                                message = "已返回教务页面。完成学校登录后点击“同步到课表”，导入成功后再设置日期。"
                            }
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) message = "学校页面暂时无法加载，请检查网络后重新进入。"
                        }
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame) message = "学校页面返回 ${response.statusCode}，请稍后重新进入。"
                        }
                    }
                    webView = this
                    loadUrl(WhpuWebPolicy.LOGIN)
                }
            })
        }
    }
}
