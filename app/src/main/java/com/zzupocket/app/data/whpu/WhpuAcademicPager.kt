package com.zzupocket.app.data.whpu

data class WhpuAcademicPage<T>(val currentPage: Int, val totalPages: Int, val totalRecords: Int, val items: List<T>)

/** A successful response is a complete term, never the prefix of a failed paginated request. */
internal suspend fun <T> collectWhpuAcademicPages(load: suspend (Int) -> WhpuAcademicPage<T>): List<T> {
    val first = load(1)
    require(first.currentPage == 1) { "Unexpected academic page" }
    if (first.totalRecords == 0) {
        require(first.items.isEmpty() && first.totalPages in 0..1) { "Inconsistent empty result" }
        return emptyList()
    }
    require(first.totalPages in 1..first.totalRecords && first.items.isNotEmpty()) { "Invalid academic page count" }
    val result = first.items.toMutableList()
    for (page in 2..first.totalPages) {
        val next = load(page)
        require(next.currentPage == page && next.totalPages == first.totalPages &&
            next.totalRecords == first.totalRecords && next.items.isNotEmpty()) { "Academic pagination changed" }
        result.addAll(next.items)
    }
    require(result.size == first.totalRecords) { "Incomplete academic response" }
    return result
}
