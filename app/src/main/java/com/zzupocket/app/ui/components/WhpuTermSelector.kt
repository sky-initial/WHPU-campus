package com.zzupocket.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zzupocket.app.data.whpu.WhpuTerm

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhpuTermSelector(term: WhpuTerm, enabled: Boolean, onSelect: (WhpuTerm) -> Unit) {
    var choosingYear by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { choosingYear = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("${term.year}—${term.year + 1} 学年", modifier = Modifier.weight(1f))
        Icon(Icons.Default.ArrowDropDown, contentDescription = "选择学年")
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(term.code == "3", { onSelect(term.copy(code = "3")) }, label = { Text("第一学期") }, enabled = enabled)
        FilterChip(term.code == "12", { onSelect(term.copy(code = "12")) }, label = { Text("第二学期") }, enabled = enabled)
    }
    if (choosingYear) {
        val years = remember { (2199 downTo 2000).toList() }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (2199 - term.year - 1).coerceAtLeast(0))
        AlertDialog(
            onDismissRequest = { choosingYear = false },
            title = { Text("选择学年") },
            text = {
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 360.dp)) {
                    items(years, key = { it }) { year ->
                        TextButton(onClick = { onSelect(term.copy(year = year)); choosingYear = false },
                            modifier = Modifier.fillMaxWidth(), enabled = enabled) {
                            Text("$year—${year + 1} 学年", modifier = Modifier.weight(1f))
                            if (year == term.year) Text("已选")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingYear = false }) { Text("取消") } },
        )
    }
}
