package com.zzupocket.app.data.whpu

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class WhpuCardProviderTest {
    private suspend fun session() = WhpuCardSession().apply { accept("synthetic-secret", userAgent = "synthetic-webview") }
    private fun body(request: Request): String = when (request.url.encodedPath) {
        "/berserker-base/user" -> CardFixture.owner()
        "/berserker-app/frontInfo" -> CardFixture.config()
        "/berserker-app/ykt/tsm/queryCard" -> CardFixture.cards()
        "/berserker-search/search/personal/turnover" -> CardFixture.page()
        else -> error("Unexpected endpoint")
    }
    private fun client(reply: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
            .message("fixture").body(body.toResponseBody()).build()
    }.build()

    @Test fun usesOnlyReadOnlySchoolEndpointsAndKeepsAuthorizationOutOfUrl() = runTest {
        val requests = mutableListOf<Request>()
        val session = session()
        val snapshot = WhpuCardProvider(session, client { requests += it; 200 to body(it) }).fetch()
        assertEquals(WhpuCardOwner("101"), snapshot.owner)
        assertEquals(1234L, snapshot.cards.single().balanceFen)
        assertEquals(-1200L, snapshot.transactions.single().amountFen)
        assertEquals(5, requests.size)
        requests.forEach {
            assertEquals("GET", it.method)
            assertEquals("https", it.url.scheme)
            assertEquals("ecardapp.whpu.edu.cn", it.url.host)
            assertEquals("bearer synthetic-secret", it.header("synjones-auth"))
            assertEquals("synthetic-webview", it.header("User-Agent"))
            assertEquals("h5", it.url.queryParameter("synAccessSource"))
            assertFalse(it.url.toString().contains("synthetic-secret"))
            assertNull(it.header("Cookie"))
        }
        val page = requests.single { it.url.encodedPath.endsWith("turnover") }
        assertEquals("1", page.url.queryParameter("current"))
        assertEquals("8", page.url.queryParameter("size"))
        session.check(snapshot.sessionRevision)
        session.clear()
        assertTrue(runCatching { session.check(snapshot.sessionRevision) }.exceptionOrNull() is WhpuCardException.SessionChanged)
    }
    @Test fun noSessionDoesNotSendAnyRequest() = runTest {
        var calls = 0
        val result = runCatching { WhpuCardProvider(WhpuCardSession(), client { calls++; 200 to body(it) }).fetch() }
        assertTrue(result.exceptionOrNull() is WhpuCardException.SessionExpired)
        assertEquals(0, calls)
    }
    @Test fun secondPageUnauthorizedDoesNotProduceSnapshot() = runTest {
        val firstRows = (1..8).joinToString(",") { CardFixture.row("order-$it") }
        val api = WhpuCardProvider(session(), client {
            when (it.url.queryParameter("current")) {
                "1" -> 200 to CardFixture.page(firstRows, pages = 2, total = 9)
                "2" -> 401 to "private-error-must-not-leak"
                else -> 200 to body(it)
            }
        })
        val failure = runCatching { api.fetch() }.exceptionOrNull()
        assertTrue(failure is WhpuCardException.SessionExpired)
        assertFalse(failure.toString().contains("private-error"))
    }
    @Test fun sessionSwitchDuringRequestRejectsOldResult() = runTest {
        val session = session()
        val api = WhpuCardProvider(session, client {
            if (it.url.encodedPath.endsWith("queryCard")) runBlocking { session.accept("other-synthetic", userAgent = "synthetic-webview") }
            200 to body(it)
        })
        assertTrue(runCatching { api.fetch() }.exceptionOrNull() is WhpuCardException.SessionChanged)
    }
    @Test fun ownerChangedAtEndRejectsCollectedSnapshot() = runTest {
        var profiles = 0
        val api = WhpuCardProvider(session(), client {
            if (it.url.encodedPath == "/berserker-base/user") 200 to CardFixture.owner(if (++profiles == 1) 101 else 202)
            else 200 to body(it)
        })
        assertTrue(runCatching { api.fetch() }.exceptionOrNull() is WhpuCardException.SessionChanged)
    }
    @Test fun oldUnauthorizedRequestDoesNotClearNewSession() = runTest {
        val session = session()
        val api = WhpuCardProvider(session, client {
            runBlocking { session.accept("new-synthetic", userAgent = "synthetic-webview") }
            401 to ""
        })
        assertTrue(runCatching { api.fetch() }.exceptionOrNull() is WhpuCardException.SessionExpired)
        assertEquals("bearer new-synthetic", session.capture().authorization)
    }
    @Test fun emptyCardResultDoesNotQueryUnownedTransactions() = runTest {
        var turnoverCalls = 0
        val snapshot = WhpuCardProvider(session(), client {
            if (it.url.encodedPath.endsWith("turnover")) turnoverCalls++
            200 to if (it.url.encodedPath.endsWith("queryCard")) CardFixture.cards("") else body(it)
        }).fetch()
        assertTrue(snapshot.cards.isEmpty())
        assertTrue(snapshot.transactions.isEmpty())
        assertEquals(0, turnoverCalls)
    }
    @Test fun serverPageSizeMustMatchRequestedSize() = runTest {
        val api = WhpuCardProvider(session(), client {
            200 to if (it.url.encodedPath.endsWith("turnover")) CardFixture.page(size = 1) else body(it)
        })
        assertTrue(runCatching { api.fetch() }.exceptionOrNull() is WhpuCardException.Parse)
    }
    @Test fun httpErrorsDoNotReturnServerBodyAndRedirectIsNotFollowed() = runTest {
        for (code in listOf(302, 403, 404, 500)) {
            var calls = 0
            val failure = runCatching { WhpuCardProvider(session(), client {
                calls++; code to "private-error-must-not-leak"
            }).fetch() }.exceptionOrNull()
            if (code == 302) assertTrue(failure is WhpuCardException.SessionExpired)
            else assertEquals(code, (failure as WhpuCardException.Http).code)
            assertEquals(1, calls)
            assertFalse(failure.toString().contains("private-error"))
        }
    }
    @Test fun malformedResponseAndNetworkFailureAreDistinct() = runTest {
        assertTrue(runCatching { WhpuCardProvider(session(), client { 200 to "<html>private-error</html>" }).fetch() }
            .exceptionOrNull() is WhpuCardException.Parse)
        val network = client { throw IOException("private-network-detail") }
        val failure = runCatching { WhpuCardProvider(session(), network).fetch() }.exceptionOrNull()
        assertTrue(failure is WhpuCardException.Network)
        assertFalse(failure!!.stackTraceToString().contains("private-network-detail"))
    }
    @Test fun sessionAndLeaseToStringDoNotExposeCredentials() = runTest {
        val session = session()
        assertFalse(session.toString().contains("synthetic-secret"))
        assertFalse(session.capture().toString().contains("synthetic-secret"))
        assertTrue(runCatching { session.accept("invalid\nvalue", userAgent = "synthetic-webview") }.isFailure)
    }
    @Test fun sessionChangeWaitsForGuardedCacheCommit() = runTest {
        val session = session()
        val revision = session.currentRevision()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        val commit = async { session.withCurrent(revision) { entered.complete(Unit); finish.await() } }
        entered.await()
        val clear = async { session.clear() }
        kotlinx.coroutines.yield()
        assertFalse(clear.isCompleted)
        finish.complete(Unit)
        commit.await(); clear.await()
        assertTrue(runCatching { session.check(revision) }.exceptionOrNull() is WhpuCardException.SessionChanged)
    }
    @Test fun lateHandoffCannotReplaceNewSession() = runTest {
        val session = session()
        val before = session.currentRevision()
        session.accept("new-synthetic", userAgent = "synthetic-webview")
        val result = runCatching { session.accept("old-synthetic", userAgent = "synthetic-webview", expectedRevision = before) }
        assertTrue(result.exceptionOrNull() is WhpuCardException.SessionChanged)
        assertEquals("bearer new-synthetic", session.capture().authorization)
    }
    @Test fun navigationIsCheckedAfterWaitingForSessionLock() = runTest {
        val session = session()
        val revision = session.currentRevision()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        val hold = async { session.withCurrent(revision) { entered.complete(Unit); finish.await() } }
        entered.await()
        var current = true
        val accept = async { runCatching {
            session.accept("late-synthetic", userAgent = "synthetic-webview", expectedRevision = revision, isCurrent = { current })
        } }
        kotlinx.coroutines.yield(); current = false; finish.complete(Unit); hold.await()
        assertTrue(accept.await().exceptionOrNull() is WhpuCardException.SessionChanged)
        assertEquals("bearer synthetic-secret", session.capture().authorization)
    }
    @Test fun cancellingFetchCancelsNetworkCall() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).addInterceptor { chain ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(CardFixture.owner().toResponseBody()).build()
        }.build()
        try {
            val job = async(Dispatchers.Default) { WhpuCardProvider(session(), client).fetch() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancel()
            job.join()
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        } finally { release.countDown() }
    }
}
