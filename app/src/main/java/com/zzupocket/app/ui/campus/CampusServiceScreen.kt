package com.zzupocket.app.ui.campus

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.MutableContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zzupocket.app.data.whpu.*
import com.zzupocket.app.core.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Retained only in memory across rotation; auth URLs are never written to saved state. */
@SuppressLint("SetJavaScriptEnabled")
class CampusWebViewModel(context: Context, val service: WhpuCampusService) : ViewModel() {
    private val appContext = context.applicationContext
    private val windowContext = MutableContextWrapper(context)
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    private var cancelViewportFix: (() -> Unit)? = null
    @Volatile private var cleared = false
    @Volatile private var navigationRevision = 0L
    var cardReady by mutableStateOf(false)
        private set
    var cardSyncing by mutableStateOf(false)
        private set
    var cardMessage by mutableStateOf<String?>(null)
        private set
    var cardSaved by mutableStateOf(false)
        private set
    private fun cancelPendingFix() {
        cancelViewportFix?.invoke()
        cancelViewportFix = null
    }
    private fun repairViewport(view: WebView, url: String?) {
        cancelPendingFix()
        if (cleared || !view.isAttachedToWindow || url == null || !service.allows(url)) return
        cancelViewportFix = com.zzupocket.app.ui.web.WebViewVhFix.install(view)
        if (service != WhpuCampusService.SEAT && Uri.parse(url).host == "e.whpu.edu.cn") {
            view.evaluateJavascript(com.zzupocket.app.ui.web.PortalViewportFix.JS, null)
        }
    }
    val webView = WebView(windowContext).apply {
        webChromeClient = com.zzupocket.app.ui.web.SchoolWebChromeClient()
        setBackgroundColor(android.graphics.Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        if (service != WhpuCampusService.SEAT) {
            settings.userAgentString = campusMobileUserAgent(settings.userAgentString)
        }
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (service.allows(request.url.toString())) return false
                error = "该跳转暂不支持在应用内打开（${WhpuWebPolicy.navigationOrigin(request.url.toString())}）。可在浏览器中从官方入口继续。"
                loading = false
                return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                navigationRevision++
                cardReady = false
                cancelPendingFix()
                if (!service.allows(url)) {
                    view.stopLoading()
                    loading = false
                    error = "该页面暂不支持在应用内打开，请使用浏览器入口。"
                    return
                }
                loading = true
                error = null
            }
            override fun onPageFinished(view: WebView, url: String) {
                loading = false
                canGoBack = view.canGoBack()
                if (view.url == url) repairViewport(view, url)
                cardReady = error == null && view.url == url && WhpuCardWebHandoff.allows(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                navigationRevision++
                canGoBack = view.canGoBack()
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, sslError: android.net.http.SslError) {
                handler.cancel()
                loading = false
                error = "无法验证学校页面的安全连接，请稍后重试。"
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                if (request.isForMainFrame) {
                    loading = false
                    error = "页面加载失败，请检查网络后重试。"
                }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) {
                    loading = false
                    error = "学校服务返回 ${response.statusCode}，可返回入口或稍后重试。"
                }
            }
        }
        addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) { repairViewport(this@apply, url) }
            override fun onViewDetachedFromWindow(v: android.view.View) { cancelPendingFix() }
        })
        // Portal pages measure their viewport during startup. Start after Compose has sized the view.
        var started = false
        addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (!started && right > left && bottom > top) {
                started = true
                loadUrl(service.entry)
            }
        }
    }
    private fun beginNavigation() {
        navigationRevision++
        cardReady = false
    }
    fun retry() { beginNavigation(); webView.reload() }
    fun home() { beginNavigation(); webView.loadUrl(service.entry) }
    fun back() { beginNavigation(); webView.goBack() }
    fun consumeCardSaved() { cardSaved = false }
    fun syncCard() {
        if (cardSyncing || !cardReady) return
        cardSyncing = true
        cardMessage = null
        val generation = navigationRevision
        viewModelScope.launch {
            var revision = AppGraph.whpuCardSession.currentRevision()
            try {
                val credentials = readCardCredentials(generation)
                if (cleared || generation != navigationRevision) throw WhpuCardException.SessionChanged()
                AppGraph.whpuCardSession.accept(credentials.token, credentials.type, webView.settings.userAgentString,
                    expectedRevision = revision, isCurrent = { !cleared && generation == navigationRevision })
                revision = AppGraph.whpuCardSession.currentRevision()
                AppGraph.whpuCardRepository.refresh { !cleared && generation == navigationRevision }
                try { com.zzupocket.app.ui.widget.Widgets.refreshAll(appContext)
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { cardMessage = "已保存校园卡，桌面组件稍后更新。" }
                cardSaved = true
            } catch (e: CancellationException) { throw e
            } catch (e: WhpuCardException) {
                if (e is WhpuCardException.SessionExpired) AppGraph.whpuCardSession.clearIfCurrent(revision)
                cardMessage = e.message
            } catch (_: Exception) { cardMessage = "校园卡同步失败，请重试。本地缓存保留。"
            } finally { cardSyncing = false }
        }
    }
    internal suspend fun readCardCredentials(generation: Long = navigationRevision): WhpuCardWebHandoff.Credentials =
        suspendCancellableCoroutine { continuation ->
            if (cleared || generation != navigationRevision || !WhpuCardWebHandoff.allows(webView.url)) {
                continuation.resumeWithException(WhpuCardException.SessionChanged())
            } else webView.evaluateJavascript(WhpuCardWebHandoff.script) { value ->
                if (continuation.isActive) {
                    if (cleared || generation != navigationRevision || !WhpuCardWebHandoff.allows(webView.url)) {
                        continuation.resumeWithException(WhpuCardException.SessionChanged())
                    } else try { continuation.resume(WhpuCardWebHandoff.decodeCallback(value))
                    } catch (e: WhpuCardException) { continuation.resumeWithException(e) }
                }
            }
        }
    fun attach(context: Context): WebView {
        windowContext.baseContext = context
        (webView.parent as? ViewGroup)?.removeView(webView)
        return webView
    }
    fun detach(context: Context) {
        if (windowContext.baseContext === context) {
            cancelPendingFix()
            (webView.parent as? ViewGroup)?.removeView(webView)
            windowContext.baseContext = appContext
        }
    }
    override fun onCleared() {
        cleared = true
        cancelPendingFix()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
        windowContext.baseContext = appContext
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CampusServiceScreen(service: WhpuCampusService, onBack: () -> Unit, onCardSaved: (() -> Unit)? = null) {
    val context = LocalContext.current
    val model: CampusWebViewModel = viewModel(key = service.name, factory = viewModelFactory {
        initializer { CampusWebViewModel(context, service) }
    })
    var browserError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(model.cardSaved) {
        if (model.cardSaved) { model.consumeCardSaved(); onCardSaved?.invoke() }
    }
    BackHandler(model.canGoBack) { model.back() }
    DisposableEffect(model, context) {
        onDispose { model.detach(context) }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(service.title) }, navigationIcon = {
            TextButton(onClick = onBack) { Text("返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text(service.instruction, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onCardSaved != null) Button(onClick = model::syncCard, enabled = model.cardReady && !model.cardSyncing) {
                        Text(if (model.cardSyncing) "正在同步…" else "同步余额与账单")
                    }
                    TextButton(onClick = model::home) { Text("返回入口") }
                    TextButton(onClick = model::retry) { Text("重试") }
                    TextButton(onClick = { browserError = openCampusBrowser(context, service.entry) }) { Text("在浏览器打开") }
                }
                (browserError ?: model.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (onCardSaved != null) Text(model.cardMessage ?: "登录门户后选择“虚拟校园卡”，进入后点击同步。",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = {
                model.attach(context)
            })
        }
    }
}

internal fun openCampusBrowser(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    null
} catch (_: ActivityNotFoundException) { "没有可打开该页面的浏览器。" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryServicesScreen(onBack: () -> Unit, onSeat: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("图书馆") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("图书借阅", style = MaterialTheme.typography.titleMedium)
            Text("打开学校书目检索系统，使用图书馆账号登录后查看借阅情况。")
            Button(onClick = { error = openCampusBrowser(context, LIBRARY_ENTRY) }) { Text("在浏览器打开图书借阅") }
            HorizontalDivider()
            Text("座位预约", style = MaterialTheme.typography.titleMedium)
            Text("进入学校超星预约页面，选择校区、阅览室和使用时间。")
            Button(onClick = onSeat) { Text("打开座位预约") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
