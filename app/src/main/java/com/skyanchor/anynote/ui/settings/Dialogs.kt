package com.skyanchor.anynote.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.skyanchor.anynote.ui.components.OptionRow
import com.skyanchor.anynote.ui.components.TextAction
import com.skyanchor.anynote.ui.theme.AppColors

/** 危险操作或需要一句话说明的确认框。 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    danger: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextAction(
                confirmLabel,
                color = if (danger) AppColors.Danger else AppColors.Primary,
                onClick = onConfirm,
            )
        },
        dismissButton = { TextAction("取消", color = AppColors.TextSecondary, onClick = onDismiss) },
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary) },
        containerColor = Color.White,
    )
}

/** 单选项列表：稍后提醒时长、默认优先级、默认分类等都复用它。 */
@Composable
fun <T> ChoiceDialog(
    title: String,
    text: String? = null,
    options: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextAction("关闭", color = AppColors.TextSecondary, onClick = onDismiss) },
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                if (text != null) {
                    Text(text, style = MaterialTheme.typography.bodySmall, color = AppColors.TextTertiary)
                }
                options.forEach { (value, label) ->
                    OptionRow(label, value == selected) {
                        onPick(value)
                        onDismiss()
                    }
                }
            }
        },
        containerColor = Color.White,
    )
}

/** 单行文本输入：新建 / 重命名分类。 */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextAction("确定", color = if (value.isBlank()) AppColors.TextTertiary else AppColors.Primary) {
                if (value.isNotBlank()) onConfirm(value.trim())
            }
        },
        dismissButton = { TextAction("取消", color = AppColors.TextSecondary, onClick = onDismiss) },
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            TextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text(label, color = AppColors.TextTertiary) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppColors.TextPrimary),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = AppColors.Primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        containerColor = Color.White,
    )
}
