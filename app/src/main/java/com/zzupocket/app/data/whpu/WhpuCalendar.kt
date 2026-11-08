package com.zzupocket.app.data.whpu

import com.zzupocket.app.domain.Period
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Timetable times supplied by the user on 2026-11-08; the week-one date is always user input. */
object WhpuCalendar {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    fun today(): LocalDate = LocalDate.now(zone)
    fun now(): LocalTime = LocalTime.now(zone)

    fun periods(): List<Period> = listOf(
        800 to 845, 855 to 940, 1010 to 1055, 1110 to 1155,
        1400 to 1445, 1455 to 1540, 1600 to 1645, 1655 to 1740,
        1815 to 1900, 1910 to 1955, 2005 to 2050,
    ).mapIndexed { index, (start, end) ->
        Period(index + 1, "第${index + 1}节", start, end,
            if (index < 4) "MORNING" else if (index < 8) "AFTERNOON" else "EVENING", index / 2 + 1)
    }
}
