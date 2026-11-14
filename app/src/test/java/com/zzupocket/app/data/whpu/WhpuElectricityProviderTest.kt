package com.zzupocket.app.data.whpu

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class WhpuElectricityProviderTest {
    private suspend fun session() = WhpuCardSession().apply { accept("synthetic-electricity", userAgent = "test Android Mobile") }
    private fun client(reply: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody()).build()
    }.build()
    private fun response(request: Request) = if (request.url.encodedPath == "/berserker-base/user") CardFixture.owner()
        else if ((request.body as FormBody).value(1) == "select") ElectricityFixture.directory() else ElectricityFixture.balance()

    @Test fun initialDirectoryUsesOnlyReadOnlyEndpointsAndMinimalAuthentication() = runTest {
        val requests = mutableListOf<Request>()
        val snapshot = WhpuElectricityProvider(session(), client { requests += it; 200 to response(it) }).directory()
        assertEquals(WhpuCardOwner("101"), snapshot.owner)
        assertEquals(ElectricityFixture.levels, snapshot.directory.levels)
        assertEquals(3, requests.size)
        assertEquals(listOf("GET", "POST", "GET"), requests.map { it.method })
        requests.forEach {
            assertEquals("ecardapp.whpu.edu.cn", it.url.host)
            assertEquals("https", it.url.scheme)
            assertEquals("bearer synthetic-electricity", it.header("synjones-auth"))
            assertEquals("test Android Mobile", it.header("User-Agent"))
            assertNull(it.header("Cookie")); assertNull(it.header("Authorization"))
            assertFalse(it.url.toString().contains("synthetic-electricity"))
        }
        val post = requests[1]
        assertEquals("/charge/feeitem/getThirdData", post.url.encodedPath)
        val form = post.body as FormBody
        assertEquals(mapOf("feeitemid" to "182", "type" to "select", "level" to "0"), fields(form))
    }

    @Test fun balancePreservesCompositeValuesAsFormFieldsAndChecksOwnerTwice() = runTest {
        val requests = mutableListOf<Request>()
        val snapshot = WhpuElectricityProvider(session(), client { requests += it; 200 to response(it) }).balance(ElectricityFixture.room)
        assertEquals(1234L, snapshot.balanceFen)
        assertEquals(ElectricityFixture.room, snapshot.room)
        val form = fields(requests.single { it.method == "POST" }.body as FormBody)
        assertEquals("IEC", form["type"]); assertEquals("4", form["level"])
        assertEquals("test-room&示例房间", form["room"])
        assertEquals(7, form.size)
        assertEquals(2, requests.count { it.url.encodedPath == "/berserker-base/user" })
    }

    @Test fun newAccountCannotQueryPreviouslySelectedRoom() = runTest {
        var requests = 0
        val provider = WhpuElectricityProvider(session(), client { requests++; 200 to CardFixture.owner(202) })
        assertTrue(runCatching { provider.balance(ElectricityFixture.room) }.exceptionOrNull() is WhpuElectricityException.SessionChanged)
        assertEquals(1, requests)
    }

    @Test fun directoryAlsoRejectsOldAccountSelectionBeforePosting() = runTest {
        var requests = 0
        val provider = WhpuElectricityProvider(session(), client { requests++; 200 to CardFixture.owner(202) })
        assertTrue(runCatching { provider.directory(steps = ElectricityFixture.room.steps.take(1), expectedOwner = WhpuCardOwner("101")) }
            .exceptionOrNull() is WhpuElectricityException.SessionChanged)
        assertEquals(1, requests)
    }

    @Test fun ownerChangeAfterSuccessfulBalanceRejectsSnapshot() = runTest {
        var profiles = 0
        val provider = WhpuElectricityProvider(session(), client {
            200 to if (it.method == "GET") CardFixture.owner(if (++profiles == 1) 101 else 202) else ElectricityFixture.balance()
        })
        assertTrue(runCatching { provider.balance(ElectricityFixture.room) }.exceptionOrNull() is WhpuElectricityException.SessionChanged)
    }

    @Test fun sessionChangeDuringQueryRejectsResponse() = runTest {
        val session = session()
        val provider = WhpuElectricityProvider(session, client {
            if (it.method == "POST") runBlocking { session.accept("other-synthetic", userAgent = "test Android Mobile") }
            200 to response(it)
        })
        assertTrue(runCatching { provider.balance(ElectricityFixture.room) }.exceptionOrNull() is WhpuElectricityException.SessionChanged)
    }

    @Test fun business401ClearsOnlyTheCapturedSession() = runTest {
        val session = session()
        val provider = WhpuElectricityProvider(session, client {
            if (it.method == "POST") 200 to """{"code":401,"success":false,"msg":"private"}""" else 200 to CardFixture.owner()
        })
        assertTrue(runCatching { provider.directory() }.exceptionOrNull() is WhpuElectricityException.SessionExpired)
        assertTrue(runCatching { session.capture() }.exceptionOrNull() is WhpuCardException.SessionExpired)
    }

    @Test fun oldUnauthorizedResponseCannotEraseANewerSession() = runTest {
        val session = session()
        val provider = WhpuElectricityProvider(session, client {
            runBlocking { session.accept("new-synthetic", userAgent = "test Android Mobile") }
            401 to "private"
        })
        assertTrue(runCatching { provider.directory() }.exceptionOrNull() is WhpuElectricityException.SessionExpired)
        assertEquals("bearer new-synthetic", session.capture().authorization)
    }

    @Test fun noSessionOrIncompleteRoomNeverSendsAQuery() = runTest {
        var requests = 0
        val transport = client { requests++; 200 to response(it) }
        assertTrue(runCatching { WhpuElectricityProvider(WhpuCardSession(), transport).directory() }
            .exceptionOrNull() is WhpuElectricityException.SessionExpired)
        assertTrue(runCatching { WhpuElectricityProvider(session(), transport).balance(ElectricityFixture.room.copy(steps = emptyList())) }
            .exceptionOrNull() is WhpuElectricityException.Parse)
        assertEquals(0, requests)
    }

    @Test fun httpErrorsAndRedirectsDoNotLeakBodiesOrFollowNavigation() = runTest {
        for (code in listOf(302, 403, 404, 500)) {
            var requests = 0
            val failure = runCatching { WhpuElectricityProvider(session(), client { requests++; code to "private-school-page" }).directory() }.exceptionOrNull()
            if (code == 302) assertTrue(failure is WhpuElectricityException.SessionExpired)
            else assertEquals(code, (failure as WhpuElectricityException.Http).code)
            assertEquals(1, requests)
            assertFalse(failure.toString().contains("private-school-page"))
        }
    }

    @Test fun networkFailureIsNotAnEmptyBalance() = runTest {
        val failure = runCatching { WhpuElectricityProvider(session(), client { throw IOException("private-network") }).directory() }.exceptionOrNull()
        assertTrue(failure is WhpuElectricityException.Network)
        assertFalse(failure.toString().contains("private-network"))
    }

    private fun fields(body: FormBody) = (0 until body.size).associate { body.name(it) to body.value(it) }
}
