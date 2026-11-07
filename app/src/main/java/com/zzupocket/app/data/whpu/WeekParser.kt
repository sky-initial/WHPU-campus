package com.zzupocket.app.data.whpu

/** Parses the week expressions returned by the WHPU timetable. */
object WeekParser {
    private val segment = Regex("^(\\d+)(?:\\s*[-~－—]\\s*(\\d+))?\\s*周?\\s*(?:[（(]\\s*(单|双)\\s*[）)])?$")

    fun parse(value: String): Set<Int> {
        if (value.isBlank()) return emptySet()
        return value.split(',', '，', '、').flatMap { part ->
            val match = requireNotNull(segment.matchEntire(part.trim())) { "Invalid week expression" }
            val first = match.groupValues[1].toInt()
            val last = match.groupValues[2].ifEmpty { match.groupValues[1] }.toInt()
            require(first > 0 && last >= first) { "Invalid week range" }
            val parity = match.groupValues[3]
            (first..last).filter { week ->
                when (parity) {
                    "单" -> week % 2 == 1
                    "双" -> week % 2 == 0
                    else -> true
                }
            }
        }.toSortedSet()
    }
}
