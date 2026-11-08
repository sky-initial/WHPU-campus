package com.zzupocket.app.data.whpu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient

class WhpuGradeProvider(
    private val session: WhpuSessionManager,
    private val endpoint: String = WhpuWebPolicy.GRADES,
    private val identityEndpoint: String = WhpuWebPolicy.SCHEDULE,
    private val client: OkHttpClient = OkHttpClient(),
) : WhpuGradeSource {
    override suspend fun fetch(term: WhpuTerm): WhpuGrades = withContext(Dispatchers.IO) {
        require(term.year in 2000..2199 && term.code in setOf("3", "12"))
        val query = WhpuAcademicQuery(session, client, identityEndpoint)
        val identity = query.identity(term)
        val grades = collectWhpuGradePages { page ->
            val form = FormBody.Builder().add("xnm", term.year.toString()).add("xqm", term.code)
                .add("sfzgcj", "").add("kcbj", "").add("pkey", "").add("_search", "false")
                .add("nd", System.currentTimeMillis().toString()).add("queryModel.showCount", "15")
                .add("queryModel.currentPage", page.toString()).add("queryModel.sortName", " ")
                .add("queryModel.sortOrder", "asc").add("time", "2").build()
            val body = query.post(endpoint, form, WhpuWebPolicy.GRADES_PAGE)
            parseAcademicData { WhpuGradeParser.parse(body, term, identity.student) }
        }
        WhpuGrades(term, identity.account, grades)
    }
}
