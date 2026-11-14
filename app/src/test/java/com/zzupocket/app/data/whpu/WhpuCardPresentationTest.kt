package com.zzupocket.app.data.whpu

import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WhpuCardPresentationTest {
    @Test fun handoffOnlyAcceptsExactHttpsOrigin() {
        assertTrue(WhpuCardWebHandoff.allows("https://ecardapp.whpu.edu.cn/plat/shouyeUser"))
        listOf(null, "http://ecardapp.whpu.edu.cn/", "https://ecardapp.whpu.edu.cn:8443/",
            "https://ecardapp.whpu.edu.cn.evil.example/", "https://ecardapp.whpu.edu.cn@evil.example/",
            "https://user@ecardapp.whpu.edu.cn/", "file:///ecardapp.whpu.edu.cn", "https://cas.whpu.edu.cn/")
            .forEach { assertFalse(WhpuCardWebHandoff.allows(it)) }
    }
    @Test fun callbackDecodesOnlyExplicitStringFieldsWithoutSecretToString() {
        val callback = Json.encodeToString("""{"token":"synthetic-secret","type":"bearer"}""")
        val credentials = WhpuCardWebHandoff.decodeCallback(callback)
        assertEquals("synthetic-secret", credentials.token)
        assertFalse(credentials.toString().contains("synthetic-secret"))
        assertTrue(runCatching { WhpuCardWebHandoff.decodeCallback("null") }.exceptionOrNull() is WhpuCardException.SessionExpired)
        listOf("{}", "bad", Json.encodeToString("""{"token":123,"type":"bearer"}"""),
            Json.encodeToString("""{"token":"synthetic-secret","type":"basic"}"""))
            .forEach { assertTrue(runCatching { WhpuCardWebHandoff.decodeCallback(it) }.exceptionOrNull() is WhpuCardException.Parse) }
    }
    @Test fun cardTotalsUseSelectedCardAndBeijingDayWithoutFutureRows() {
        fun row(id: String, time: String, amount: Long, account: String = "a") =
            WhpuCardTransaction(account, id, amount, Instant.parse(time).toEpochMilli(), "人工")
        val cache = WhpuCardCache(WhpuCardOwner("101"), listOf(WhpuCard("a", "人工", 1000), WhpuCard("b", "人工", 2000)),
            listOf(row("1", "2026-01-04T16:00:00Z", -123), row("2", "2026-01-05T00:00:00Z", 500),
                row("3", "2026-01-04T15:59:59Z", -900), row("4", "2026-01-05T16:00:00Z", -800),
                row("5", "2026-01-05T00:00:00Z", -700, "b")), "a", 1000)
        val totals = cache.totals(LocalDate.of(2026, 1, 5))!!
        assertEquals(123L, totals.todaySpendFen)
        assertEquals(500L, totals.todayIncomeFen)
        assertEquals(2, totals.todayCount)
        assertEquals(123L, totals.weekSpendFen)
        assertEquals(1, totals.weekCount)
    }
    @Test fun completeEmptyCardHasZeroTotalsButMissingCardHasNoTotals() {
        val cache = WhpuCardCache(WhpuCardOwner("101"), listOf(WhpuCard("a", "人工", 0)), emptyList(), "a", 1000)
        assertEquals(0L, cache.totals()!!.todaySpendFen)
        assertNull(cache.copy(cards = emptyList(), selectedCardAccount = null).totals())
        assertEquals("-1.01", cardMoney(-101))
        assertEquals("0.00", cardMoney(0))
        assertEquals("2026-01-05 00:00", cardDateTime(Instant.parse("2026-01-04T16:00:00Z").toEpochMilli()))
    }
}
