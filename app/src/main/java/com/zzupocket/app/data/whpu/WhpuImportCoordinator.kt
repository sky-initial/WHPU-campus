package com.zzupocket.app.data.whpu

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** No cache write until the response is complete and the initiating login session is still current. */
class WhpuImportCoordinator(
    private val provider: WhpuScheduleProvider,
    private val session: WhpuSessionManager,
    private val save: suspend (WhpuTerm, WhpuSchedule, Boolean, () -> Boolean) -> Unit,
) {
    suspend fun import(term: WhpuTerm, replaceAccount: Boolean = false): WhpuSchedule {
        val revision = session.revision
        val context = currentCoroutineContext()
        val current = { context.ensureActive(); session.revision == revision }
        try {
            val schedule = provider.fetchSchedule(term)
            if (!current()) throw WhpuAcademicException.SessionChanged()
            if (schedule.accountId == null) throw WhpuAcademicException.MissingAccount()
            save(term, schedule, replaceAccount, current)
            return schedule
        } catch (e: WhpuAcademicException.SessionExpired) {
            if (session.revision == revision) session.clear()
            throw e
        }
    }
}
