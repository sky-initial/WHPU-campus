package com.zzupocket.app.data.whpu

import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.KvCacheEntity
import com.zzupocket.app.data.repo.ScheduleRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun interface WhpuGradeSource {
    suspend fun fetch(term: WhpuTerm): WhpuGrades
}

class WhpuGradeRepository(
    private val db: AppDatabase,
    private val scheduleRepository: ScheduleRepository,
    private val source: WhpuGradeSource,
    private val session: WhpuSessionManager,
) {
    fun observe(term: WhpuTerm) = db.kvCacheDao().observe(key(term)).map { entry ->
        entry?.let { WhpuGradesSnapshot(term, Json.decodeFromString<List<WhpuGrade>>(it.payload), it.fetchedAt) }
    }

    suspend fun cached(term: WhpuTerm): WhpuGradesSnapshot? = db.kvCacheDao().get(key(term))?.let {
        WhpuGradesSnapshot(term, Json.decodeFromString<List<WhpuGrade>>(it.payload), it.fetchedAt)
    }

    suspend fun refresh(term: WhpuTerm, replaceAccount: Boolean = false, legacyAccount: WhpuAccountId? = null): WhpuGradesSnapshot {
        require(term.year in 2000..2199 && term.code in setOf("3", "12"))
        val revision = session.revision
        val context = currentCoroutineContext()
        val current = { context.ensureActive(); session.revision == revision }
        try {
            val result = source.fetch(term)
            if (!current()) throw WhpuAcademicException.SessionChanged()
            require(result.term == term) { "Grade term mismatch" }
            val snapshot = WhpuGradesSnapshot(term, result.grades, System.currentTimeMillis())
            scheduleRepository.withWhpuAccount(result.account, replaceAccount, current, legacyAccount) {
                db.kvCacheDao().put(KvCacheEntity(key(term), Json.encodeToString(result.grades), snapshot.fetchedAt, 0))
            }
            return snapshot
        } catch (e: WhpuAcademicException.SessionExpired) {
            if (session.revision == revision) session.clear()
            throw e
        }
    }

    private fun key(term: WhpuTerm) = "whpu-grades:${term.year}:${term.code}"
}
