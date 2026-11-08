package com.zzupocket.app.data.whpu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient

class WhpuExamProvider(
    private val session: WhpuSessionManager,
    private val endpoint: String = WhpuWebPolicy.EXAMS,
    private val identityEndpoint: String = WhpuWebPolicy.SCHEDULE,
    private val client: OkHttpClient = OkHttpClient(),
) : WhpuExamSource {
    override suspend fun fetch(term: WhpuTerm): WhpuExams = withContext(Dispatchers.IO) {
        require(term.year in 2000..2199 && term.code in setOf("3", "12"))
        val query = WhpuAcademicQuery(session, client, identityEndpoint)
        val identity = query.identity(term)
        val exams = collectWhpuAcademicPages { page ->
            val form = FormBody.Builder().add("xnm", term.year.toString()).add("xqm", term.code)
                .add("ksmcdmb_id", "").add("kch", "").add("kc", "").add("ksrq", "").add("kkbm_id", "")
                .add("_search", "false").add("nd", System.currentTimeMillis().toString())
                .add("queryModel.showCount", "15").add("queryModel.currentPage", page.toString())
                .add("queryModel.sortName", " ").add("queryModel.sortOrder", "asc").add("time", "1").build()
            val body = query.post(endpoint, form, WhpuWebPolicy.EXAMS_PAGE)
            parseAcademicData { WhpuExamParser.parse(body, term, identity.student) }
        }
        WhpuExams(term, identity.account, exams)
    }
}
