package com.zzupocket.app.data.whpu

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class WhpuWebSessionTest {
    @Test fun academicReturnUsesHttpsAndPreservesEncodedPathAndQuery() {
        val original = "http://jwglxt.whpu.edu.cn:80/xtgl/login_slogin.html?ticket=synthetic%2Bvalue%3D&service=a%2Fb#tab"
        val upgraded = WhpuWebPolicy.academicHttpsRedirect(original, "GET", true)
        assertEquals("https://jwglxt.whpu.edu.cn/xtgl/login_slogin.html?ticket=synthetic%2Bvalue%3D&service=a%2Fb#tab", upgraded)
        assertTrue(WhpuWebPolicy.allows(requireNotNull(upgraded)))
        assertFalse(WhpuWebPolicy.allows(original))
    }

    @Test fun academicUpgradeDoesNotReplayPostsOrBroadenSchoolOrigins() {
        val original = "http://jwglxt.whpu.edu.cn/xtgl/login_slogin.html"
        assertNull(WhpuWebPolicy.academicHttpsRedirect(original, "POST", true))
        assertNull(WhpuWebPolicy.academicHttpsRedirect(original, "GET", false))
        listOf("https://jwglxt.whpu.edu.cn/", "http://cas.whpu.edu.cn/", "http://sec.whpu.edu.cn/",
            "http://jwglxt.whpu.edu.cn.evil.test/", "http://jwglxt.whpu.edu.cn:8080/",
            "http://synthetic:secret@jwglxt.whpu.edu.cn/", "javascript:synthetic").forEach {
            assertNull(WhpuWebPolicy.academicHttpsRedirect(it, "GET", true))
        }
    }

    @Test fun navigationDiagnosticOmitsCredentialsPathsAndTickets() {
        assertEquals("https://cas.whpu.edu.cn:8443", WhpuWebPolicy.navigationOrigin(
            "https://synthetic:secret@cas.whpu.edu.cn:8443/sensitive-path?ticket=synthetic#private"))
        assertEquals("http://jwglxt.whpu.edu.cn:80", WhpuWebPolicy.navigationOrigin(
            "http://jwglxt.whpu.edu.cn/?ticket=synthetic"))
        assertEquals("非 HTTP(S) 跳转", WhpuWebPolicy.navigationOrigin("custom:synthetic-secret"))
    }
    @Test fun navigationAcceptsOnlySchoolHttpsOrigins() {
        assertTrue(WhpuWebPolicy.allows(WhpuWebPolicy.LOGIN))
        assertTrue(WhpuWebPolicy.allows("https://cas.whpu.edu.cn/cas/login?service=test"))
        assertTrue(WhpuWebPolicy.allows("https://sec.whpu.edu.cn/"))
        listOf("http://jwglxt.whpu.edu.cn/", "https://jwglxt.whpu.edu.cn.evil.test/",
            "https://evil.test/?next=https://cas.whpu.edu.cn", "file:///test", "javascript:alert(1)",
            "https://jwglxt.whpu.edu.cn:8443/", "https://name@jwglxt.whpu.edu.cn/",
            "https://sec.whpu.edu.cn.evil.test/", "http://sec.whpu.edu.cn/",
            "https://sec.whpu.edu.cn:8443/").forEach {
            assertFalse(it, WhpuWebPolicy.allows(it))
        }
        assertFalse(WhpuWebPolicy.isAcademic("https://cas.whpu.edu.cn/cas/login"))
        assertFalse(WhpuWebPolicy.isAcademic("https://sec.whpu.edu.cn/"))
    }

    @Test fun browserCookieSnapshotIsLimitedToAcademicRequestsAndReplacesOldSession() {
        val jar = WhpuSessionManager()
        val endpoint = WhpuWebPolicy.SCHEDULE.toHttpUrl()
        jar.acceptBrowserSession("JSESSIONID=synthetic; gate=a=b", "test-browser")
        assertEquals(listOf("synthetic", "a=b"), jar.loadForRequest(endpoint).map { it.value })
        assertEquals("test-browser", jar.userAgent)
        assertTrue(jar.loadForRequest("https://cas.whpu.edu.cn/cas/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://jwglxt.whpu.edu.cn/xtgl/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://jwglxt.whpu.edu.cn/kbcx/".toHttpUrl()).isEmpty())
        jar.acceptBrowserSession("JSESSIONID=next", "test-browser-next")
        assertEquals(listOf("next"), jar.loadForRequest(endpoint).map { it.value })
        jar.clear()
        assertTrue(jar.loadForRequest(endpoint).isEmpty())
        assertNull(jar.userAgent)
    }

    @Test fun userSuppliedClockHasElevenPeriodsWithoutDemoOverrides() {
        val periods = WhpuCalendar.periods()
        assertEquals(11, periods.size)
        assertEquals("10:10", periods[2].startText)
        assertEquals("11:10", periods[3].startText)
        assertEquals("18:15", periods[8].startText)
        assertEquals("20:50", periods.last().endText)
        assertEquals("Asia/Shanghai", WhpuCalendar.zone.id)
    }

    @Test fun termIdsAreSeparateFromDemoAndRoundTripBothSemesterCodes() {
        listOf(WhpuTerm(2026, "3"), WhpuTerm(2026, "12"), WhpuTerm(2027, "3")).forEach {
            assertEquals(it, WhpuTerm.fromSemesterId(it.semesterId))
        }
        assertNull(WhpuTerm.fromSemesterId(WhpuScheduleMapper.DEMO_SEMESTER_ID))
        assertNull(WhpuTerm.fromSemesterId(123))
    }
}
