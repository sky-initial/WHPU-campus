package com.zzupocket.app.data.whpu

internal suspend fun collectWhpuGradePages(load: suspend (Int) -> WhpuGradePage): List<WhpuGrade> =
    collectWhpuAcademicPages { page ->
        val result = load(page)
        WhpuAcademicPage(result.currentPage, result.totalPages, result.totalRecords, result.grades)
    }
