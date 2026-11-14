package com.zzupocket.app.ui.campus

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.MainActivity
import com.zzupocket.app.data.whpu.WhpuCampusService
import com.zzupocket.app.data.whpu.WhpuCardException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** An isolated synthetic WebView; network loads are blocked and no provider is invoked. */
@RunWith(AndroidJUnit4::class)
class WhpuCardHandoffTest {
    @Test fun officialOriginCallbackReadsInMemoryAndRejectsStaleNavigation() = verify(WhpuCampusService.CARD)
    @Test fun electricityReusesOriginAndNavigationCheckedHandoff() = verify(WhpuCampusService.ELECTRICITY)

    private fun verify(service: WhpuCampusService) = runBlocking {
        val store = ViewModelStore()
        var model: CampusWebViewModel? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    val current = CampusWebViewModel(activity, service)
                    model = current; store.put("fixture", current)
                    current.webView.settings.blockNetworkLoads = true
                    assertTrue(current.webView.webChromeClient!!.onConsoleMessage(android.webkit.ConsoleMessage(
                        "synthetic-sensitive-console", "synthetic", 1, android.webkit.ConsoleMessage.MessageLevel.LOG)))
                    activity.addContentView(current.attach(activity), ViewGroup.LayoutParams(600, 800))
                }
                var initialLayout = false
                for (attempt in 1..100) {
                    scenario.onActivity {
                        initialLayout = model!!.webView.width > 0 && model!!.webView.height > 0 &&
                            model!!.webView.url != null && !model!!.loading
                    }
                    if (initialLayout) break
                    kotlinx.coroutines.delay(100)
                }
                assertTrue("Initial layout did not finish", initialLayout)
                scenario.onActivity {
                    model!!.webView.stopLoading()
                    val view = model!!.webView
                    val original = view.webViewClient
                    val html = """<html><head><title>card-handoff-fixture</title></head><body>synthetic
                        <script>sessionStorage.setItem('access_token','synthetic-token');
                        sessionStorage.setItem('token_type','bearer');</script></body></html>"""
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            val body = if (request.url.toString() == "https://ecardapp.whpu.edu.cn/plat/fixture") html else ""
                            return WebResourceResponse("text/html", "UTF-8", body.byteInputStream())
                        }
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) = original.onPageStarted(view, url, favicon)
                        override fun onPageFinished(view: WebView, url: String) = original.onPageFinished(view, url)
                        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = original.doUpdateVisitedHistory(view, url, isReload)
                    }
                    // Every resource is intercepted above; none reaches the network.
                    view.settings.blockNetworkLoads = false
                    view.loadUrl("https://ecardapp.whpu.edu.cn/plat/fixture")
                }
                var loaded = false
                for (attempt in 1..50) {
                    scenario.onActivity { loaded = model!!.webView.title == "card-handoff-fixture" && model!!.cardReady }
                    if (loaded) break
                    kotlinx.coroutines.delay(100)
                }
                var status = ""
                scenario.onActivity {
                    status = "titleMatches=${model!!.webView.title == "card-handoff-fixture"}, ready=${model!!.cardReady}, originAllowed=${com.zzupocket.app.data.whpu.WhpuCardWebHandoff.allows(model!!.webView.url)}, error=${model!!.error}"
                }
                assertTrue(status, loaded)
                val credentials = withContext(Dispatchers.Main) { model!!.readCardCredentials() }
                assertEquals("synthetic-token", credentials.token)
                assertEquals("bearer", credentials.type)
                val stale = runCatching { withContext(Dispatchers.Main) { model!!.readCardCredentials(-1) } }
                assertTrue(stale.exceptionOrNull() is WhpuCardException.SessionChanged)
                scenario.onActivity { model!!.webView.evaluateJavascript("sessionStorage.removeItem('access_token')", null) }
                val missing = runCatching { withContext(Dispatchers.Main) { model!!.readCardCredentials() } }
                assertTrue(missing.exceptionOrNull() is WhpuCardException.SessionExpired)
            } finally {
                scenario.onActivity { store.clear() }
            }
        }
    }
}
