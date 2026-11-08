package com.zzupocket.app.data.whpu

/** Return a whole term, never a partial successful prefix of a failed refresh. */
internal suspend fun collectWhpuGradePages(load: suspend (Int) -> WhpuGradePage): List<WhpuGrade> {
    val first = load(1)
    require(first.currentPage == 1) { "Unexpected grade page" }
    if (first.totalRecords == 0) {
        require(first.grades.isEmpty() && first.totalPages in 0..1) { "Inconsistent empty grades" }
        return emptyList()
    }
    require(first.totalPages in 1..first.totalRecords && first.grades.isNotEmpty()) { "Invalid grade page count" }
    val result = first.grades.toMutableList()
    for (page in 2..first.totalPages) {
        val next = load(page)
        require(next.currentPage == page && next.totalPages == first.totalPages &&
            next.totalRecords == first.totalRecords && next.grades.isNotEmpty()) { "Grade pagination changed" }
        result.addAll(next.grades)
    }
    require(result.size == first.totalRecords) { "Incomplete grade response" }
    return result
}
