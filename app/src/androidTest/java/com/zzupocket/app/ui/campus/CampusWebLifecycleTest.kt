package com.zzupocket.app.ui.campus

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.MainActivity
import com.zzupocket.app.ui.nav.AppRoutes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.zzupocket.app.ui.web.WebViewVhFix
import com.zzupocket.app.ui.web.PortalViewportFix

/** Synthetic page only: no account data, booking or Cookie changes. */
@RunWith(AndroidJUnit4::class)
class CampusWebLifecycleTest {
    @Test fun recreationPreservesTheSamePageWithoutReloadingEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(AppRoutes.EXTRA_ROUTE, AppRoutes.SEAT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            var original: WebView? = null
            await {
                var ready = false
                scenario.onActivity {
                    original = webView(it.window.decorView)
                    ready = original?.let { view -> view.width > 0 && view.height > 0 && view.url != null } == true
                }
                ready
            }
            scenario.onActivity {
                val view = original!!
                view.stopLoading()
                val delegate = view.webViewClient
                val fixtureUrl = "https://office.chaoxing.com/front/third/apps/seat/lifecycle-fixture"
                val html = "<html><head><title>lifecycle-fixture</title><style>#campus-fixture{height:100vh;overflow:hidden}</style></head><body><div id='campus-fixture'>synthetic</div></body></html>"
                // loadDataWithBaseURL can report a non-origin URL on API 31. Exercise the
                // production origin/navigation checks with a real URL and local-only responses.
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                        WebResourceResponse("text/html", "UTF-8", (if (request.url.toString() == fixtureUrl) html else "").byteInputStream())
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) = delegate.onPageStarted(view, url, favicon)
                    override fun onPageFinished(view: WebView, url: String) = delegate.onPageFinished(view, url)
                    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = delegate.doUpdateVisitedHistory(view, url, isReload)
                }
                view.loadUrl(fixtureUrl)
            }
            await {
                var loaded = false
                scenario.onActivity { loaded = original!!.title == "lifecycle-fixture" }
                loaded
            }
            val contentHeight = AtomicInteger()
            await(onTimeout = {
                "Synthetic viewport: " + evaluate(scenario, original!!,
                    "JSON.stringify({fixture:!!document.getElementById('campus-fixture'),height:document.getElementById('campus-fixture')?.getBoundingClientRect().height,innerHeight:innerHeight,ready:document.readyState})")
            }) {
                scenario.onActivity {
                    original!!.evaluateJavascript("document.getElementById('campus-fixture').getBoundingClientRect().height") {
                        contentHeight.set(it.toDoubleOrNull()?.toInt() ?: 0)
                    }
                }
                contentHeight.get() > 0
            }
            scenario.recreate()
            await {
                var restored = false
                scenario.onActivity { restored = webView(it.window.decorView) != null }
                restored
            }
            scenario.onActivity {
                val restored = webView(it.window.decorView)!!
                assertSame(original, restored)
                assertEquals("lifecycle-fixture", restored.title)
                assertFalse(restored.settings.allowFileAccess)
                assertFalse(restored.settings.allowContentAccess)
            }
            contentHeight.set(0)
            await {
                scenario.onActivity {
                    original!!.evaluateJavascript("document.getElementById('campus-fixture').getBoundingClientRect().height") {
                        contentHeight.set(it.toDoubleOrNull()?.toInt() ?: 0)
                    }
                }
                contentHeight.get() > 0
            }
            assertEquals("true", evaluate(scenario, original!!,
                "getComputedStyle(document.getElementById('campus-fixture')).visibility === 'visible'"))
        }
    }

    @Test fun viewportFixSurvivesRepeatedInjectionAndResize() = withSyntheticWebView { scenario, view ->
        // Simulate only the broken CSS-vh probe, independently of installed WebView version.
        evaluate(scenario, view, """
            window.fixtureHeight = 800;
            Object.defineProperty(window, 'innerHeight', {configurable:true,get:()=>window.fixtureHeight});
            var measure = Element.prototype.getBoundingClientRect;
            Element.prototype.getBoundingClientRect = function() {
                if (this.style && this.style.left === '-9999px') return {height:0};
                return measure.call(this);
            };
        """)
        repeat(2) { evaluate(scenario, view, WebViewVhFix.JS) }
        assertEquals("800", evaluate(scenario, view, "document.getElementById('fixture').getBoundingClientRect().height"))
        assertEquals("800", evaluate(scenario, view, "document.getElementById('inline-fixture').getBoundingClientRect().height"))
        evaluate(scenario, view, "window.fixtureHeight = 400")
        evaluate(scenario, view, WebViewVhFix.JS)
        assertEquals("400", evaluate(scenario, view, "document.getElementById('fixture').getBoundingClientRect().height"))
        assertEquals("400", evaluate(scenario, view, "document.getElementById('inline-fixture').getBoundingClientRect().height"))
        // A later page update wins over a previously rewritten inline property.
        evaluate(scenario, view, "document.getElementById('inline-fixture').style.height='25px'")
        evaluate(scenario, view, WebViewVhFix.JS)
        assertEquals("25", evaluate(scenario, view, "document.getElementById('inline-fixture').getBoundingClientRect().height"))
    }

    @Test fun positionedToolbarDoesNotPushTheLoginFormOutsideItsPanel() = withSyntheticWebView { scenario, view ->
        evaluate(scenario, view, """
            document.body.innerHTML = '<div id="root" style="position:relative;height:0"><div id="panel" style="position:absolute;height:200px;overflow:hidden"><div id="toolbar" style="position:relative;height:0"><b style="position:absolute;height:20px">Help</b></div><div id="form" style="height:100px">Synthetic login form</div></div></div>';
            var measure = Element.prototype.getBoundingClientRect;
            Element.prototype.getBoundingClientRect = function() {
                if (this.style && this.style.left === '-9999px') return {height:0};
                return measure.call(this);
            };
        """)
        repeat(2) { evaluate(scenario, view, WebViewVhFix.JS) }
        assertEquals("true", evaluate(scenario, view, """
            document.getElementById('root').getBoundingClientRect().height > 0 &&
            document.getElementById('toolbar').getBoundingClientRect().height === 0 &&
            document.getElementById('form').getBoundingClientRect().bottom <= document.getElementById('panel').getBoundingClientRect().bottom
        """))
    }

    @Test fun cancelledRepairDoesNotRunOnThePage() = withSyntheticWebView { scenario, view ->
        scenario.onActivity {
            val cancel = WebViewVhFix.install(view)
            cancel()
        }
        Thread.sleep(500) // Past the first scheduled injection.
        assertEquals("true", evaluate(scenario, view, "typeof window.__zzpVhFix === 'undefined'"))
    }

    @Test fun normalViewportIsNotRewritten() = withSyntheticWebView { scenario, view ->
        evaluate(scenario, view, """
            var measure = Element.prototype.getBoundingClientRect;
            Element.prototype.getBoundingClientRect = function() {
                if (this.style && this.style.left === '-9999px') return {height:800};
                return measure.call(this);
            };
        """)
        evaluate(scenario, view, WebViewVhFix.JS)
        evaluate(scenario, view, PortalViewportFix.JS)
        assertEquals("true", evaluate(scenario, view,
            "document.getElementById('zzp-vhfix') === null && document.getElementById('inline-fixture').style.height === '100vh' && document.body.style.height === ''"))
    }

    @Test fun portalRootRepairRequiresCollapsedViewportAndTracksResize() = withSyntheticWebView { scenario, view ->
        evaluate(scenario, view, """
            document.body.innerHTML = '<div id="root"></div>';
            window.fixtureHeight = 800;
            Object.defineProperty(window, 'innerHeight', {configurable:true,get:()=>window.fixtureHeight});
            var measure = Element.prototype.getBoundingClientRect;
            Element.prototype.getBoundingClientRect = function() {
                if (this === document.body || this === document.documentElement || this.id === 'root' || (this.style && this.style.left === '-9999px')) return {height:0};
                return measure.call(this);
            };
        """)
        evaluate(scenario, view, PortalViewportFix.JS)
        assertEquals("true", evaluate(scenario, view, "document.body.style.height === '800px' && document.getElementById('root').style.height === '800px'"))
        evaluate(scenario, view, "window.fixtureHeight=400;window.dispatchEvent(new Event('resize'))")
        assertEquals("true", evaluate(scenario, view, "document.body.style.height === '400px' && document.getElementById('root').style.height === '400px'"))
    }

    private fun withSyntheticWebView(block: (ActivityScenario<MainActivity>, WebView) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
            lateinit var view: WebView
            scenario.onActivity { activity ->
                view = WebView(activity)
                view.settings.javaScriptEnabled = true
                activity.addContentView(view, ViewGroup.LayoutParams(600, 800))
                view.loadDataWithBaseURL("https://fixture.invalid/", "<html><head><title>viewport-fixture</title><style>#fixture{height:100vh}</style></head><body><div id='fixture'></div><div id='inline-fixture' style='height:100vh'></div></body></html>", "text/html", "UTF-8", null)
            }
            try {
                await {
                    var loaded = false
                    scenario.onActivity { loaded = view.title == "viewport-fixture" }
                    loaded
                }
                block(scenario, view)
            } finally {
                scenario.onActivity { (view.parent as? ViewGroup)?.removeView(view); view.destroy() }
            }
        }
    }

    private fun evaluate(scenario: ActivityScenario<MainActivity>, view: WebView, script: String): String {
        val result = AtomicReference<String?>()
        scenario.onActivity { view.evaluateJavascript(script) { result.set(it) } }
        await { result.get() != null }
        return result.get()!!
    }

    private fun webView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            webView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun await(onTimeout: () -> String = { "页面未在超时前就绪" }, predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail(onTimeout())
    }
}
