package com.zzupocket.app.data.whpu

import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class WhpuGradeProviderTest {
    private val term = WhpuTerm(2025, "3")
    private val identity = """{"xsxx":{"XH_ID":"synthetic-owner","XH":"synthetic-student"}}"""
    private fun page(n: Int, total: Int = 2, pages: Int = 2, items: String =
        """{"kcmc":"示例课程$n","cj":"优秀","xh":"synthetic-student","xnm":"2025","xqm":"3"}""") =
        """{"currentPage":$n,"totalPage":$pages,"totalResult":$total,"items":[$items]}"""
    private fun session() = WhpuSessionManager().apply {
        acceptBrowserSession("JSESSIONID=synthetic-schedule", "synthetic-webview", "JSESSIONID=synthetic-grades")
    }
    private fun provider(respond: (Request) -> Pair<Int, String>): WhpuGradeProvider {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, text) = respond(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").body(text.toResponseBody()).build()
        }.build()
        return WhpuGradeProvider(session(), client = client)
    }
    @Test fun postsObservedFormAndFetchesEveryPageWithFrozenIdentity() = runTest {
        val requested = mutableListOf<Int>()
        val result = provider { request ->
            assertEquals("POST", request.method)
            assertEquals("synthetic-webview", request.header("User-Agent"))
            val form = request.body as FormBody
            val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
            assertEquals("2025", fields["xnm"]); assertEquals("3", fields["xqm"])
            if (request.url.encodedPath.startsWith("/kbcx")) 200 to identity else {
                assertEquals("query", request.url.queryParameter("doType"))
                assertEquals("N305005", request.url.queryParameter("gnmkdm"))
                assertEquals("15", fields["queryModel.showCount"])
                assertEquals(" ", fields["queryModel.sortName"])
                assertEquals("asc", fields["queryModel.sortOrder"])
                assertEquals("false", fields["_search"]); assertEquals("2", fields["time"])
                listOf("sfzgcj", "kcbj", "pkey").forEach { assertEquals("", fields[it]) }
                assertNotNull(fields["nd"]?.toLongOrNull())
                assertFalse(fields.containsKey("xh"))
                val n = fields.getValue("queryModel.currentPage").toInt()
                requested += n
                200 to page(n)
            }
        }.fetch(term)
        assertEquals(listOf(1, 2), requested)
        assertEquals("synthetic-owner", result.account.value)
        assertEquals(listOf("示例课程1", "示例课程2"), result.grades.map { it.courseName })
    }
    @Test fun emptyTermStillHasVerifiedOwner() = runTest {
        val result = provider { r -> 200 to if (r.url.encodedPath.startsWith("/kbcx")) identity else page(1, 0, 0, "") }.fetch(term)
        assertTrue(result.grades.isEmpty())
        assertEquals("synthetic-owner", result.account.value)
    }
    @Test fun missingIdentityStopsBeforeGradeRequest() = runTest {
        var calls = 0
        val error = runCatching { provider { calls++; 200 to "{}" }.fetch(term) }.exceptionOrNull()
        assertTrue(error is WhpuAcademicException.MissingAccount)
        assertEquals(1, calls)
    }
    @Test fun wrongStudentIsNotAcceptedUnderVerifiedOwner() = runTest {
        val error = runCatching { provider { r ->
            200 to if (r.url.encodedPath.startsWith("/kbcx")) identity else page(1).replace("synthetic-student", "synthetic-other")
        }.fetch(term) }.exceptionOrNull()
        assertTrue(error is WhpuAcademicException.Parse)
    }
    @Test fun expiredSessionOnLaterPageFailsTheWholeRequest() = runTest {
        val error = runCatching { provider { r ->
            when {
                r.url.encodedPath.startsWith("/kbcx") -> 200 to identity
                (r.body as FormBody).let { f -> (0 until f.size).any { f.name(it) == "queryModel.currentPage" && f.value(it) == "2" } } ->
                    200 to "<html><input type='password'></html>"
                else -> 200 to page(1)
            }
        }.fetch(term) }.exceptionOrNull()
        assertTrue(error is WhpuAcademicException.SessionExpired)
    }
    @Test fun requestCookiesAreScopedSeparatelyForBothEndpoints() {
        val jar = session()
        val schedule = HttpUrl.Builder().scheme("https").host("jwglxt.whpu.edu.cn").addPathSegment("kbcx").addPathSegment("test").build()
        val grades = schedule.newBuilder().encodedPath("/cjcx/test").build()
        assertEquals("synthetic-schedule", jar.loadForRequest(schedule).single().value)
        assertEquals("synthetic-grades", jar.loadForRequest(grades).single().value)
        assertTrue(jar.loadForRequest(grades.newBuilder().encodedPath("/xtgl/test").build()).isEmpty())
        assertTrue(jar.loadForRequest(grades.newBuilder().host("cas.whpu.edu.cn").build()).isEmpty())
        assertTrue(jar.loadForRequest(grades.newBuilder().scheme("http").build()).isEmpty())
    }
    @Test fun acceptingNewScheduleSessionDropsPreviousGradeCookie() {
        val jar = session()
        jar.acceptBrowserSession("JSESSIONID=synthetic-new", "new")
        assertTrue(jar.loadForRequest(HttpUrl.Builder().scheme("https").host("jwglxt.whpu.edu.cn").encodedPath("/cjcx/test").build()).isEmpty())
    }
}
