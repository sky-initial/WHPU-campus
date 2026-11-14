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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import com.zzupocket.app.ui.components.CollapsibleSection
import com.zzupocket.app.ui.components.WhpuTermSelector
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WhpuLoginScreen(onBack: () -> Unit, onImported: () -> Unit, signInOnly: Boolean = false, queryName: String = "成绩查询") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var academicPage by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("jwglxt.whpu.edu.cn") }
    var message by remember { mutableStateOf(if (signInOnly) "在下方学校页面完成登录，然后返回${queryName}并刷新。" else "先在下方学校页面登录，再点击“同步到课表”。导入成功后选择第一周日期。") }
    var busy by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf(false) }
    var syncJob by remember { mutableStateOf<Job?>(null) }
    var accountChange by remember { mutableStateOf<WhpuAcademicException.AccountChangeRequired?>(null) }
    var pendingSemesterId by rememberSaveable { mutableStateOf<Int?>(null) }
    var pickerInitial by rememberSaveable { mutableStateOf(WhpuCalendar.today().toString()) }
    var dateError by remember { mutableStateOf<String?>(null) }
    var controlsExpanded by rememberSaveable { mutableStateOf(false) }
    var confirmClearSession by rememberSaveable { mutableStateOf(false) }
    val controlsMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.42f
    var year by rememberSaveable { mutableIntStateOf(WhpuCalendar.today().let { if (it.monthValue >= 9) it.year else it.year - 1 }) }
    var termCode by rememberSaveable { mutableStateOf("3") }
    val term = WhpuTerm(year, termCode)
    fun startSync(replaceAccount: Boolean = false, legacyAccount: WhpuAccountId? = null) {
        val selectedTerm = term
        val view = webView ?: return
        if (!WhpuWebPolicy.isAcademic(view.url.orEmpty())) { message = "请先返回教务系统页面。"; return }
        AppGraph.whpuSessionManager.acceptBrowserSession(
            CookieManager.getInstance().getCookie(WhpuWebPolicy.SCHEDULE), view.settings.userAgentString,
            CookieManager.getInstance().getCookie(WhpuWebPolicy.GRADES),
            CookieManager.getInstance().getCookie(WhpuWebPolicy.EXAMS),
        )
        busy = true
        imported = false
        message = "正在读取并保存课表…"
        syncJob = scope.launch {
            try {
                val schedule = AppGraph.whpuImportCoordinator.import(selectedTerm, replaceAccount, legacyAccount)
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
                accountChange = e
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
    accountChange?.let { change ->
        WhpuAccountChangeDialog(change.legacyCache, onConfirm = { accountChange = null; startSync(true) },
            onDismiss = { accountChange = null }, onBindLegacy = change.account?.let { account ->
                { accountChange = null; startSync(legacyAccount = account) }
            })
    }
    DisposableEffect(Unit) {
        onDispose {
            syncJob?.cancel()
            val view = webView
            webView = null
            view?.stopLoading()
            view?.destroy()
        }
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

    if (confirmClearSession) AlertDialog(
        onDismissRequest = { confirmClearSession = false },
        title = { Text("清除应用内网页登录状态？") },
        text = { Text("这会清除本应用所有内置网页的登录状态，包括教务、校园卡和座位预约，之后需要重新登录。离线课表、成绩、考试、日程和设置保留；外部浏览器不受影响。") },
        dismissButton = { TextButton(onClick = { confirmClearSession = false }) { Text("取消") } },
        confirmButton = { TextButton(onClick = {
            confirmClearSession = false
            busy = true
            academicPage = false
            webView?.stopLoading()
            AppGraph.whpuSessionManager.clear()
            imported = false
            scope.launch {
                AppGraph.whpuCardSession.clear()
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    webView?.clearHistory()
                    webView?.loadUrl(WhpuWebPolicy.LOGIN)
                    busy = false
                    message = "已清除应用内全部网页登录状态，本地缓存保留。"
                }
            }
        }) { Text("清除登录状态") } },
    )
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (signInOnly) "学校登录" else "学校登录与课表同步") }, navigationIcon = {
            TextButton(onClick = onBack) { Text(if (signInOnly) "返回$queryName" else "返回") }
        }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            CollapsibleSection(
                title = if (busy) "正在同步…" else if (signInOnly) "登录操作" else "$year—${year + 1} · ${if (termCode == "3") "第一学期" else "第二学期"} · 同步操作",
                expanded = controlsExpanded, onExpandedChange = { controlsExpanded = it },
            ) {
                Column(Modifier.heightIn(max = controlsMaxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!signInOnly) WhpuTermSelector(term, enabled = !busy, onSelect = { year = it.year; termCode = it.code })
                    Text(message, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && (signInOnly || academicPage), onClick = {
                            if (signInOnly) onImported() else startSync()
                        }) {
                            Text(if (signInOnly) "返回${queryName}" else if (busy) "同步中…" else "同步到课表")
                        }
                        if (syncJob?.isActive == true) TextButton(onClick = {
                            syncJob?.cancel()
                            message = "已取消同步，原缓存保留。"
                        }) { Text("取消同步") }
                        if (imported) TextButton(onClick = onImported) { Text("查看课表") }
                        TextButton(enabled = !busy, onClick = { confirmClearSession = true }) {
                            Text("清除网页登录态 / 换账号")
                        }
                    }
                    Text("学校页面：$host", style = MaterialTheme.typography.labelSmall)
                    TextButton(enabled = !busy, onClick = {
                        message = "正在返回教务系统，已保留学校登录状态。"
                        webView?.loadUrl(WhpuWebPolicy.LOGIN)
                    }) { Text("返回教务") }
                }
            }
            AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { ctx ->
                WebView(ctx).apply {
                    webChromeClient = com.zzupocket.app.ui.web.SchoolWebChromeClient()
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
                                message = if (signInOnly) "已返回教务页面。登录完成后返回${queryName}并刷新。" else "已返回教务页面。完成学校登录后点击“同步到课表”，导入成功后再设置日期。"
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
