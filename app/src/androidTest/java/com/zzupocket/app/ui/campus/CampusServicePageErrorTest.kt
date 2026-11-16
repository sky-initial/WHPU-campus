package com.zzupocket.app.ui.campus

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.MainActivity
import com.zzupocket.app.data.whpu.WhpuCampusService
import com.zzupocket.app.data.whpu.WhpuCardException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Every WebView request receives a local response; no school traffic or provider/cache writes. */
@RunWith(AndroidJUnit4::class)
class CampusServicePageErrorTest {
    @Test fun mainDocument503KeepsErrorAndHandoffDisabledUntilSuccessfulRetry() {
        withPage(Failure.MAIN_HTTP) { scenario, model, responses ->
            await(responses) { responses.finished.get() > 0 && responses.mainErrors.get() > 0 }
            scenario.onActivity {
                assertEquals("Callbacks: ${responses.events}", HTTP_ERROR, model.error)
                assertFalse(model.loading)
                assertFalse("A failed main document must not enable account handoff", model.cardReady)
            }

            val finishedBeforeRetry = responses.finished.get()
            responses.failing.set(false)
            scenario.onActivity {
                model.retry()
                assertNull(model.error)
                assertFalse(model.cardReady)
            }
            await(responses) { responses.finished.get() > finishedBeforeRetry }
            scenario.onActivity {
                assertEquals("synthetic-ready", model.webView.title)
                assertNull(model.error)
                assertFalse(model.loading)
                assertTrue(model.cardReady)
            }
            assertTrue(responses.mainRequests.get() >= 2)
        }
    }

    @Test fun backAndHomeClearTheFailedDocumentBeforeLoadingTheEntry() {
        withPage(Failure.MAIN_HTTP) { scenario, model, responses ->
            fun awaitEntry() = await(responses) {
                var ready = false
                scenario.onActivity {
                    ready = model.webView.title == "synthetic-entry" && !model.loading
                }
                ready
            }
            await(responses) { responses.finished.get() > 0 && responses.mainErrors.get() > 0 }
            scenario.onActivity {
                assertEquals(HTTP_ERROR, model.error)
                assertTrue(model.canGoBack)
                model.back()
                assertNull(model.error)
                assertFalse(model.cardReady)
            }
            awaitEntry()
            val finishedBeforeReload = responses.finished.get()
            scenario.onActivity {
                assertNull(model.error)
                model.webView.loadUrl(FIXTURE_URL)
            }
            await(responses) {
                responses.finished.get() > finishedBeforeReload && responses.mainErrors.get() > 1
            }
            scenario.onActivity {
                assertEquals(HTTP_ERROR, model.error)
                model.home()
                assertNull(model.error)
                assertFalse(model.cardReady)
            }
            awaitEntry()
            scenario.onActivity {
                assertNull(model.error)
                assertFalse(model.cardReady) // The portal itself is not a card handoff page.
            }
        }
    }

    @Test fun mainDocument503WithFragmentKeepsErrorUntilSuccessfulRetry() {
        val routeUrl = "$FIXTURE_URL?fixture=route#/summary"
        withPage(Failure.MAIN_HTTP, fixtureUrl = routeUrl) { scenario, model, responses ->
            await(responses) { responses.finished.get() > 0 && responses.mainErrors.get() > 0 }
            // These are fixed synthetic URLs, never the user's current page or auth URL.
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nSynthetic document URLs: ${responses.documentEvents}\n")
            })
            scenario.onActivity {
                assertEquals(HTTP_ERROR, model.error)
                assertFalse(model.loading)
                assertFalse(model.cardReady)
            }
            val finishedBeforeRetry = responses.finished.get()
            responses.failing.set(false)
            scenario.onActivity { model.retry() }
            await(responses) { responses.finished.get() > finishedBeforeRetry }
            scenario.onActivity {
                assertEquals("synthetic-ready", model.webView.title)
                assertNull(model.error)
                assertTrue(model.cardReady)
            }
        }
    }

    @Test fun subresource503DoesNotDisableSuccessfulElectricityHandoff() {
        withPage(Failure.SUBRESOURCE_HTTP, WhpuCampusService.ELECTRICITY) { scenario, model, responses ->
            await(responses) { responses.finished.get() > 0 && responses.resourceErrors.get() > 0 }
            scenario.onActivity {
                assertEquals("synthetic-ready", model.webView.title)
                assertNull(model.error)
                assertFalse(model.loading)
                assertTrue(model.cardReady)
            }
            assertEquals(0, responses.mainErrors.get())
        }
    }

    @Test fun hashNavigationAndBackKeepHandoffReadyWithoutReloadingDocument() {
        withPage(Failure.NONE) { scenario, model, responses ->
            await(responses) { responses.finished.get() > 0 }
            val requestsBeforeHash = responses.allMainRequests.get()
            val historyBeforeHash = responses.historyUpdates.get()
            scenario.onActivity {
                assertTrue(model.cardReady)
                // Use an actual link gesture so Chromium retains a user-initiated history
                // entry; a script-only location.hash change may be skipped by goBack().
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 50 else 0,
                        action, 60f, 60f, 0)
                    model.webView.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            await(responses) {
                var navigated = false
                scenario.onActivity {
                    responses.pageState = "url=${model.webView.url}, ready=${model.cardReady}, loading=${model.loading}"
                    navigated = model.webView.url == "$FIXTURE_URL#/details"
                }
                navigated && responses.historyUpdates.get() > historyBeforeHash
            }
            assertEquals(requestsBeforeHash, responses.allMainRequests.get())
            val historyBeforeBack = responses.historyUpdates.get()
            scenario.onActivity {
                assertNull(model.error)
                assertTrue(model.canGoBack)
                model.back()
            }
            await(responses) {
                var ready = false
                scenario.onActivity {
                    responses.pageState = "url=${model.webView.url}, ready=${model.cardReady}, loading=${model.loading}"
                    ready = model.webView.url == FIXTURE_URL && model.cardReady && !model.loading
                }
                ready && responses.historyUpdates.get() > historyBeforeBack
            }
            scenario.onActivity { assertNull(model.error) }
            assertEquals("Hash navigation must not reload the main document", requestsBeforeHash,
                responses.allMainRequests.get())
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nSynthetic hash callbacks: ${responses.events}; URLs: ${responses.documentEvents}\n")
            })
        }
    }

    @Test fun leavingActivityClearsPageModelAndRejectsLateAccountHandoff() {
        withPage(Failure.NONE) { scenario, model, responses ->
            await(responses) { responses.finished.get() > 0 }
            var modelJob: Job? = null
            scenario.onActivity {
                assertTrue(model.cardReady)
                assertNotNull(model.webView.parent)
                modelJob = model.viewModelScope.coroutineContext[Job]
            }
            // This destroys the Activity and its ViewModelStore, rather than calling the
            // viewport cancellation function or ViewModel.onCleared directly.
            scenario.close()
            assertTrue(requireNotNull(modelJob).isCancelled)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertNull(model.webView.parent)
            }
            val result = runBlocking {
                runCatching { withContext(Dispatchers.Main) { model.readCardCredentials() } }
            }
            assertTrue(result.exceptionOrNull() is WhpuCardException.SessionChanged)
        }
    }

    private fun withPage(
        failure: Failure,
        service: WhpuCampusService = WhpuCampusService.CARD,
        fixtureUrl: String = FIXTURE_URL,
        check: (ActivityScenario<MainActivity>, CampusWebViewModel, LocalResponses) -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: CampusWebViewModel
            lateinit var responses: LocalResponses
            scenario.onActivity { activity ->
                model = CampusWebViewModel(activity, service)
                activity.viewModelStore.put("campus-error-fixture", model)
                responses = LocalResponses(model.webView.webViewClient, failure, fixtureUrl, service.entry)
                // The model loads its entry only after layout. Install interception first,
                // including for the portal entry, redirects and all subresources.
                model.webView.webViewClient = responses
                activity.addContentView(model.attach(activity), ViewGroup.LayoutParams(600, 800))
            }
            await(responses) {
                var ready = false
                scenario.onActivity { ready = model.webView.title == "synthetic-entry" && !model.loading }
                ready
            }
            scenario.onActivity { model.webView.loadUrl(fixtureUrl) }
            try {
                check(scenario, model, responses)
            } catch (error: AssertionError) {
                throw AssertionError("Synthetic callbacks: ${responses.events}; document URLs: ${responses.documentEvents}; state: ${responses.pageState}", error)
            }
        }
    }

    private enum class Failure { NONE, MAIN_HTTP, SUBRESOURCE_HTTP }

    private class LocalResponses(
        private val delegate: WebViewClient,
        private val failure: Failure,
        fixtureUrl: String,
        private val entryUrl: String,
    ) : WebViewClient() {
        private val documentUrl = fixtureUrl.substringBefore('#')
        val failing = AtomicBoolean(true)
        val mainRequests = AtomicInteger()
        val allMainRequests = AtomicInteger()
        val mainErrors = AtomicInteger()
        val resourceErrors = AtomicInteger()
        val finished = AtomicInteger()
        val historyUpdates = AtomicInteger()
        val events = CopyOnWriteArrayList<String>()
        val documentEvents = CopyOnWriteArrayList<String>()
        @Volatile var pageState = ""

        private fun isFixtureDocument(url: String) = url.substringBefore('#') == documentUrl

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val fixture = isFixtureDocument(request.url.toString())
            if (request.isForMainFrame) {
                allMainRequests.incrementAndGet()
                if (request.url.toString() == entryUrl) documentEvents += "entry-request=${request.url}"
            }
            if (fixture && request.isForMainFrame) {
                documentEvents += "request=${request.url}"
                mainRequests.incrementAndGet()
            }
            val unavailable = failing.get() && when (failure) {
                Failure.MAIN_HTTP -> fixture && request.isForMainFrame
                Failure.SUBRESOURCE_HTTP -> request.url.path == "/synthetic-resource.png"
                Failure.NONE -> false
            }
            val body = if (request.isForMainFrame) {
                val title = if (fixture) "synthetic-ready" else "synthetic-entry"
                val resource = if (fixture && failure == Failure.SUBRESOURCE_HTTP) {
                    "<img src='/synthetic-resource.png'>"
                } else ""
                val link = if (fixture && failure == Failure.NONE) {
                    "<a href='#/details' style='display:block;width:200px;height:100px'>Synthetic details</a>"
                } else ""
                "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                    "<title>$title</title></head><body>${link}Local page$resource</body></html>"
            } else ""
            return WebResourceResponse("text/html", "UTF-8", if (unavailable) 503 else 200,
                if (unavailable) "Synthetic unavailable" else "OK",
                mapOf("Cache-Control" to "no-store"), body.byteInputStream())
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
            delegate.shouldOverrideUrlLoading(view, request)

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (isFixtureDocument(url)) {
                events += "started"
                documentEvents += "started=$url"
            } else if (url == entryUrl) {
                events += "entry-started"
                documentEvents += "entry-started=$url"
            }
            delegate.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView, url: String) {
            delegate.onPageFinished(view, url)
            if (isFixtureDocument(url)) {
                events += "finished"
                documentEvents += "finished=$url"
                finished.incrementAndGet()
            } else if (url == entryUrl) {
                events += "entry-finished"
                documentEvents += "entry-finished=$url"
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            delegate.doUpdateVisitedHistory(view, url, isReload)
            if (isFixtureDocument(url)) {
                events += "history:reload=$isReload"
                documentEvents += "history=$url"
                historyUpdates.incrementAndGet()
            } else if (url == entryUrl) {
                events += "entry-history:reload=$isReload"
                documentEvents += "entry-history=$url"
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            events += "http:${request.isForMainFrame}:${response.statusCode}"
            if (request.isForMainFrame && isFixtureDocument(request.url.toString())) {
                documentEvents += "http=${request.url}"
            }
            delegate.onReceivedHttpError(view, request, response)
            if (request.isForMainFrame) mainErrors.incrementAndGet() else resourceErrors.incrementAndGet()
        }
    }

    private fun await(responses: LocalResponses, predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail("Local page did not settle; callbacks: ${responses.events}; document URLs: ${responses.documentEvents}; state: ${responses.pageState}")
    }

    private companion object {
        const val FIXTURE_URL = "https://ecardapp.whpu.edu.cn/plat/campus-error-fixture"
        const val HTTP_ERROR = "学校服务返回 503，可返回入口或稍后重试。"
    }
}
