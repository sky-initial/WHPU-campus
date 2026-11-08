package com.zzupocket.app.ui.login

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column

@Composable
fun WhpuAccountChangeDialog(legacy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit, onBindLegacy: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (legacy) "确认旧版数据归属" else "检测到不同账号") },
        text = { Text(if (legacy && onBindLegacy != null)
            "旧版课表未记录账号。若旧课表属于当前登录账号，选择“属于当前账号，保留并绑定”，已有课表和显示修改会保留，再保存本次查询结果。若选择替换，会清除所有学期的旧学校课表、成绩和课程显示修改；自定义日程、演示课表和日期设置保留。"
            else "当前登录账号与本机学校数据所属账号不同。继续会重新获取数据，成功后清除旧账号所有学期的学校课表、成绩缓存和课程显示修改，再保存本次查询的数据；自定义日程、演示课表和日期设置保留。获取失败时不替换。") },
        confirmButton = {
            TextButton(onClick = if (legacy && onBindLegacy != null) onBindLegacy else onConfirm) {
                Text(if (legacy && onBindLegacy != null) "属于当前账号，保留并绑定" else "替换并导入")
            }
        },
        dismissButton = {
            Column {
                if (legacy && onBindLegacy != null) TextButton(onClick = onConfirm) { Text("其他账号，替换旧数据") }
                TextButton(onClick = onDismiss) { Text("取消，保留原数据") }
            }
        },
    )
}
