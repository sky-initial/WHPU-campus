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
class WhpuGradeCacheTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var name: String
    private lateinit var schedule: ScheduleRepository
    private val session = WhpuSessionManager()
    private val first = WhpuTerm(2025, "3")
    private val second = WhpuTerm(2025, "12")
    private fun grade(value: String) = WhpuGrade("人工课程", "SYN-01", value, "2.0", "必修课", "正常考试", null, "否", "考试",
        gradePoint = "3.00", creditGradePoint = "6.00")
    private fun source(owner: String = "synthetic-a", value: String = "良好") = WhpuGradeSource { term ->
        WhpuGrades(term, WhpuAccountId(owner), listOf(grade(value)))
    }
    private fun repo(provider: WhpuGradeSource = source()) = WhpuGradeRepository(db, schedule, provider, session)
    private fun timetable(owner: String) = WhpuScheduleParser.parse("""{"kbList":[
        {"kcmc":"人工课程","kch":"SYN-01","xqj":1,"jcs":"1-2","zcd":"1-2周"}],
        "xsxx":{"XH_ID":"$owner"}}""")
    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "whpu-grade-test-${UUID.randomUUID()}.db"
        open()
    }
    private fun open() {
        db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        schedule = ScheduleRepository(db, AppGraph.easClient, AppGraph.authRepository)
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }

    @Test fun gradeSnapshotAndFetchTimeSurviveReopen() = runBlocking {
        val saved = repo().refresh(first)
        db.close(); open()
        assertEquals(saved, repo().cached(first))
    }
    @Test fun sameAccountRefreshPreservesOtherTermsAndCurrentTimetable() = runBlocking {
        schedule.importWhpuAccountTimetable(first, timetable("synthetic-a"))
        repo().refresh(first); repo().refresh(second)
        repo(source(value = "优秀")).refresh(first)
        assertEquals("优秀", repo().cached(first)!!.grades.single().grade)
        assertEquals("良好", repo().cached(second)!!.grades.single().grade)
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
    @Test fun gradeAccountSwitchClearsSchoolDataAndKeepsCustomDemoAndDates() = runBlocking {
        schedule.ensureDemoTimetable()
        schedule.importWhpuAccountTimetable(first, timetable("synthetic-a"))
        schedule.importWhpuTimetable(first, java.time.LocalDate.of(2025, 9, 1), timetable("synthetic-a"))
        val plan = schedule.savePlan(PlanSpec(semesterId = first.semesterId, name = "人工日程", weekday = 2,
            startUnit = 1, endUnit = 2, weeks = listOf(1)))
        repo().refresh(first); repo().refresh(second)
        repo(source("synthetic-b", "合格")).refresh(second, true)
        assertNull(repo().cached(first))
        assertEquals("合格", repo().cached(second)!!.grades.single().grade)
        assertTrue(schedule.cachedSessions("whpu", first.semesterId).isEmpty())
        assertNull(schedule.lastFetchedAt("whpu", first.semesterId))
        assertEquals(plan, schedule.customPlans(first.semesterId).single().code)
        assertEquals(java.time.LocalDate.of(2025, 9, 1), schedule.semester(first.semesterId)!!.startDate)
        assertTrue(schedule.cachedSessions("whpu-demo", WhpuScheduleMapper.DEMO_SEMESTER_ID).isNotEmpty())
    }
    @Test fun timetableAccountSwitchAlsoClearsGradesAcrossTerms() = runBlocking {
        repo().refresh(first); repo().refresh(second)
        schedule.importWhpuAccountTimetable(first, timetable("synthetic-a"))
        assertNotNull(repo().cached(first))
        schedule.importWhpuAccountTimetable(first, timetable("synthetic-b"), true)
        assertNull(repo().cached(first)); assertNull(repo().cached(second))
    }
    @Test fun accountWriteFailureRollsBackGradesTimetableAndOwner() = runBlocking {
        schedule.importWhpuAccountTimetable(first, timetable("synthetic-a"))
        val before = repo().refresh(first)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_grade_owner BEFORE INSERT ON kv_cache
            WHEN NEW.key = 'whpu-cache-owner' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END""")
        assertTrue(runCatching { repo(source("synthetic-b")).refresh(second, true) }.isFailure)
        assertEquals(before, repo().cached(first)); assertNull(repo().cached(second))
        assertTrue(schedule.cachedSessions("whpu", first.semesterId).isNotEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_grade_owner")
        assertEquals("良好", repo().refresh(second).grades.single().grade)
    }
    @Test fun failedRequestKeepsPreviousSnapshotAndTimestamp() = runBlocking {
        val before = repo().refresh(first)
        val failure = WhpuGradeSource { throw WhpuAcademicException.Network(java.io.IOException("synthetic offline")) }
        assertTrue(runCatching { repo(failure).refresh(first, true) }.isFailure)
        assertEquals(before, repo().cached(first))
    }
    @Test fun cancellationDoesNotSaveLateResponse() = runBlocking {
        val before = repo().refresh(first)
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<WhpuGrades>()
        val job = async { repo(WhpuGradeSource { started.complete(Unit); response.await() }).refresh(first) }
        started.await(); job.cancelAndJoin()
        response.complete(WhpuGrades(first, WhpuAccountId("synthetic-a"), listOf(grade("优秀"))))
        assertEquals(before, repo().cached(first))
    }
    @Test fun sessionChangeDuringFetchCannotReplaceCache() = runBlocking {
        val before = repo().refresh(first)
        val result = runCatching { repo(WhpuGradeSource { term ->
            session.clear(); WhpuGrades(term, WhpuAccountId("synthetic-a"), listOf(grade("优秀")))
        }).refresh(first) }
        assertTrue(result.exceptionOrNull() is WhpuAcademicException.SessionChanged)
        assertEquals(before, repo().cached(first))
    }
    @Test fun verifiedEmptyTermReplacesOnlyItsGrades() = runBlocking {
        schedule.importWhpuAccountTimetable(first, timetable("synthetic-a"))
        repo().refresh(first); repo().refresh(second)
        repo(WhpuGradeSource { WhpuGrades(it, WhpuAccountId("synthetic-a"), emptyList()) }).refresh(first)
        assertTrue(repo().cached(first)!!.grades.isEmpty())
        assertTrue(repo().cached(second)!!.grades.isNotEmpty())
        assertTrue(schedule.cachedSessions("whpu", first.semesterId).isNotEmpty())
    }
    @Test fun oldUnboundTimetableRequiresConfirmationBeforeGradesBindOwner() = runBlocking {
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
