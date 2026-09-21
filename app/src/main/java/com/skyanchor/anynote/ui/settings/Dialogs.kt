package com.skyanchor.anynote.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.data.SettingsStore
import com.skyanchor.anynote.reminder.DeviceProfiles
import com.skyanchor.anynote.ui.components.AppDialogTitle
import com.skyanchor.anynote.ui.components.OptionRow
import com.skyanchor.anynote.ui.components.SpacerHeight
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
        title = { AppDialogTitle(title) },
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
        title = { AppDialogTitle(title) },
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

/** 弹窗内的输入框：浅灰底 + 圆角描边，聚焦时描边变主题色，让用户一眼看出可以点击输入。 */
@Composable
private fun DialogInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    suffix: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(InputFill)
            .border(1.4.dp, if (focused) AppColors.Primary else InputBorder, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text(label, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextTertiary) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppColors.TextPrimary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
                cursorColor = AppColors.Primary,
            ),
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
        )
        if (suffix != null) {
            Text(
                suffix,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
    }
}

private val InputFill = Color(0xFFF4F6FA)
private val InputBorder = Color(0xFFD9E1EC)

/** 稍后提醒时长选择：固定预设 + 自定义分钟（基线 §9）。 */
@Composable
fun SnoozeDurationDialog(
    title: String,
    text: String? = null,
    selected: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    var customizing by remember { mutableStateOf(false) }
    // 已经是自定义值时预填进去，改起来只动数字；预设值则留空，避免"看起来选中了预设"的歧义
    var input by remember { mutableStateOf(if (selected in SettingsStore.SNOOZE_PRESETS) "" else selected.toString()) }
    val minutes = input.trim().toIntOrNull()
    val valid = minutes != null && minutes in 1..MAX_SNOOZE_MINUTES
    val isCustomSelected = selected !in SettingsStore.SNOOZE_PRESETS
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (customizing) {
                TextAction(
                    "确定",
                    color = if (valid) AppColors.Primary else AppColors.TextTertiary,
                ) {
                    if (valid) {
                        onPick(minutes!!)
                        onDismiss()
                    }
                }
            } else {
                TextAction("关闭", color = AppColors.TextSecondary, onClick = onDismiss)
            }
        },
        dismissButton = if (customizing) {
            { TextAction("返回", color = AppColors.TextSecondary, onClick = { customizing = false }) }
        } else {
            null
        },
        title = { AppDialogTitle(title) },
        text = {
            Column {
                if (text != null) {
                    Text(text, style = MaterialTheme.typography.bodySmall, color = AppColors.TextTertiary)
                }
                if (!customizing) {
                    SettingsStore.SNOOZE_PRESETS.forEach { preset ->
                        OptionRow("$preset 分钟后", preset == selected) {
                            onPick(preset)
                            onDismiss()
                        }
                    }
                    OptionRow(
                        "自定义分钟",
                        isCustomSelected,
                        subtitle = if (isCustomSelected) "当前 $selected 分钟" else "可输入 1 - $MAX_SNOOZE_MINUTES 分钟",
                        onClick = { customizing = true },
                    )
                } else {
                    DialogInputField(
                        value = input,
                        onValueChange = { input = it.filter(Char::isDigit).take(4) },
                        label = "输入分钟数",
                        keyboardType = KeyboardType.Number,
                        suffix = "分钟",
                    )
                    SpacerHeight(6)
                    if (input.isNotEmpty() && !valid) {
                        Text(
                            "请输入 1 - $MAX_SNOOZE_MINUTES 之间的整数分钟",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.Danger,
                        )
                    }
                }
            }
        },
        containerColor = Color.White,
    )
}

private const val MAX_SNOOZE_MINUTES = 24 * 60

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
        title = { AppDialogTitle(title) },
        text = {
            DialogInputField(
                value = value,
                onValueChange = { value = it },
                label = label,
            )
        },
        containerColor = Color.White,
    )
}

/**
 * 「保存提醒后」的一次性后台运行引导（基线 §24、§31）。
 * 提醒由系统闹钟投递、不依赖进程存活，但省电策略把应用列入管控时，锁屏或清掉后台后
 * 到点的提醒可能被延后甚至拦截。这一步必须在用户"刚设好提醒"时出现，
 * 藏在设置页里等于没有——调用方负责用 SettingsStore.backgroundHintShown 保证只弹一次。
 */
@Composable
fun BackgroundRunDialog(onGo: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextAction("去允许", onClick = onGo) },
        dismissButton = { TextAction("暂不", color = AppColors.TextSecondary, onClick = onDismiss) },
        title = { AppDialogTitle("让提醒在退出应用后照常响起") },
        text = {
            Text(
                "提醒由系统闹钟负责，随记不需要常驻后台。但若系统把随记列入省电管控，" +
                    "锁屏或清掉后台后，到点的提醒可能被延后甚至拦截，尤其是国产 ROM。\n\n" +
                    DeviceProfiles.manualHint(DeviceProfiles.current()),
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
            )
        },
        containerColor = Color.White,
    )
}
