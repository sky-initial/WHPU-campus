package com.zzupocket.app.data.whpu

import com.zzupocket.app.data.whpu.WhpuGradeParser.text
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class WhpuGradeProvider(
    private val session: WhpuSessionManager,
    private val endpoint: String = WhpuWebPolicy.GRADES,
    private val identityEndpoint: String = WhpuWebPolicy.SCHEDULE,
    private val client: OkHttpClient = OkHttpClient(),
) : WhpuGradeSource {
    override suspend fun fetch(term: WhpuTerm): WhpuGrades = withContext(Dispatchers.IO) {
        require(term.year in 2000..2199 && term.code in setOf("3", "12"))
        val snapshot = session.snapshot()
        val http = client.newBuilder().cookieJar(snapshot).followRedirects(false)
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
        // Empty grade lists contain no identity. Verify the same frozen school session first.
        val identityBody = post(identityEndpoint, FormBody.Builder()
            .add("xnm", term.year.toString()).add("xqm", term.code).add("kzlx", "ck")
            .add("xsdm", "").add("kclbdm", "").add("kclxdm", "").build(),
            "https://jwglxt.whpu.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default")
        val identity = parseAcademicData {
            Json.parseToJsonElement(identityBody).jsonObject["xsxx"]?.jsonObject
        } ?: throw WhpuAcademicException.MissingAccount()
        val account = identity.text("XH_ID")?.let(::WhpuAccountId) ?: throw WhpuAcademicException.MissingAccount()
        val student = identity.text("XH")?.let(::WhpuAccountId) ?: throw WhpuAcademicException.MissingAccount()
        val grades = collectWhpuGradePages { page ->
            val form = FormBody.Builder().add("xnm", term.year.toString()).add("xqm", term.code)
                .add("sfzgcj", "").add("kcbj", "").add("pkey", "").add("_search", "false")
                .add("nd", System.currentTimeMillis().toString()).add("queryModel.showCount", "15")
                .add("queryModel.currentPage", page.toString()).add("queryModel.sortName", " ")
                .add("queryModel.sortOrder", "asc").add("time", "2").build()
            val body = post(endpoint, form, WhpuWebPolicy.GRADES_PAGE)
            parseAcademicData { WhpuGradeParser.parse(body, term, student) }
        }
        WhpuGrades(term, account, grades)
    }
}
