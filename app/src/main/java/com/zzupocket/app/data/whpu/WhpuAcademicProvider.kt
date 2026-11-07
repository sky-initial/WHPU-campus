package com.zzupocket.app.data.whpu

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class WhpuTerm(val year: Int, val code: String) {
    companion object {
        val YEAR_2026_FIRST = WhpuTerm(2026, "3")
    }
}

interface WhpuScheduleProvider {
    fun schedule(term: WhpuTerm): WhpuSchedule
}

class WhpuSessionManager : CookieJar {
    private val cookies = mutableListOf<Cookie>()

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

    @Synchronized fun clear() = cookies.clear()
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
            .header("X-Requested-With", "XMLHttpRequest").build()
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
