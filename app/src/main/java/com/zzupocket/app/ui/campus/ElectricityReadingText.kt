package com.zzupocket.app.ui.campus

import com.zzupocket.app.data.whpu.WhpuElectricityReadingKind
import com.zzupocket.app.data.whpu.WhpuElectricityValue

internal val WhpuElectricityValue.title: String
    get() = when (kind) {
        WhpuElectricityReadingKind.YUAN -> "电费余额"
        WhpuElectricityReadingKind.SCHOOL_REMAINING -> "当前剩余电量"
    }

internal val WhpuElectricityValue.unit: String
    get() = if (kind == WhpuElectricityReadingKind.YUAN) "元" else ""

internal val WhpuElectricityValue.unitNote: String
    get() = if (kind == WhpuElectricityReadingKind.SCHOOL_REMAINING) "学校未标注单位" else ""

internal val WhpuElectricityValue.formatted: String
    get() = if (kind == WhpuElectricityReadingKind.YUAN) "¥ $number" else number
