package com.zzupocket.app.data.whpu

import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.KvCacheEntity
import com.zzupocket.app.data.repo.ScheduleRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun interface WhpuExamSource {
    suspend fun fetch(term: WhpuTerm): WhpuExams
}

class WhpuExamRepository(
    private val db: AppDatabase,
    private val scheduleRepository: ScheduleRepository,
    private val source: WhpuExamSource,
    private val session: WhpuSessionManager,
) {
    fun observe(term: WhpuTerm) = db.kvCacheDao().observe(key(term)).map { entry ->
        entry?.let { WhpuExamsSnapshot(term, Json.decodeFromString<List<WhpuExam>>(it.payload), it.fetchedAt) }
    }

    suspend fun cached(term: WhpuTerm): WhpuExamsSnapshot? = db.kvCacheDao().get(key(term))?.let {
        WhpuExamsSnapshot(term, Json.decodeFromString<List<WhpuExam>>(it.payload), it.fetchedAt)
    }

    suspend fun refresh(term: WhpuTerm, replaceAccount: Boolean = false, legacyAccount: WhpuAccountId? = null): WhpuExamsSnapshot {
        require(term.year in 2000..2199 && term.code in setOf("3", "12"))
        val revision = session.revision
        val context = currentCoroutineContext()
        val current = { context.ensureActive(); session.revision == revision }
        try {
            val result = source.fetch(term)
            if (!current()) throw WhpuAcademicException.SessionChanged()
            require(result.term == term) { "Exam term mismatch" }
            val snapshot = WhpuExamsSnapshot(term, result.exams, System.currentTimeMillis())
            scheduleRepository.withWhpuAccount(result.account, replaceAccount, current, legacyAccount) {
                db.kvCacheDao().put(KvCacheEntity(key(term), Json.encodeToString(result.exams), snapshot.fetchedAt, 0))
            }
            return snapshot
        } catch (e: WhpuAcademicException.SessionExpired) {
            if (session.revision == revision) session.clear()
            throw e
        }
    }

    private fun key(term: WhpuTerm) = "whpu-exams:${term.year}:${term.code}"
}
