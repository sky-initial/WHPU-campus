package com.zzupocket.app.data.whpu

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.repo.ScheduleRepository
import com.zzupocket.app.domain.PlanSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WhpuCacheTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository

    @Before fun openDatabase() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "whpu-cache-test-${UUID.randomUUID()}.db"
        openRepository()
    }

    @After fun deleteTestDatabase() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun snapshotSurvivesDatabaseReopen() = runBlocking {
        saveSnapshot("old")
        db.close()
        openRepository()
        assertSnapshot("old")
    }

    @Test fun realImportActivatesUserDatedTermAndKeepsDemoAndCustomData() = runBlocking {
        repo.ensureDemoTimetable()
        val demo = repo.cachedSessions("whpu-demo", WhpuScheduleMapper.DEMO_SEMESTER_ID)
        val term = WhpuTerm.YEAR_2026_FIRST
        val monday = java.time.LocalDate.of(2026, 9, 7)
        val schedule = WhpuScheduleParser.parse("""{"kbList":[
          {"kcmc":"示例晚课","kch":"test-evening","xqj":"1","jcs":"9-11","zcd":"1-2周"}
        ],"sjkList":[{"kcmc":"示例实践","qsjsz":"19周","jsxm":"教师甲"}]}""")
        repo.importWhpuTimetable(term, monday, schedule)
        val custom = repo.savePlan(PlanSpec(semesterId = term.semesterId, name = "test custom",
            weekday = 2, startUnit = 3, endUnit = 4, weeks = listOf(1)))
        repo.importWhpuTimetable(term, monday.plusWeeks(1), schedule)
        db.close()
        openRepository()
        assertEquals(term.semesterId, repo.currentSemester()!!.id)
        assertEquals(monday.plusWeeks(1), repo.currentSemester()!!.startDate)
        assertEquals("18:15", repo.cachedSessions("whpu", term.semesterId).first().startTime)
        assertEquals("20:50", repo.cachedSessions("whpu", term.semesterId).first().endTime)
        assertEquals("10:10", repo.cachedSessions(ScheduleRepository.SOURCE_CUSTOM, term.semesterId).single().startTime)
        assertEquals(custom, repo.customPlans(term.semesterId).single().code)
        assertEquals(setOf(19), repo.whpuPractice("whpu", term.semesterId).single().weeks)
        repo.ensureDemoTimetable()
        assertEquals(term.semesterId, repo.currentSemester()!!.id)
        assertEquals(demo, repo.cachedSessions("whpu-demo", WhpuScheduleMapper.DEMO_SEMESTER_ID))
    }

    @Test fun termActivationFailureRollsBackImportedCourses() = runBlocking {
        repo.ensureDemoTimetable()
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_term BEFORE INSERT ON semesters
            BEGIN SELECT RAISE(ABORT, 'test-term-failure'); END
        """.trimIndent())
        val term = WhpuTerm.YEAR_2026_FIRST
        val result = runCatching { repo.importWhpuTimetable(term, java.time.LocalDate.of(2026, 9, 7), schedule("new")) }
        assertTrue(result.exceptionOrNull() is SQLiteException)
        assertEquals(WhpuScheduleMapper.DEMO_SEMESTER_ID, repo.currentSemester()!!.id)
        assertTrue(repo.cachedSessions("whpu", term.semesterId).isEmpty())
        assertTrue(repo.whpuPractice("whpu", term.semesterId).isEmpty())
        assertNull(repo.lastFetchedAt("whpu", term.semesterId))
    }

    private fun owned(name: String, owner: String) = schedule(name).copy(accountId = WhpuAccountId(owner))

    @Test fun sameAccountRefreshPreservesOtherTermsAndOwnerSurvivesReopen() = runBlocking {
        val first = WhpuTerm(2026, "3")
        val second = WhpuTerm(2026, "12")
        repo.importWhpuAccountTimetable(first, owned("first", "synthetic-a"))
        repo.importWhpuAccountTimetable(second, owned("second", "synthetic-a"))
        db.close(); openRepository()
        repo.importWhpuAccountTimetable(first, owned("updated", "synthetic-a"))
        assertEquals("second", repo.courses(second.semesterId).single().nameZh)
        assertEquals("updated", repo.courses(first.semesterId).single().nameZh)
    }

    @Test fun differentAccountRequiresConfirmationAndLeavesSnapshotUntouched() = runBlocking {
        val term = WhpuTerm.YEAR_2026_FIRST
        repo.importWhpuAccountTimetable(term, owned("old", "synthetic-a"))
        val before = repo.lastFetchedAt("whpu", term.semesterId)
        val result = runCatching { repo.importWhpuAccountTimetable(term, owned("new", "synthetic-b")) }
        assertTrue(result.exceptionOrNull() is WhpuAcademicException.AccountChangeRequired)
        assertEquals("old", repo.courses(term.semesterId).single().nameZh)
        assertEquals(before, repo.lastFetchedAt("whpu", term.semesterId))
    }

    @Test fun confirmedAccountSwitchReplacesSchoolTermsButPreservesCustomAndDemo() = runBlocking {
        repo.ensureDemoTimetable()
        val first = WhpuTerm(2026, "3")
        val second = WhpuTerm(2026, "12")
        repo.importWhpuAccountTimetable(first, owned("first", "synthetic-a"))
        repo.importWhpuAccountTimetable(second, owned("second", "synthetic-a"))
        val custom = repo.savePlan(PlanSpec(semesterId = second.semesterId, name = "device custom",
            weekday = 2, startUnit = 1, endUnit = 2, weeks = listOf(1)))
        repo.saveDisplayNameOverride(first.semesterId, "test-course", "old display")
        repo.importWhpuAccountTimetable(first, owned("new", "synthetic-b"), replaceAccount = true)
        assertEquals("new", repo.courses(first.semesterId).single().nameZh)
        assertTrue(repo.cachedSessions("whpu", second.semesterId).isEmpty())
        assertTrue(repo.whpuPractice("whpu", second.semesterId).isEmpty())
        assertNull(repo.lastFetchedAt("whpu", second.semesterId))
        assertEquals(custom, repo.customPlans(second.semesterId).single().code)
        assertTrue(repo.displayOverrides(first.semesterId).isEmpty())
        assertTrue(repo.cachedSessions("whpu-demo", WhpuScheduleMapper.DEMO_SEMESTER_ID).isNotEmpty())
    }

    @Test fun accountSwitchTransactionFailureRestoresAllTermsAndOwner() = runBlocking {
        val first = WhpuTerm(2026, "3")
        val second = WhpuTerm(2026, "12")
        repo.importWhpuAccountTimetable(first, owned("first", "synthetic-a"))
        repo.importWhpuAccountTimetable(second, owned("second", "synthetic-a"))
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_owner BEFORE INSERT ON kv_cache
            WHEN NEW.key = 'whpu-cache-owner'
            BEGIN SELECT RAISE(ABORT, 'test-owner-failure'); END
        """.trimIndent())
        val result = runCatching { repo.importWhpuAccountTimetable(first, owned("new", "synthetic-b"), true) }
        assertTrue(result.exceptionOrNull() is SQLiteException)
        assertEquals("first", repo.courses(first.semesterId).single().nameZh)
        assertEquals("second", repo.courses(second.semesterId).single().nameZh)
        assertEquals(second.semesterId, repo.currentSemester()!!.id)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_owner")
        repo.importWhpuAccountTimetable(first, owned("still-a", "synthetic-a"))
    }

    @Test fun sessionChangeBeforeCommitRollsBackTheReplacement() = runBlocking {
        val term = WhpuTerm.YEAR_2026_FIRST
        repo.importWhpuAccountTimetable(term, owned("old", "synthetic-a"))
        var checks = 0
        val result = runCatching {
            repo.importWhpuAccountTimetable(term, owned("late", "synthetic-a"), sessionCurrent = { ++checks == 1 })
        }
        assertTrue(result.exceptionOrNull() is WhpuAcademicException.SessionChanged)
        assertEquals("old", repo.courses(term.semesterId).single().nameZh)
    }

    @Test fun legacyCacheNeedsConfirmationBeforeFirstAccountBinding() = runBlocking {
        val term = WhpuTerm.YEAR_2026_FIRST
        repo.importWhpuTimetable(term, null, schedule("legacy"))
        val result = runCatching { repo.importWhpuAccountTimetable(term, owned("bound", "synthetic-a")) }
        assertTrue((result.exceptionOrNull() as WhpuAcademicException.AccountChangeRequired).legacyCache)
        assertEquals("legacy", repo.courses(term.semesterId).single().nameZh)
        repo.importWhpuAccountTimetable(term, owned("bound", "synthetic-a"), true)
        assertEquals("bound", repo.courses(term.semesterId).single().nameZh)
    }

    @Test fun unidentifiedAccountCannotReplaceEvenWithConfirmation() = runBlocking {
        val term = WhpuTerm.YEAR_2026_FIRST
        repo.importWhpuAccountTimetable(term, owned("old", "synthetic-a"))
        val result = runCatching { repo.importWhpuAccountTimetable(term, schedule("unknown"), true) }
        assertTrue(result.exceptionOrNull() is WhpuAcademicException.MissingAccount)
        assertEquals("old", repo.courses(term.semesterId).single().nameZh)
    }

    @Test fun importCanPersistBeforeTheUserSelectsFirstWeekDate() = runBlocking {
        val term = WhpuTerm.YEAR_2026_FIRST
        repo.importWhpuTimetable(term, null, schedule("new"))
        db.close()
        openRepository()
        assertEquals(term.semesterId, repo.currentSemester()!!.id)
        assertNull(repo.currentSemester()!!.startDate)
        assertTrue(repo.cachedSessions("whpu", term.semesterId).isNotEmpty())
        assertTrue(repo.whpuPractice("whpu", term.semesterId).isNotEmpty())
        assertNull(repo.resolveCurrentWeek(repo.currentSemester()!!, java.time.LocalDate.of(2026, 9, 7)))
    }

    @Test fun reimportWithoutDatePreservesThePreviouslyChosenStart() = runBlocking {
        val term = WhpuTerm.YEAR_2026_FIRST
        val monday = java.time.LocalDate.of(2026, 9, 7)
        repo.importWhpuTimetable(term, monday, schedule("old"))
        repo.importWhpuTimetable(term, null, schedule("new"))
        assertEquals(monday, repo.currentSemester()!!.startDate)
        assertEquals("new", repo.courses(term.semesterId).single().nameZh)
    }

    @Test fun invalidMondayDoesNotActivateOrWriteTerm() = runBlocking {
        val term = WhpuTerm.YEAR_2026_FIRST
        val result = runCatching { repo.importWhpuTimetable(term, java.time.LocalDate.of(2026, 9, 8), schedule("new")) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertNull(repo.currentSemester())
        assertTrue(repo.cachedSessions("whpu", term.semesterId).isEmpty())
    }

    @Test fun observedPracticeFieldsSurviveDatabaseReopen() = runBlocking {
        val provider = object : WhpuScheduleProvider {
            override fun schedule(term: WhpuTerm) = WhpuScheduleParser.parse("""
                {"kbList":[],"sjkList":[
                  {"kcmc":"示例实习","qsjsz":"1-3周","jsxm":"教师甲,教师乙","sfsjk":"1","xqmc":"示例校区"},
                  {"kcmc":"示例课程设计","qsjsz":"19周","jsxm":"教师丙","xqmc":"示例校区"}
                ]}
            """.trimIndent())
        }
        WhpuAcademicRepository(provider, repo).refresh(WhpuTerm.YEAR_2026_FIRST, TERM)
        db.close()
        openRepository()
        val practice = repo.whpuPractice(SOURCE, TERM)
        assertEquals(listOf(setOf(1, 2, 3), setOf(19)), practice.map { it.weeks })
        assertEquals(listOf("教师甲,教师乙", "教师丙"), practice.map { it.teacher })
        assertTrue(practice.all { it.campus == "示例校区" })
        assertFalse(practice[1].raw.containsKey("sfsjk"))
    }

    @Test fun failedRefreshRetainsCoursesPracticeAndFetchTime() = runBlocking {
        saveSnapshot("old")
        val provider = object : WhpuScheduleProvider {
            override fun schedule(term: WhpuTerm): WhpuSchedule =
                throw WhpuAcademicException.Network(IOException("test offline"))
        }
        val result = runCatching { WhpuAcademicRepository(provider, repo).refresh(WhpuTerm.YEAR_2026_FIRST, TERM) }
        assertTrue(result.exceptionOrNull() is WhpuAcademicException.Network)
        assertSnapshot("old")
    }

    @Test fun lateWriteFailureRollsBackEntireSnapshot() = runBlocking {
        saveSnapshot("old")
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_test_sync BEFORE INSERT ON timetable_sync
            BEGIN SELECT RAISE(ABORT, 'test-write-failure'); END
        """.trimIndent())
        val result = runCatching { saveSnapshot("new", fetchedAt = 2000L) }
        assertTrue(result.exceptionOrNull() is SQLiteException)
        assertSnapshot("old")
        assertEquals("old", repo.courses(TERM).single().nameZh)
    }

    @Test fun successfulReplacementPreservesCustomSchedule() = runBlocking {
        saveSnapshot("old")
        val code = saveCustomPlan()
        val customBefore = repo.cachedSessions(ScheduleRepository.SOURCE_CUSTOM, TERM)
        saveSnapshot("new", fetchedAt = 2000L)
        assertSnapshot("new", fetchedAt = 2000L)
        assertEquals(customBefore, repo.cachedSessions(ScheduleRepository.SOURCE_CUSTOM, TERM))
        assertEquals(code, repo.customPlans(TERM).single().code)
        assertEquals("new", repo.courses(TERM).single().nameZh)
    }

    @Test fun validEmptySnapshotClearsSchoolCoursesAndKeepsCustomSchedule() = runBlocking {
        saveSnapshot("old")
        saveCustomPlan()
        repo.saveTimetable(SOURCE, TERM, emptyList(), emptyList(), 2000L, "[]")
        assertTrue(repo.cachedSessions(SOURCE, TERM).isEmpty())
        assertTrue(repo.courses(TERM).isEmpty())
        assertTrue(repo.whpuPractice(SOURCE, TERM).isEmpty())
        assertEquals(2000L, repo.lastFetchedAt(SOURCE, TERM))
        assertEquals(1, repo.customPlans(TERM).size)
    }

    @Test fun conflictingSourceCannotOverwriteExistingSnapshot() = runBlocking {
        saveSnapshot("old")
        saveSnapshot("demo", source = "whpu-demo", semesterId = TERM + 1)
        val result = runCatching { saveSnapshot("wrong", source = "whpu-demo") }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertSnapshot("old")
        assertSnapshot("demo", source = "whpu-demo", semesterId = TERM + 1)
    }

    @Test fun clearingOneTermRemovesItsPracticeAndPreservesOtherTerm() = runBlocking {
        saveSnapshot("old")
        saveSnapshot("other", semesterId = TERM + 1)
        saveCustomPlan()
        repo.clearTimetable(SOURCE, TERM)
        assertTrue(repo.cachedSessions(SOURCE, TERM).isEmpty())
        assertTrue(repo.courses(TERM).isEmpty())
        assertTrue(repo.whpuPractice(SOURCE, TERM).isEmpty())
        assertNull(repo.lastFetchedAt(SOURCE, TERM))
        assertEquals(1, repo.customPlans(TERM).size)
        assertSnapshot("other", semesterId = TERM + 1)
    }

    @Test fun wrongSourceCannotClearExistingSnapshot() = runBlocking {
        saveSnapshot("old")
        val result = runCatching { repo.clearTimetable("whpu-demo", TERM) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertSnapshot("old")
        assertEquals("old", repo.courses(TERM).single().nameZh)
    }

    @Test fun concurrentRefreshesKeepCoursesAndPracticeFromSameSnapshot() = runBlocking {
        coroutineScope {
            val first = async { saveSnapshot("first") }
            val second = async { saveSnapshot("second") }
            first.await()
            second.await()
        }
        val name = repo.courses(TERM).single().nameZh
        assertTrue(name == "first" || name == "second")
        assertSnapshot(name)
        assertFalse(db.inTransaction())
    }

    private fun openRepository() {
        db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        repo = ScheduleRepository(db, AppGraph.easClient, AppGraph.authRepository)
    }

    private fun schedule(name: String): WhpuSchedule = WhpuScheduleParser.parse("""
        {"kbList":[{"kcmc":"$name","kch":"test-course","xqj":1,"jcs":"1-2","zcd":"1-2周"}],
         "sjkList":[{"kcmc":"$name practice","zcd":"3-4周"}]}
    """.trimIndent())

    private suspend fun saveSnapshot(
        name: String,
        source: String = SOURCE,
        semesterId: Int = TERM,
        fetchedAt: Long = 1000L,
    ) {
        val schedule = schedule(name)
        val sessions = WhpuScheduleMapper.sessions(schedule, semesterId, source)
        repo.saveTimetable(source, semesterId, sessions, WhpuScheduleMapper.courses(sessions), fetchedAt,
            JsonArray(schedule.practice.map { it.raw }).toString())
    }

    private suspend fun assertSnapshot(
        name: String,
        source: String = SOURCE,
        semesterId: Int = TERM,
        fetchedAt: Long = 1000L,
    ) {
        val expected = schedule(name)
        assertEquals(WhpuScheduleMapper.sessions(expected, semesterId, source), repo.cachedSessions(source, semesterId))
        assertEquals(expected.practice, repo.whpuPractice(source, semesterId))
        assertEquals(fetchedAt, repo.lastFetchedAt(source, semesterId))
    }

    private suspend fun saveCustomPlan(): String = repo.savePlan(PlanSpec(
        semesterId = TERM, name = "test custom plan", weekday = 2, startUnit = 3, endUnit = 4, weeks = listOf(1, 2),
    ))

    companion object {
        private const val SOURCE = "whpu"
        private const val TERM = 123
    }
}
