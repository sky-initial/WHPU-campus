package com.zzupocket.app.ui.components

import android.widget.NumberPicker
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
fun WeekStartWheelDialog(
    initialDate: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: (() -> Unit)? = null,
    requiredDay: DayOfWeek = DayOfWeek.MONDAY,
    saving: Boolean = false,
    error: String? = null,
) {
    var epochDay by rememberSaveable(initialDate) { mutableStateOf(initialDate.toEpochDay()) }
    val selected = LocalDate.ofEpochDay(epochDay)
    val weekday = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss?.invoke() },
        title = { Text("设置第一周${weekday[requiredDay.value - 1]}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("课表已保存在本机。滑动选择开学日期，用于计算教学周、今日安排和桌面小组件。")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    NumberWheel("年", selected.year, 2000, 2199, Modifier.weight(1f), !saving) {
                        epochDay = selected.withYear(it).toEpochDay()
                    }
                    NumberWheel("月", selected.monthValue, 1, 12, Modifier.weight(1f), !saving) {
                        epochDay = selected.withMonth(it).toEpochDay()
                    }
                    NumberWheel("日", selected.dayOfMonth, 1, selected.lengthOfMonth(), Modifier.weight(1f), !saving) {
                        epochDay = selected.withDayOfMonth(it).toEpochDay()
                    }
                }
                Text("$selected · ${weekday[selected.dayOfWeek.value - 1]}")
                if (selected.dayOfWeek != requiredDay) {
                    Text("请选择第一周的${weekday[requiredDay.value - 1]}。", color = MaterialTheme.colorScheme.error)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && selected.dayOfWeek == requiredDay,
                onClick = { onConfirm(selected) }) { Text(if (saving) "保存中…" else "保存日期") }
        },
        dismissButton = {
            if (onDismiss != null) TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun NumberWheel(label: String, value: Int, min: Int, max: Int, modifier: Modifier,
    enabled: Boolean, onChange: (Int) -> Unit) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(148.dp),
            factory = { context -> NumberPicker(context).apply {
                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            } },
            update = { picker ->
                picker.setOnValueChangedListener(null)
                picker.minValue = min
                picker.maxValue = max
                picker.value = value
                picker.wrapSelectorWheel = false
                picker.isEnabled = enabled
                picker.contentDescription = label
                picker.setTextColor(textColor)
                picker.setOnValueChangedListener { _, _, newValue -> onChange(newValue) }
            },
        )
    }
}
