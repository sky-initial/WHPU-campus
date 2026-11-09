package com.zzupocket.app.data.whpu

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.repo.ScheduleRepository
import com.zzupocket.app.domain.PlanSpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WhpuPeriodCacheTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository
    private lateinit var name: String
    private val first = WhpuTerm(2030, "3")
    private val second = WhpuTerm(2030, "12")
    private val custom = WhpuPeriodSettings.defaults().toMutableList().apply { this[0] = WhpuPeriodTime(810, 850) }
    private fun schedule(owner: String = "synthetic-a") = WhpuScheduleParser.parse("""{"kbList":[
        {"kcmc":"人工课程","kch":"SYN-01","xqj":1,"jcs":"1-2","zcd":"1-2周"}],
        "xsxx":{"XH_ID":"$owner"}}""")

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "whpu-period-test-${UUID.randomUUID()}.db"
        open()
    }
    private fun open() {
        db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        repo = ScheduleRepository(db, AppGraph.easClient, AppGraph.authRepository)
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }

    @Test fun customTimesSurviveReopenAndStaySeparateFromOtherTermsAndDemo() = runBlocking {
        repo.ensureDemoTimetable()
        val demo = repo.periodsForSemester(WhpuScheduleMapper.DEMO_SEMESTER_ID)
        repo.saveWhpuPeriods(first.semesterId, custom)
        db.close(); open()
        assertEquals(WhpuPeriodSettings.periods(custom), repo.periodsForSemester(first.semesterId))
        assertEquals(WhpuCalendar.periods(), repo.periodsForSemester(second.semesterId))
        assertEquals(demo, repo.periodsForSemester(WhpuScheduleMapper.DEMO_SEMESTER_ID))
    }
    @Test fun reimportKeepsTimesAndUpdatesSchoolCustomAndDetailReads() = runBlocking {
        repo.importWhpuAccountTimetable(first, schedule())
        repo.savePlan(PlanSpec(semesterId = first.semesterId, name = "人工日程", weekday = 1,
            startUnit = 1, endUnit = 2, weeks = listOf(1)))
        repo.saveWhpuPeriods(first.semesterId, custom)
        repo.importWhpuAccountTimetable(first, schedule())
        assertTrue(repo.sessionsOfSemester(first.semesterId).all { it.startTime == "08:10" && it.endTime == "09:40" })
        assertTrue(repo.sessionsOn(first.semesterId, java.time.LocalDate.of(2030, 9, 2), 1).all { it.startTime == "08:10" })
        assertTrue(repo.courses(first.semesterId).flatMap { it.sessions }.all { it.startTime == "08:10" })
    }
    @Test fun accountChangePreservesUserPeriodSettings() = runBlocking {
        repo.importWhpuAccountTimetable(first, schedule())
        repo.saveWhpuPeriods(first.semesterId, custom)
        repo.importWhpuAccountTimetable(first, schedule("synthetic-b"), replaceAccount = true)
        assertEquals(WhpuPeriodSettings.periods(custom), repo.periodsForSemester(first.semesterId))
        assertTrue(repo.sessionsOfSemester(first.semesterId).all { it.startTime == "08:10" })
    }
    @Test fun invalidSaveDoesNotReplaceExistingTimes() = runBlocking {
        repo.saveWhpuPeriods(first.semesterId, custom)
        assertTrue(runCatching { repo.saveWhpuPeriods(first.semesterId, listOf(WhpuPeriodTime(800, 900))) }.isFailure)
        assertTrue(runCatching { repo.saveWhpuPeriods(WhpuScheduleMapper.DEMO_SEMESTER_ID, custom) }.isFailure)
        assertEquals(WhpuPeriodSettings.periods(custom), repo.periodsForSemester(first.semesterId))
    }
    @Test fun resetOnlyAffectsSelectedTermAndKeepsExamTime() = runBlocking {
        repo.saveWhpuPeriods(first.semesterId, custom)
        repo.saveWhpuPeriods(second.semesterId, custom)
        val exams = WhpuExamRepository(db, repo, WhpuExamSource {
            WhpuExams(it, WhpuAccountId("synthetic-a"), listOf(WhpuExam("人工考试", "SYN-01",
                "2030-12-10(09:00-11:00)", null, null, null, null, null, null, null)))
        }, WhpuSessionManager())
        val saved = exams.refresh(first)
        repo.saveWhpuPeriods(first.semesterId, WhpuPeriodSettings.defaults())
        assertEquals(WhpuCalendar.periods(), repo.periodsForSemester(first.semesterId))
        assertEquals(WhpuPeriodSettings.periods(custom), repo.periodsForSemester(second.semesterId))
        assertEquals(saved, exams.cached(first))
    }
    @Test fun failedWriteKeepsLastSavedTimes() = runBlocking {
        repo.saveWhpuPeriods(first.semesterId, custom)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_period BEFORE INSERT ON kv_cache
            WHEN NEW.key LIKE 'whpu-periods:%' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END""")
        val other = custom.toMutableList().apply { this[0] = WhpuPeriodTime(815, 850) }
        assertTrue(runCatching { repo.saveWhpuPeriods(first.semesterId, other) }.isFailure)
        assertEquals(WhpuPeriodSettings.periods(custom), repo.periodsForSemester(first.semesterId))
    }
}
