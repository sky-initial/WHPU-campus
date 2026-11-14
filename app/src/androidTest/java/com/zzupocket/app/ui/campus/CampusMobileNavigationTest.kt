package com.zzupocket.app.ui.campus

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zzupocket.app.MainActivity
import com.zzupocket.app.data.whpu.WhpuCampusService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap

/** Local responses only: verify the actual request UA and JS UA across school origins. */
@RunWith(AndroidJUnit4::class)
class CampusMobileNavigationTest {
    @Test fun cardPortalAndElectricityUseOneMobileIdentity() = verify(WhpuCampusService.CARD)
    @Test fun electricityEntryUsesOneMobileIdentity() = verify(WhpuCampusService.ELECTRICITY)

    private fun verify(service: WhpuCampusService) {
        val portal = service.entry
        val card = "https://ecardapp.whpu.edu.cn/plat/mobile-fixture"
        val electricity = "https://ecardapp.whpu.edu.cn/charge-app/mobile-fixture"
        val requestAgents = ConcurrentHashMap<String, String>()
        val store = ViewModelStore()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: CampusWebViewModel
            try {
                scenario.onActivity { activity ->
                    model = CampusWebViewModel(activity, service)
                    store.put("mobile-fixture", model)
                    val view = model.webView
                    val original = view.webViewClient
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            val url = request.url.toString()
                            if (request.isForMainFrame) requestAgents[url] = request.requestHeaders.entries
                                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value.orEmpty()
                            val content = when (url) {
                                portal -> page("portal", card)
                                card -> page("card", electricity)
                                electricity -> page("electricity", portal)
                                else -> ""
                            }
                            return WebResourceResponse("text/html", "UTF-8", content.byteInputStream())
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                            original.shouldOverrideUrlLoading(view, request)
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = original.onPageStarted(view, url, favicon)
                        override fun onPageFinished(view: WebView, url: String) = original.onPageFinished(view, url)
                        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) =
                            original.doUpdateVisitedHistory(view, url, isReload)
                    }
                    activity.addContentView(model.attach(activity), ViewGroup.LayoutParams(600, 800))
                }
                awaitTitle(scenario, { model }, "portal-mobile")
                tapNext(scenario) { model }
                awaitTitle(scenario, { model }, "card-mobile")
                tapNext(scenario) { model }
                awaitTitle(scenario, { model }, "electricity-mobile")
                val agent = requestAgents[portal]
                assertNotNull(agent)
                assertTrue(agent!!.contains("Android"))
                assertTrue(agent.contains("Mobile"))
                assertFalse(agent.contains("X11"))
                assertEquals(agent, requestAgents[card])
                assertEquals(agent, requestAgents[electricity])
                scenario.onActivity { model.back() }
                awaitTitle(scenario, { model }, "card-mobile")
                scenario.onActivity { model.home() }
                awaitTitle(scenario, { model }, "portal-mobile")
                scenario.onActivity { assertNull(model.error) }
            } finally {
                scenario.onActivity { store.clear() }
            }
        }
    }

    private fun page(name: String, next: String) = """
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
        <body><a id="next" href="$next" style="display:block;width:200px;height:100px">Next synthetic page</a><script>
        document.title='$name-'+(navigator.userAgent.includes('Android') && navigator.userAgent.includes('Mobile')?'mobile':'desktop');
        </script></body></html>
    """.trimIndent()

    private fun tapNext(scenario: ActivityScenario<MainActivity>, model: () -> CampusWebViewModel) {
        // Native touch gives the navigation a user gesture, as on the actual school page.
        scenario.onActivity {
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 50 else 0, action, 60f, 60f, 0)
                model().webView.dispatchTouchEvent(event)
                event.recycle()
            }
        }
    }

    private fun awaitTitle(scenario: ActivityScenario<MainActivity>, model: () -> CampusWebViewModel, expected: String) {
        repeat(100) {
            var ready = false
            scenario.onActivity { ready = model().webView.title == expected && !model().loading }
            if (ready) return
            Thread.sleep(100)
        }
        var observed = ""
        scenario.onActivity {
            val history = model().webView.copyBackForwardList()
            observed = "title=${model().webView.title}, history=${history.currentIndex}/${history.size}, loading=${model().loading}"
        }
        fail("Synthetic page did not reach $expected ($observed)")
    }
}
