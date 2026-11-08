package com.zzupocket.app.data.whpu

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.repo.ScheduleRepository
import com.zzupocket.app.domain.PlanSpec
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WhpuExamCacheTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var name: String
    private lateinit var schedule: ScheduleRepository
    private val session = WhpuSessionManager()
    private val first = WhpuTerm(2025, "3")
    private val second = WhpuTerm(2025, "12")
    private fun exam(value: String) = WhpuExam("人工课程", "SYN-01", value, "示例考场", "示例校区",
        "示例考试", null, "否", "考试", "笔试")
    private fun source(owner: String = "synthetic-a", value: String = "待定") = WhpuExamSource { term ->
        WhpuExams(term, WhpuAccountId(owner), listOf(exam(value)))
    }
    private fun repo(provider: WhpuExamSource = source()) = WhpuExamRepository(db, schedule, provider, session)
    private fun timetable(owner: String) = WhpuScheduleParser.parse("""{"kbList":[
        {"kcmc":"人工课程","kch":"SYN-01","xqj":1,"jcs":"1-2","zcd":"1-2周"}],
        "xsxx":{"XH_ID":"$owner"}}""")
    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "whpu-exam-test-${UUID.randomUUID()}.db"
        open()
    }
    private fun open() {
        db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        schedule = ScheduleRepository(db, AppGraph.easClient, AppGraph.authRepository)
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }

    private fun grades() = WhpuGradeRepository(db, schedule, WhpuGradeSource {
        WhpuGrades(it, WhpuAccountId("synthetic-a"), listOf(WhpuGrade("人工课程", null, "2030-01-11(14:00-16:00)", "2", null, null, null, null, null)))
    }, session)
    @Test fun examAndGradeSwitchesClearEachOthersCache() = runBlocking {
        grades().refresh(first)
        repo().refresh(first)
        assertNotNull(grades().cached(first))
        repo(source("synthetic-b")).refresh(second, true)
        assertNull(grades().cached(first))
        grades().refresh(first, true)
        assertNull(repo().cached(second))
    }
    @Test fun failedOwnerWriteAlsoRestoresGradeCache() = runBlocking {
        val before = grades().refresh(first)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_exam_owner BEFORE INSERT ON kv_cache
            WHEN NEW.key = 'whpu-cache-owner' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END""")
        assertTrue(runCatching { repo(source("synthetic-b")).refresh(first, true) }.isFailure)
        assertEquals(before, grades().cached(first))
        assertNull(repo().cached(first))
    }
    @Test fun examSnapshotAndFetchTimeSurviveReopen() = runBlocking {
        val saved = repo().refresh(first)
        db.close(); open()
        assertEquals(saved, repo().cached(first))
    }
    @Test fun sameAccountRefreshPreservesOtherTermsAndCurrentTimetable() = runBlocking {
        schedule.importWhpuAccountTimetable(first, timetable("synthetic-a"))
        repo().refresh(first); repo().refresh(second)
        repo(source(value = "2030-01-10(09:00-11:00)")).refresh(first)
        assertEquals("2030-01-10(09:00-11:00)", repo().cached(first)!!.exams.single().timeText)
        assertEquals("待定", repo().cached(second)!!.exams.single().timeText)
        assertEquals(first.semesterId, schedule.currentSemester()!!.id)
        assertTrue(schedule.cachedSessions("whpu", first.semesterId).isNotEmpty())
    }
    @Test fun changingAccountWithoutConfirmationPreservesBothCaches() = runBlocking {
        schedule.importWhpuAccountTimetable(first, timetable("synthetic-a"))
        val before = repo().refresh(first)
        assertTrue(runCatching { repo(source("synthetic-b")).refresh(first) }.exceptionOrNull() is WhpuAcademicException.AccountChangeRequired)
        assertEquals(before, repo().cached(first))
        assertTrue(schedule.cachedSessions("whpu", first.semesterId).isNotEmpty())
    }
    @Test fun examAccountSwitchClearsSchoolDataAndKeepsCustomDemoAndDates() = runBlocking {
        schedule.ensureDemoTimetable()
        schedule.importWhpuAccountTimetable(first, timetable("synthetic-a"))
        schedule.importWhpuTimetable(first, java.time.LocalDate.of(2025, 9, 1), timetable("synthetic-a"))
        val plan = schedule.savePlan(PlanSpec(semesterId = first.semesterId, name = "人工日程", weekday = 2,
            startUnit = 1, endUnit = 2, weeks = listOf(1)))
        repo().refresh(first); repo().refresh(second)
        repo(source("synthetic-b", "2030-01-11(14:00-16:00)")).refresh(second, true)
        assertNull(repo().cached(first))
        assertEquals("2030-01-11(14:00-16:00)", repo().cached(second)!!.exams.single().timeText)
        assertTrue(schedule.cachedSessions("whpu", first.semesterId).isEmpty())
        assertNull(schedule.lastFetchedAt("whpu", first.semesterId))
        assertEquals(plan, schedule.customPlans(first.semesterId).single().code)
        assertEquals(java.time.LocalDate.of(2025, 9, 1), schedule.semester(first.semesterId)!!.startDate)
        assertTrue(schedule.cachedSessions("whpu-demo", WhpuScheduleMapper.DEMO_SEMESTER_ID).isNotEmpty())
    }
    @Test fun timetableAccountSwitchAlsoClearsExamsAcrossTerms() = runBlocking {
        repo().refresh(first); repo().refresh(second)
        schedule.importWhpuAccountTimetable(first, timetable("synthetic-a"))
        assertNotNull(repo().cached(first))
        schedule.importWhpuAccountTimetable(first, timetable("synthetic-b"), true)
        assertNull(repo().cached(first)); assertNull(repo().cached(second))
    }
    @Test fun accountWriteFailureRollsBackExamsTimetableAndOwner() = runBlocking {
        schedule.importWhpuAccountTimetable(first, timetable("synthetic-a"))
        val before = repo().refresh(first)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_exam_owner BEFORE INSERT ON kv_cache
            WHEN NEW.key = 'whpu-cache-owner' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END""")
        assertTrue(runCatching { repo(source("synthetic-b")).refresh(second, true) }.isFailure)
        assertEquals(before, repo().cached(first)); assertNull(repo().cached(second))
        assertTrue(schedule.cachedSessions("whpu", first.semesterId).isNotEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_exam_owner")
        assertEquals("待定", repo().refresh(second).exams.single().timeText)
    }
    @Test fun failedRequestKeepsPreviousSnapshotAndTimestamp() = runBlocking {
        val before = repo().refresh(first)
        val failure = WhpuExamSource { throw WhpuAcademicException.Network(java.io.IOException("synthetic offline")) }
        assertTrue(runCatching { repo(failure).refresh(first, true) }.isFailure)
        assertEquals(before, repo().cached(first))
    }
    @Test fun cancellationDoesNotSaveLateResponse() = runBlocking {
        val before = repo().refresh(first)
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<WhpuExams>()
        val job = async { repo(WhpuExamSource { started.complete(Unit); response.await() }).refresh(first) }
        started.await(); job.cancelAndJoin()
        response.complete(WhpuExams(first, WhpuAccountId("synthetic-a"), listOf(exam("2030-01-10(09:00-11:00)"))))
        assertEquals(before, repo().cached(first))
    }
    @Test fun sessionChangeDuringFetchCannotReplaceCache() = runBlocking {
        val before = repo().refresh(first)
        val result = runCatching { repo(WhpuExamSource { term ->
            session.clear(); WhpuExams(term, WhpuAccountId("synthetic-a"), listOf(exam("2030-01-10(09:00-11:00)")))
        }).refresh(first) }
        assertTrue(result.exceptionOrNull() is WhpuAcademicException.SessionChanged)
        assertEquals(before, repo().cached(first))
    }
    @Test fun verifiedEmptyTermReplacesOnlyItsExams() = runBlocking {
        schedule.importWhpuAccountTimetable(first, timetable("synthetic-a"))
        repo().refresh(first); repo().refresh(second)
        repo(WhpuExamSource { WhpuExams(it, WhpuAccountId("synthetic-a"), emptyList()) }).refresh(first)
        assertTrue(repo().cached(first)!!.exams.isEmpty())
        assertTrue(repo().cached(second)!!.exams.isNotEmpty())
        assertTrue(schedule.cachedSessions("whpu", first.semesterId).isNotEmpty())
    }
    @Test fun oldUnboundTimetableRequiresConfirmationBeforeExamsBindOwner() = runBlocking {
        schedule.importWhpuTimetable(first, null, timetable("synthetic-a"))
        val result = runCatching { repo().refresh(first) }
        assertTrue((result.exceptionOrNull() as WhpuAcademicException.AccountChangeRequired).legacyCache)
        assertNull(repo().cached(first))
        assertTrue(schedule.cachedSessions("whpu", first.semesterId).isNotEmpty())
    }

    @Test fun confirmedLegacyOwnerKeepsAllSchoolTermsAndDisplayChanges() = runBlocking {
        schedule.importWhpuTimetable(first, null, timetable("synthetic-a"))
        schedule.importWhpuTimetable(second, null, timetable("synthetic-a"))
        schedule.saveDisplayNameOverride(first.semesterId, "SYN-01", "人工显示名")
        val oldFirst = schedule.cachedSessions("whpu", first.semesterId)
        val oldSecond = schedule.cachedSessions("whpu", second.semesterId)
        repo().refresh(first, legacyAccount = WhpuAccountId("synthetic-a"))
        assertEquals(oldFirst, schedule.cachedSessions("whpu", first.semesterId))
        assertEquals(oldSecond, schedule.cachedSessions("whpu", second.semesterId))
        assertTrue(schedule.displayOverrides(first.semesterId).isNotEmpty())
        assertEquals(second.semesterId, schedule.currentSemester()!!.id)
        assertNotNull(repo().cached(first))
    }
    @Test fun changedAccountAfterLegacyConfirmationNeedsNewConfirmation() = runBlocking {
        schedule.importWhpuTimetable(first, null, timetable("synthetic-a"))
        val result = runCatching { repo(source("synthetic-b")).refresh(first, legacyAccount = WhpuAccountId("synthetic-a")) }
        assertTrue(result.exceptionOrNull() is WhpuAcademicException.AccountChangeRequired)
        assertNull(repo().cached(first))
        assertTrue(schedule.cachedSessions("whpu", first.semesterId).isNotEmpty())
    }
    @Test fun legacyConfirmationCannotOverrideAnAlreadyBoundDifferentAccount() = runBlocking {
        schedule.importWhpuAccountTimetable(first, timetable("synthetic-a"))
        val result = runCatching { repo(source("synthetic-b")).refresh(first, legacyAccount = WhpuAccountId("synthetic-b")) }
        assertTrue(result.exceptionOrNull() is WhpuAcademicException.AccountChangeRequired)
        assertNull(repo().cached(first))
    }
    @Test fun demoOnlyInstallationNeedsNoAccountConfirmation() = runBlocking {
        schedule.ensureDemoTimetable()
        repo().refresh(first)
        assertNotNull(repo().cached(first))
        assertTrue(schedule.cachedSessions("whpu-demo", WhpuScheduleMapper.DEMO_SEMESTER_ID).isNotEmpty())
    }
}
