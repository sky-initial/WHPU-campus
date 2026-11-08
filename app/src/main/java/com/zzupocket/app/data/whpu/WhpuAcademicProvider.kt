package com.zzupocket.app.data.whpu

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class WhpuTerm(val year: Int, val code: String) {
    val semesterId: Int get() = 1_000_000 + year * 10 + if (code == "3") 1 else 2
    companion object {
        val YEAR_2026_FIRST = WhpuTerm(2026, "3")
        fun fromSemesterId(id: Int): WhpuTerm? {
            val value = id - 1_000_000
            val year = value / 10
            return if (year in 2000..2199 && value % 10 in 1..2)
                WhpuTerm(year, if (value % 10 == 1) "3" else "12") else null
        }
    }
}

interface WhpuScheduleProvider {
    fun schedule(term: WhpuTerm): WhpuSchedule
}

class WhpuSessionManager : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    @Volatile var userAgent: String? = null
        private set

    /** CookieManager already scoped this header to the exact academic endpoint. */
    @Synchronized fun acceptBrowserSession(header: String?, browserUserAgent: String) {
        clear()
        val url = WhpuWebPolicy.SCHEDULE.toHttpUrl()
        header.orEmpty().split(';').forEach { part ->
            val pair = part.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0].isNotBlank()) {
                cookies.add(Cookie.Builder().name(pair[0]).value(pair[1])
                    .hostOnlyDomain(url.host).path("/kbcx").secure().build())
            }
        }
        userAgent = browserUserAgent
    }

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies.add(cookie)
        }
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized fun clear() { cookies.clear(); userAgent = null }
}

sealed class WhpuAcademicException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: IOException) : WhpuAcademicException("网络请求失败", cause)
    class SessionExpired : WhpuAcademicException("教务会话已失效")
    class Forbidden : WhpuAcademicException("没有访问权限")
    class Http(val code: Int) : WhpuAcademicException("教务请求失败 ($code)")
    class Parse(cause: Throwable) : WhpuAcademicException("课表数据解析失败", cause)
}

class WhpuAcademicProvider(
    session: CookieJar,
    private val endpoint: String = "https://jwglxt.whpu.edu.cn/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151",
    client: OkHttpClient = OkHttpClient(),
    private val userAgent: () -> String? = { null },
) : WhpuScheduleProvider {
    private val client = client.newBuilder().cookieJar(session).followRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()

    override fun schedule(term: WhpuTerm): WhpuSchedule {
        val form = FormBody.Builder().add("xnm", term.year.toString()).add("xqm", term.code)
            .add("kzlx", "ck").add("xsdm", "").add("kclbdm", "").add("kclxdm", "").build()
        val request = Request.Builder().url(endpoint).post(form)
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .header("Accept", "*/*")
            .header("Origin", "https://jwglxt.whpu.edu.cn")
            .header("Referer", "https://jwglxt.whpu.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default")
            .header("X-Requested-With", "XMLHttpRequest")
            .apply { userAgent()?.let { header("User-Agent", it) } }.build()
        val response = try { client.newCall(request).execute() } catch (e: IOException) {
            throw WhpuAcademicException.Network(e)
        }
        response.use {
            when {
                it.code == 401 || it.isRedirect -> throw WhpuAcademicException.SessionExpired()
                it.code == 403 -> throw WhpuAcademicException.Forbidden()
                !it.isSuccessful -> throw WhpuAcademicException.Http(it.code)
            }
            val body = try { it.body?.string().orEmpty() } catch (e: IOException) {
                throw WhpuAcademicException.Network(e)
            }
            if (body.trimStart().startsWith("<")) throw WhpuAcademicException.SessionExpired()
            return try { WhpuScheduleParser.parse(body) } catch (e: Exception) {
                throw WhpuAcademicException.Parse(e)
            }
        }
    }
}
