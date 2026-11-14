package com.zzupocket.app.data.whpu

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

fun cardMoney(fen: Long): String = BigDecimal.valueOf(fen, 2).toPlainString()
fun cardDateTime(millis: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(WhpuCalendar.zone).format(Instant.ofEpochMilli(millis))

data class WhpuCardTotals(val todaySpendFen: Long, val todayIncomeFen: Long,
    val todayCount: Int, val weekSpendFen: Long, val weekCount: Int)

fun WhpuCardCache.totals(today: LocalDate = WhpuCalendar.today()): WhpuCardTotals? {
    val card = selectedCard ?: return null
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val records = transactionsFor(card.account).map {
        it to Instant.ofEpochMilli(it.displayedAt).atZone(WhpuCalendar.zone).toLocalDate()
    }
    val todayRows = records.filter { it.second == today }.map { it.first }
    val weekSpends = records.filter { it.second in weekStart..today && it.first.amountFen < 0 }.map { it.first }
    return WhpuCardTotals(todayRows.filter { it.amountFen < 0 }.sumOf { -it.amountFen },
        todayRows.filter { it.amountFen > 0 }.sumOf { it.amountFen }, todayRows.size,
        weekSpends.sumOf { -it.amountFen }, weekSpends.size)
}
