package com.zzupocket.app.data.whpu

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhpuAcademicProviderTest {
    @Test fun requestUsesWebViewsUserAgentWithoutAddingCredentialsToUrl() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("test-webview", chain.request().header("User-Agent"))
            assertEquals(setOf("gnmkdm"), chain.request().url.queryParameterNames)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("test").body("""{"kbList":[]}""".toResponseBody()).build()
        }.build()
        WhpuAcademicProvider(WhpuSessionManager(), client = client, userAgent = { "test-webview" })
            .schedule(WhpuTerm.YEAR_2026_FIRST)
    }
    private fun provider(code: Int, body: String, check: (okhttp3.Request) -> Unit = {}): WhpuAcademicProvider {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            check(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("test").body(body.toResponseBody()).build()
        }).build()
        return WhpuAcademicProvider(WhpuSessionManager(), client = client)
    }

    @Test fun sendsConfirmedForm() {
        val api = provider(200, """{"kbList":[]}""") { request ->
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            assertEquals("xnm=2026&xqm=3&kzlx=ck&xsdm=&kclbdm=&kclxdm=", buffer.readUtf8())
            assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
            assertEquals("application/x-www-form-urlencoded;charset=UTF-8", request.header("Content-Type"))
            assertEquals("N2151", request.url.queryParameter("gnmkdm"))
        }
        assertTrue(api.schedule(WhpuTerm.YEAR_2026_FIRST).courses.isEmpty())
    }

    @Test(expected = WhpuAcademicException.SessionExpired::class)
    fun htmlIsExpiredSession() { provider(200, "<html>登录</html>").schedule(WhpuTerm.YEAR_2026_FIRST) }

    @Test(expected = WhpuAcademicException.SessionExpired::class)
    fun unauthorizedIsExpiredSession() { provider(401, "").schedule(WhpuTerm.YEAR_2026_FIRST) }

    @Test(expected = WhpuAcademicException.Forbidden::class)
    fun forbiddenIsDistinct() { provider(403, "").schedule(WhpuTerm.YEAR_2026_FIRST) }

    @Test(expected = WhpuAcademicException.Http::class)
    fun serverErrorIsHttpFailure() { provider(500, "").schedule(WhpuTerm.YEAR_2026_FIRST) }

    @Test(expected = WhpuAcademicException.Network::class)
    fun networkErrorIsDistinct() {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { throw java.io.IOException("offline") }).build()
        WhpuAcademicProvider(WhpuSessionManager(), client = client).schedule(WhpuTerm.YEAR_2026_FIRST)
    }

    @Test(expected = WhpuAcademicException.Parse::class)
    fun corruptJsonIsInvalid() { provider(200, "{broken").schedule(WhpuTerm.YEAR_2026_FIRST) }

    @Test(expected = WhpuAcademicException.Parse::class)
    fun emptyBodyIsInvalid() { provider(200, "").schedule(WhpuTerm.YEAR_2026_FIRST) }

    @Test fun cookiesMatchDomainAndPathAndCanBeCleared() {
        val jar = WhpuSessionManager()
        val url = "https://jwglxt.whpu.edu.cn/kbcx/".toHttpUrl()
        val cookie = Cookie.Builder().name("session").value("mock").hostOnlyDomain(url.host)
            .path("/kbcx").secure().build()
        jar.saveFromResponse(url, listOf(cookie))
        assertEquals(1, jar.loadForRequest(url).size)
        assertTrue(jar.loadForRequest("http://jwglxt.whpu.edu.cn/kbcx/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://jwglxt.whpu.edu.cn/other".toHttpUrl()).isEmpty())
        jar.clear()
        assertTrue(jar.loadForRequest(url).isEmpty())
    }
}
