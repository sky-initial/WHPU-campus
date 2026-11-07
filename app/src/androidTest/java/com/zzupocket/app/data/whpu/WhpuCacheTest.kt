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
