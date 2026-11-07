package com.zzupocket.app.data.whpu

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhpuAcademicRepositoryTest {
    @Test fun savesOnlyAfterSuccessfulFetch() = runTest {
        var saved = false
        val provider = MockWhpuScheduleProvider()
        val repo = WhpuAcademicRepository(provider) { source, semester, sessions, _, _ ->
            saved = true
            assertEquals(WhpuAcademicRepository.SOURCE, source)
            assertEquals(123, semester)
            assertTrue(sessions.isNotEmpty())
        }
        repo.refresh(WhpuTerm.YEAR_2026_FIRST, 123)
        assertTrue(saved)
    }

    @Test fun failedFetchDoesNotReplaceCache() = runTest {
        var saved = false
        val provider = object : WhpuScheduleProvider {
            override fun schedule(term: WhpuTerm): WhpuSchedule = throw IllegalStateException("offline")
        }
        val repo = WhpuAcademicRepository(provider) { _, _, _, _, _ -> saved = true }
        val result = runCatching { repo.refresh(WhpuTerm.YEAR_2026_FIRST, 123) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertFalse(saved)
    }

    @Test fun malformedCourseDoesNotReplaceCache() = runTest {
        var saved = false
        val provider = object : WhpuScheduleProvider {
            override fun schedule(term: WhpuTerm): WhpuSchedule = WhpuScheduleParser.parse(
                """{"kbList":[{"kcmc":"示例课程","xqj":1,"jcs":"1-2"}]}""",
            )
        }
        val repo = WhpuAcademicRepository(provider) { _, _, _, _, _ -> saved = true }
        val result = runCatching { repo.refresh(WhpuTerm.YEAR_2026_FIRST, 123) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertFalse(saved)
    }

    @Test(expected = IllegalArgumentException::class)
    fun realSourceCannotOverwriteDemo() = runTest {
        val repo = WhpuAcademicRepository(MockWhpuScheduleProvider()) { _, _, _, _, _ -> }
        repo.refresh(WhpuTerm.YEAR_2026_FIRST, WhpuScheduleMapper.DEMO_SEMESTER_ID)
    }
}
