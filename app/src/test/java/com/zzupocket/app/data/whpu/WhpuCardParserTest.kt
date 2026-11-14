package com.zzupocket.app.data.whpu

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal object CardFixture {
    fun response(data: String) = """{"code":200,"success":true,"data":$data}"""
    fun owner(id: Int = 101) = response("""{"id":$id,"name":"人工姓名","account":"synthetic-user"}""")
    fun config(type: String = "1") = response("""{"getEcardConfig":"{\"type\":\"$type\"}"}""")
    fun card(account: String = "test-card") = """{"account":"$account","cardname":"示例卡",
        "elec_accamt":1234,"db_balance":5000,"unsettle_amount":-100,"name":"人工姓名"}"""
    fun cards(rows: String = card()) = response("""{"retcode":"0","card":[$rows]}""")
    fun row(id: String = "test-order", account: String = "test-card") = """{"fromAccount":"$account",
        "orderId":"$id","tranamt":1200,"typeFrom":"2","resume":"示例商户",
        "effectdateStr":"2026-01-02 08:30:00","jndatetimeStr":"2026-01-02 08:00:00",
        "userName":"人工姓名","sno":"synthetic-student"}"""
    fun page(rows: String = row(), current: Int = 1, size: Int = 8, pages: Int = 1, total: Int = 1) =
        response("""{"current":$current,"size":$size,"pages":$pages,"total":$total,"records":[$rows]}""")
}

class WhpuCardParserTest {
    private fun parse(body: String) = WhpuCardParser.page(body, setOf("test-card", "other-card"))
    private fun rejects(block: () -> Unit) = assertTrue(runCatching(block).exceptionOrNull() is WhpuCardException.Parse)
    private val transaction get() = parse(CardFixture.page()).records.single()

    @Test fun electronicBalanceDoesNotUsePhysicalBalance() {
        assertEquals(1234L, WhpuCardParser.cards(CardFixture.cards(), "1").single().balanceFen)
        assertEquals(4900L, WhpuCardParser.cards(CardFixture.cards(), "2").single().balanceFen)
    }
    @Test fun acceptsVerifiedConfigAndRejectsUnknownMode() {
        assertEquals("1", WhpuCardParser.balanceType(CardFixture.config()))
        rejects { WhpuCardParser.balanceType(CardFixture.config("unknown")) }
    }
    @Test fun directionUsesTypeAndTimeUsesSchoolTimezoneAndEffectiveTime() {
        assertEquals(-1200L, transaction.amountFen)
        assertEquals(Instant.parse("2026-01-02T00:30:00Z").toEpochMilli(), transaction.displayedAt)
        val income = CardFixture.page().replace("\"typeFrom\":\"2\"", "\"typeFrom\":\"1\"")
        assertEquals(1200L, parse(income).records.single().amountFen)
    }
    @Test fun missingEffectiveTimeUsesJournalTime() {
        val body = CardFixture.page().replace("\"2026-01-02 08:30:00\"", "null")
        assertEquals(Instant.parse("2026-01-02T00:00:00Z").toEpochMilli(), parse(body).records.single().displayedAt)
    }
    @Test fun invalidTimeDoesNotBecomeEpochOrFallBackToOtherField() {
        listOf("2026-02-30 08:30:00", "2026-01-02 25:30:00", "bad").forEach { value ->
            rejects { parse(CardFixture.page().replace("2026-01-02 08:30:00", value)) }
        }
    }
    @Test fun malformedFractionalNegativeAndOverflowAmountsFail() {
        listOf("null", "\"bad\"", "0.5", "-1", "9223372036854775808").forEach {
            rejects { parse(CardFixture.page().replace("\"tranamt\":1200", "\"tranamt\":$it")) }
        }
        assertEquals(0L, parse(CardFixture.page().replace("\"tranamt\":1200", "\"tranamt\":0")).records.single().amountFen)
    }
    @Test fun unknownDirectionAndUnknownCardFail() {
        rejects { parse(CardFixture.page().replace("\"typeFrom\":\"2\"", "\"typeFrom\":\"9\"")) }
        rejects { parse(CardFixture.page(CardFixture.row(account = "unowned-card"))) }
    }
    @Test fun doesNotRetainStudentProfileOrRawRecord() {
        assertEquals(WhpuCardOwner("101"), WhpuCardParser.owner(CardFixture.owner()))
        val retained = transaction.toString() + WhpuCardParser.cards(CardFixture.cards(), "1")
        assertFalse(retained.contains("人工姓名"))
        assertFalse(retained.contains("synthetic-student"))
    }
    @Test fun emptyCardsRequireSuccessfulExplicitArray() {
        assertTrue(WhpuCardParser.cards(CardFixture.cards(""), "1").isEmpty())
        rejects { WhpuCardParser.cards(CardFixture.response("""{"retcode":"0"}"""), "1") }
        rejects { WhpuCardParser.cards(CardFixture.cards().replace("\"retcode\":\"0\"", "\"retcode\":\"1\""), "1") }
        rejects { WhpuCardParser.cards(CardFixture.cards(CardFixture.card() + "," + CardFixture.card()), "1") }
    }
    @Test fun badEnvelopeAndHtmlAreNotSuccessfulEmptyResults() {
        listOf("<html>maintenance</html>", "{}", CardFixture.page().replace("\"success\":true", "\"success\":false"))
            .forEach { rejects { parse(it) } }
        assertTrue(runCatching { parse("""{"code":401}""") }.exceptionOrNull() is WhpuCardException.SessionExpired)
        assertTrue(runCatching { parse("""{"code":500}""") }.exceptionOrNull() is WhpuCardException.Business)
    }
    @Test fun collectsFullPagesAndShortLastPage() = runTest {
        val requested = mutableListOf<Int>()
        val result = collectWhpuCardPages { n ->
            requested += n
            WhpuCardPage(n, 2, 2, 3, if (n == 1) listOf(transaction, transaction.copy(orderId = "two"))
                else listOf(transaction.copy(orderId = "three")))
        }
        assertEquals(listOf(1, 2), requested)
        assertEquals(3, result.size)
    }
    @Test fun verifiedEmptyResultCompletesButInconsistentEmptyFails() = runTest {
        assertTrue(collectWhpuCardPages { parse(CardFixture.page("", pages = 0, total = 0)) }.isEmpty())
        assertTrue(runCatching { collectWhpuCardPages { parse(CardFixture.page("", total = 0)) } }
            .exceptionOrNull() is WhpuCardException.Parse)
    }
    @Test fun laterUnauthorizedAndCancellationNeverReturnPartialList() = runTest {
        for (failure in listOf(WhpuCardException.SessionExpired(), CancellationException())) {
            val result = runCatching { collectWhpuCardPages { n ->
                if (n == 2) throw failure
                WhpuCardPage(1, 1, 2, 2, listOf(transaction))
            } }
            assertSame(failure, result.exceptionOrNull())
        }
    }
    @Test fun pageLimitRejectsBeforeFetchingUnneededPages() = runTest {
        val requested = mutableListOf<Int>()
        val failure = runCatching { collectWhpuCardPages(maxPages = 1) { n ->
            requested += n
            WhpuCardPage(1, 1, 2, 2, listOf(transaction))
        } }.exceptionOrNull()
        assertTrue(failure is WhpuCardException.Parse)
        assertEquals(listOf(1), requested)
    }
    @Test fun changingTotalsRepeatedPagesShortPagesAndDuplicateRowsFail() = runTest {
        for (mode in 0..3) {
            val result = runCatching { collectWhpuCardPages { n ->
                if (n == 1) WhpuCardPage(1, 1, 2, 2, listOf(transaction)) else when (mode) {
                    0 -> WhpuCardPage(2, 1, 2, 3, listOf(transaction.copy(orderId = "two")))
                    1 -> WhpuCardPage(1, 1, 2, 2, listOf(transaction.copy(orderId = "two")))
                    2 -> WhpuCardPage(2, 1, 2, 2, emptyList())
                    else -> WhpuCardPage(2, 1, 2, 2, listOf(transaction))
                }
            } }
            assertTrue(result.exceptionOrNull() is WhpuCardException.Parse)
        }
    }
    @Test fun sameOrderIdOnDifferentCardsRemainsSeparate() = runTest {
        val result = collectWhpuCardPages {
            WhpuCardPage(1, 8, 1, 2, listOf(transaction, transaction.copy(cardAccount = "other-card")))
        }
        assertEquals(2, result.size)
    }
}
