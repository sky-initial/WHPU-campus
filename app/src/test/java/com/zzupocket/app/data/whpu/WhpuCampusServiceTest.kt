package com.zzupocket.app.data.whpu

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class WhpuCampusServiceTest {
    @Test fun mobilePortalKeepsAndroidAndInstalledEngineVersion() {
        val mobile = "Mozilla/5.0 (Linux; Android 16; Device Build/TEST; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/154.0.8037.106 Mobile Safari/537.36"
        val browser = campusMobileUserAgent(mobile)
        assertTrue(browser.contains("Chrome/154.0.8037.106"))
        assertTrue(browser.contains("Android 16"))
        assertTrue(browser.contains("Mobile Safari/537.36"))
        assertFalse(browser.contains("X11"))
        assertFalse(browser.contains("; wv"))
        assertFalse(browser.contains("Version/4.0"))
        assertEquals(browser, campusMobileUserAgent(browser))
    }
    @Test fun tabletUsesMobileCardRoutingWithoutChangingItsEngineVersion() {
        val tablet = "Mozilla/5.0 (Linux; Android 16; Tablet; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/154.0.8037.106 Safari/537.36"
        val browser = campusMobileUserAgent(tablet)
        assertTrue(browser.contains("Android 16; Tablet"))
        assertTrue(browser.contains("Chrome/154.0.8037.106 Mobile Safari/537.36"))
        assertEquals(browser, campusMobileUserAgent(browser))
    }
    @Test fun entriesAreAllowedAndContainNoSessionOrFixedDate() {
        WhpuCampusService.entries.forEach { service ->
            assertTrue(service.allows(service.entry))
            val keys = service.entry.toHttpUrl().queryParameterNames
            assertFalse(keys.any { it in setOf("wfw_token", "token", "ticket", "day", "synjones-auth") })
        }
    }
    @Test fun cardAndElectricityShareOnlyVerifiedPortalHandoffs() {
        listOf(WhpuCampusService.CARD, WhpuCampusService.ELECTRICITY).forEach {
            assertTrue(it.allows("https://cas.whpu.edu.cn/cas/login"))
            assertTrue(it.allows("https://sec.whpu.edu.cn/"))
            assertTrue(it.allows("https://ecardapp.whpu.edu.cn/plat/shouyeUser"))
            assertFalse(it.allows("https://jwglxt.whpu.edu.cn/"))
            assertFalse(it.allows(SEAT_ENTRY))
        }
    }
    @Test fun navigationDoesNotAllowLookalikeHostsCredentialsPortsOrCleartext() {
        val service = WhpuCampusService.SEAT
        assertTrue(service.allows("https://passport2.chaoxing.com/login"))
        assertFalse(service.allows("https://passport2.chaoxing.com.evil.example/login"))
        listOf("https://office.chaoxing.com.evil.example/", "https://evil.example/?next=office.chaoxing.com",
            "https://user:pass@office.chaoxing.com/", "https://office.chaoxing.com:444/",
            "http://office.chaoxing.com/", "javascript:alert(1)", "file:///data/local/tmp/test").forEach {
            assertFalse(it, service.allows(it))
        }
    }
    @Test fun libraryCleartextIsExternalOnlyAndDiagnosticsOmitQueries() {
        WhpuCampusService.entries.forEach { assertFalse(it.allows(LIBRARY_ENTRY)) }
        assertEquals("https://office.chaoxing.com:443", WhpuWebPolicy.navigationOrigin("https://office.chaoxing.com/path?token=synthetic"))
    }
}
