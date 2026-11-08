package com.zzupocket.app.data.whpu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WhpuImportCoordinatorTest {
    private val term = WhpuTerm.YEAR_2026_FIRST
    private fun payload() = WhpuScheduleParser.parse("""{"kbList":[],"xsxx":{"XH_ID":"synthetic-account"}}""")
    private fun provider(fetch: suspend () -> WhpuSchedule) = object : WhpuScheduleProvider {
        override fun schedule(term: WhpuTerm): WhpuSchedule = error("Use cancellable fetch")
        override suspend fun fetchSchedule(term: WhpuTerm) = fetch()
    }

    @Test fun successfulEmptyScheduleStillHasAnAccountAndCanReplaceCache() = runTest {
        var saves = 0
        val session = WhpuSessionManager()
        WhpuImportCoordinator(provider { payload() }, session) { _, data, replace, current ->
            assertNotNull(data.accountId); assertFalse(replace); assertTrue(current()); saves++
        }.import(term)
        assertEquals(1, saves)
    }

    @Test fun missingIdentityDoesNotWriteOrGuessTheAccount() = runTest {
        var saved = false
        val result = runCatching {
            WhpuImportCoordinator(provider { WhpuSchedule(emptyList(), emptyList()) }, WhpuSessionManager()) {
                    _, _, _, _ -> saved = true
            }.import(term)
        }
        assertTrue(result.exceptionOrNull() is WhpuAcademicException.MissingAccount)
        assertFalse(saved)
    }

    @Test fun cancellationLeavesCacheAndLoginUntouched() = runTest {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<WhpuSchedule>()
        val session = WhpuSessionManager().apply { acceptBrowserSession("JSESSIONID=synthetic", "test") }
        val revision = session.revision
        var saved = false
        val job = async {
            WhpuImportCoordinator(provider { started.complete(Unit); response.await() }, session) {
                    _, _, _, _ -> saved = true
            }.import(term)
        }
        started.await()
        job.cancelAndJoin()
        response.complete(payload())
        assertFalse(saved)
        assertEquals(revision, session.revision)
    }

    @Test fun responseFromPreviousLoginCannotReachCacheWriter() = runTest {
        val session = WhpuSessionManager()
        var saved = false
        val result = runCatching {
            WhpuImportCoordinator(provider { session.clear(); payload() }, session) {
                    _, _, _, _ -> saved = true
            }.import(term)
        }
        assertTrue(result.exceptionOrNull() is WhpuAcademicException.SessionChanged)
        assertFalse(saved)
    }

    @Test fun expiredLoginClearsMemorySessionAndKeepsCache() = runTest {
        val session = WhpuSessionManager().apply { acceptBrowserSession("JSESSIONID=synthetic", "test") }
        var saved = false
        val result = runCatching {
            WhpuImportCoordinator(provider { throw WhpuAcademicException.SessionExpired() }, session) {
                    _, _, _, _ -> saved = true
            }.import(term)
        }
        assertTrue(result.exceptionOrNull() is WhpuAcademicException.SessionExpired)
        assertNull(session.userAgent)
        assertFalse(saved)
    }

    @Test fun oldExpiryDoesNotClearANewerLogin() = runTest {
        val session = WhpuSessionManager()
        runCatching {
            WhpuImportCoordinator(provider {
                session.acceptBrowserSession("JSESSIONID=synthetic-new", "new-browser")
                throw WhpuAcademicException.SessionExpired()
            }, session) { _, _, _, _ -> fail("Must not write") }.import(term)
        }
        assertEquals("new-browser", session.userAgent)
    }

    @Test fun forbiddenAndNetworkFailuresDoNotWriteOrDiscardSession() = runTest {
        listOf(WhpuAcademicException.Forbidden(), WhpuAcademicException.Network(java.io.IOException("offline")))
            .forEach { failure ->
                val session = WhpuSessionManager().apply { acceptBrowserSession("JSESSIONID=synthetic", "test") }
                val revision = session.revision
                val result = runCatching {
                    WhpuImportCoordinator(provider { throw failure }, session) { _, _, _, _ -> fail("Must not write") }
                        .import(term, replaceAccount = true)
                }
                assertSame(failure, result.exceptionOrNull())
                assertEquals(revision, session.revision)
            }
    }

    @Test fun accountIdentifierNeverAppearsInModelDiagnosticString() {
        assertFalse(payload().toString().contains("synthetic-account"))
        assertNull(WhpuScheduleParser.parse("""{"kbList":[],"xsxx":{"XH_ID":" "}}""").accountId)
    }
}
