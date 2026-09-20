package com.skyanchor.anynote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.core.compactDateTimeText
import com.skyanchor.anynote.ui.theme.AppColors
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** 数值步进选择器，用于时/分/秒。比系统 TimePicker 更直观，也不依赖额外组件库。 */
@Composable
private fun Stepper(label: String, value: Int, max: Int, onChange: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StepButton("▲") { onChange(if (value >= max) 0 else value + 1) }
        Box(
            Modifier
                .width(52.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.PrimarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                value.toString().padStart(2, '0'),
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.Primary,
            )
        }
        StepButton("▼") { onChange(if (value <= 0) max else value - 1) }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppColors.TextTertiary)
    }
}

@Composable
private fun StepButton(glyph: String, onChange: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onChange),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = AppColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 日期 + 时间选择对话框。时间精度到秒（基线 §5.2），UI 默认按分钟展示。
 */
@Composable
fun DateTimePickerDialog(
    title: String,
    initial: LocalDateTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalDateTime) -> Unit,
) {
    var date by remember { mutableStateOf(initial.toLocalDate()) }
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    var second by remember { mutableIntStateOf(initial.second) }
    var showCalendar by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextAction("确定") { onConfirm(LocalDateTime.of(date, LocalTime.of(hour, minute, second))) }
        },
        dismissButton = { TextAction("取消", color = AppColors.TextSecondary, onClick = onDismiss) },
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppColors.PrimarySoft)
                        .clickable { showCalendar = true }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Calendar, null, tint = AppColors.Primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(AppIcons.ChevronRight, null, tint = AppColors.TextTertiary, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Stepper("时", hour, 23) { hour = it }
                    Text(":", style = MaterialTheme.typography.headlineSmall, color = AppColors.TextTertiary)
                    Stepper("分", minute, 59) { minute = it }
                    Text(":", style = MaterialTheme.typography.headlineSmall, color = AppColors.TextTertiary)
                    Stepper("秒", second, 59) { second = it }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK_TIMES.forEach { (label, apply) ->
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = AppColors.Primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, AppColors.PrimarySoft, RoundedCornerShape(10.dp))
                                .clickable {
                                    val next = apply(LocalDateTime.now())
                                    date = next.toLocalDate()
                                    hour = next.hour
                                    minute = next.minute
                                    second = next.second
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        },
        containerColor = Color.White,
    )

    if (showCalendar) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showCalendar = false },
            confirmButton = {
                TextAction("确定") {
                    state.selectedDateMillis?.let {
                        date = LocalDate.ofEpochDay(it / 86_400_000L)
                    }
                    showCalendar = false
                }
            },
            dismissButton = { TextAction("取消", color = AppColors.TextSecondary) { showCalendar = false } },
        ) {
            DatePicker(state = state, showModeToggle = false)
        }
    }
}

private val QUICK_TIMES: List<Pair<String, (LocalDateTime) -> LocalDateTime>> = listOf(
    "10分钟后" to { now -> now.plusMinutes(10) },
    "1小时后" to { now -> now.plusHours(1) },
    "明早9点" to { now -> now.plusDays(1).toLocalDate().atTime(9, 0) },
)

/** 表单里的日期时间展示行。 */
@Composable
fun DateTimeField(value: LocalDateTime, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.PrimarySoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Clock, null, tint = AppColors.Primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            compactDateTimeText(value),
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(AppIcons.ChevronRight, null, tint = AppColors.TextTertiary, modifier = Modifier.size(16.dp))
    }
}
