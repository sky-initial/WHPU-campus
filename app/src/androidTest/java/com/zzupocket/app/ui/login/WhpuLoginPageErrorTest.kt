package com.zzupocket.app.ui.login

import android.content.Context
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.MainActivity
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** All WebView requests receive local responses. No school traffic, login or cache writes. */
@RunWith(AndroidJUnit4::class)
class WhpuLoginPageErrorTest {
    @Test fun mainDocumentHttpErrorIsVisibleWhileCollapsedAndRetryRestoresSync() {
        withPage(Failure.HTTP) { scenario, view, _ ->
            await { view.finished.get() > 0 }
            awaitText(HTTP_ERROR)
            assertTrue(nodeValue(HTTP_ERROR) { it.isVisibleToUser } == true)
            assertFalse(hasText("同步到课表")) // The operation area starts collapsed.
            click("同步操作", exact = false)
            awaitText("同步到课表")
            assertFalse(controlEnabled("同步到课表"))

            view.failing.set(false)
            click("重试")
            await { view.finished.get() > 1 && !hasText(HTTP_ERROR) }
            await { controlEnabled("同步到课表") }
            scenario.onActivity { assertEquals("synthetic-ready", view.title) }
            assertTrue(view.mainRequests.get() >= 2)
        }
    }

    @Test fun mainDocumentNetworkFailureKeepsReturnAvailableInSignInOnlyMode() {
        withPage(Failure.NETWORK, signInOnly = true) { _, view, returned ->
            await { view.finished.get() > 0 }
            awaitText(NETWORK_ERROR)
            assertTrue(nodeValue(NETWORK_ERROR) { it.isVisibleToUser } == true)
            assertFalse(hasText("清除网页登录态 / 换账号"))
            click("返回成绩查询")
            await { returned.get() }
            assertFalse(hasText("会话已过期"))
        }
    }

    @Test fun subresourceHttpFailureDoesNotBlockTheSuccessfulMainDocument() {
        withPage(Failure.SUBRESOURCE_HTTP) { scenario, view, _ ->
            await { view.finished.get() > 0 && view.resourceErrors.get() > 0 }
            scenario.onActivity { assertEquals("synthetic-ready", view.title) }
            assertFalse(hasText(HTTP_ERROR))
            click("同步操作", exact = false)
            awaitText("同步到课表")
            assertTrue(controlEnabled("同步到课表"))
        }
    }

    private fun withPage(
        failure: Failure,
        signInOnly: Boolean = false,
        check: (ActivityScenario<MainActivity>, LocalWebView, AtomicBoolean) -> Unit,
    ) {
        val returned = AtomicBoolean(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val created = AtomicReference<LocalWebView?>()
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        WhpuLoginScreen(onBack = { returned.set(true) }, onImported = { returned.set(true) },
                            signInOnly = signInOnly, webViewFactory = { context ->
                                LocalWebView(context, failure).also { created.set(it) }
                            })
                    }
                }
            }
            await { created.get() != null }
            try { check(scenario, requireNotNull(created.get()), returned) }
            catch (error: AssertionError) {
                throw AssertionError("Synthetic callbacks: ${created.get()?.events}", error)
            }
        }
    }

    private enum class Failure { HTTP, NETWORK, SUBRESOURCE_HTTP }

    private class LocalWebView(context: Context, private val failure: Failure) : WebView(context) {
        val events = CopyOnWriteArrayList<String>()
        val failing = AtomicBoolean(true)
        val finished = AtomicInteger()
        val mainRequests = AtomicInteger()
        val resourceErrors = AtomicInteger()

        override fun setWebViewClient(delegate: WebViewClient) {
            super.setWebViewClient(object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    if (request.isForMainFrame) mainRequests.incrementAndGet()
                    if (failure == Failure.NETWORK && failing.get() && request.isForMainFrame) {
                        return WebResourceResponse("text/html", "UTF-8", object : InputStream() {
                            override fun read(): Int = throw IOException("Synthetic response stream failure")
                        })
                    }
                    val unavailable = failing.get() && (failure == Failure.HTTP && request.isForMainFrame ||
                        failure == Failure.SUBRESOURCE_HTTP && request.url.path == "/synthetic-resource.png")
                    val html = if (request.isForMainFrame) {
                        """<html><head><title>synthetic-ready</title></head><body>Local page
                            <img src="/synthetic-resource.png"></body></html>"""
                    } else ""
                    // Every URL, including assets and unexpected redirects, is intercepted.
                    return WebResourceResponse("text/html", "UTF-8", if (unavailable) 503 else 200,
                        if (unavailable) "Synthetic unavailable" else "OK", mapOf("Cache-Control" to "no-store"), html.byteInputStream())
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    delegate.shouldOverrideUrlLoading(view, request)

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    events += "started"
                    delegate.onPageStarted(view, url, favicon)

                }

                override fun onPageFinished(view: WebView, url: String) {
                    events += "finished"
                    delegate.onPageFinished(view, url)
                    finished.incrementAndGet()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    events += "network:${request.isForMainFrame}:${error.errorCode}"
                    delegate.onReceivedError(view, request, error)
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    events += "http:${request.isForMainFrame}:${response.statusCode}"
                    delegate.onReceivedHttpError(view, request, response)
                    if (!request.isForMainFrame) resourceErrors.incrementAndGet()
                }
            })
        }
    }

    private fun awaitText(text: String) = await { hasText(text) }
    private fun await(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail("Synthetic login page did not reach the expected state")
    }

    private fun hasText(text: String) = nodeValue(text) { true } == true

    private fun controlEnabled(text: String): Boolean = nodeValue(text, exact = true) { node ->
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var enabled = true
        try {
            repeat(6) {
                val item = current ?: return@repeat
                enabled = enabled && item.isEnabled
                if (item.isClickable || item.className == "android.widget.Button") return@nodeValue enabled
                current = item.parent
                item.recycle()
            }
            false
        } finally { current?.recycle() }
    } ?: false

    private fun click(text: String, exact: Boolean = true) {
        nodeValue(text, exact) { it.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) }
        await {
            nodeValue(text, exact) { node ->
                var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
                try {
                    repeat(6) {
                        val item = current ?: return@repeat
                        if (item.isEnabled && item.isClickable && item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return@nodeValue true
                        }
                        current = item.parent
                        item.recycle()
                    }
                    false
                } finally { current?.recycle() }
            } == true
        }
    }

    private fun <T> nodeValue(text: String, exact: Boolean = false, block: (AccessibilityNodeInfo) -> T): T? {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        val node = try { find(root, text, exact) } finally { root.recycle() } ?: return null
        return try { block(node) } finally { node.recycle() }
    }

    private fun find(node: AccessibilityNodeInfo, text: String, exact: Boolean): AccessibilityNodeInfo? {
        if (if (exact) node.text?.toString() == text else node.text?.contains(text) == true) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = try { find(child, text, exact) } finally { child.recycle() }
            if (match != null) return match
        }
        return null
    }

    companion object {
        private const val HTTP_ERROR = "学校页面返回 503，请稍后重试。已有缓存保留。"
        private const val NETWORK_ERROR = "学校页面暂时无法加载，请稍后重试。已有缓存保留。"
    }
}
