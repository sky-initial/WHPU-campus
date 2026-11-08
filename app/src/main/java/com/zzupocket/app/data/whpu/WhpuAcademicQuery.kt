package com.zzupocket.app.data.whpu

import com.zzupocket.app.data.whpu.WhpuGradeParser.text
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class WhpuQueryIdentity(val account: WhpuAccountId, val student: WhpuAccountId)

/** One cookie snapshot for identity verification and every page of a school query. */
internal class WhpuAcademicQuery(
    session: WhpuSessionManager,
    client: OkHttpClient,
    private val identityEndpoint: String = WhpuWebPolicy.SCHEDULE,
) {
    private val snapshot = session.snapshot()
    private val http = client.newBuilder().cookieJar(snapshot).followRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()

    suspend fun post(url: String, form: FormBody, referer: String): String {
        val request = Request.Builder().url(url).post(form)
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .header("Origin", "https://jwglxt.whpu.edu.cn")
            .header("Referer", referer).header("X-Requested-With", "XMLHttpRequest")
            .apply { snapshot.userAgent?.let { header("User-Agent", it) } }.build()
        return http.newCall(request).awaitAcademicBody()
    }

    suspend fun identity(term: WhpuTerm): WhpuQueryIdentity {
        val body = post(identityEndpoint, FormBody.Builder()
            .add("xnm", term.year.toString()).add("xqm", term.code).add("kzlx", "ck")
            .add("xsdm", "").add("kclbdm", "").add("kclxdm", "").build(),
            "https://jwglxt.whpu.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default")
        val identity = parseAcademicData { Json.parseToJsonElement(body).jsonObject["xsxx"]?.jsonObject }
            ?: throw WhpuAcademicException.MissingAccount()
        return WhpuQueryIdentity(
            identity.text("XH_ID")?.let(::WhpuAccountId) ?: throw WhpuAcademicException.MissingAccount(),
            identity.text("XH")?.let(::WhpuAccountId) ?: throw WhpuAcademicException.MissingAccount(),
        )
    }
}
