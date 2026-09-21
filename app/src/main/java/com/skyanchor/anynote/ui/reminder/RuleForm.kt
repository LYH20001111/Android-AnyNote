package com.skyanchor.anynote.ui.reminder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.core.WEEKDAY_CN
import com.skyanchor.anynote.data.entity.CompletionMode
import com.skyanchor.anynote.data.entity.ReminderRule
import com.skyanchor.anynote.reminder.IntervalUnit
import com.skyanchor.anynote.reminder.RecurrenceEngine
import com.skyanchor.anynote.reminder.RecurrenceType
import com.skyanchor.anynote.ui.components.AppIcons
import com.skyanchor.anynote.ui.components.DateTimeField
import com.skyanchor.anynote.ui.components.DateTimePickerDialog
import com.skyanchor.anynote.ui.components.FieldLabel
import com.skyanchor.anynote.ui.components.FilterChip
import com.skyanchor.anynote.ui.components.InfoBanner
import com.skyanchor.anynote.ui.components.SegmentedTabs
import com.skyanchor.anynote.ui.components.SpacerHeight
import com.skyanchor.anynote.ui.components.noRippleClickable
import com.skyanchor.anynote.ui.theme.AppColors
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 新建一条规则时的默认值：从现在起 1 小时后、单次、跟随备忘录的完成语义。 */
fun newRule(noteId: String, zone: ZoneId = ZoneId.systemDefault()): ReminderRule {
    val now = System.currentTimeMillis()
    return ReminderRule(
        id = UUID.randomUUID().toString(),
        noteId = noteId,
        type = RecurrenceType.ONCE,
        startLocal = LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES).plusHours(1),
        timezone = zone.id,
        intervalValue = 1,
        intervalUnit = IntervalUnit.DAY,
        weekdays = setOf(1, 2, 3, 4, 5),
        dayOfMonth = null,
        monthOfYear = null,
        endDate = null,
        completionMode = CompletionMode.CONTINUE,
        autoRepeatEnabled = false,
        autoRepeatIntervalMinutes = 10,
        autoRepeatLimit = 3,
        isEnabled = true,
        createdAt = now,
        updatedAt = now,
    )
}

/**
 * 规则编辑表单。编辑页与详情页共用同一份实现：表单只负责产出新的 [rule]，
 * 落库与重排由调用方完成（基线 §23：规则变更必须走"取消 → 重新登记"）。
 */
@Composable
fun RuleForm(rule: ReminderRule, onChange: (ReminderRule) -> Unit) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        FieldLabel("重复方式")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(RecurrenceType.entries.toList()) { type ->
                FilterChip(type.label, rule.type == type) {
                    onChange(
                        rule.copy(
                            type = type,
                            dayOfMonth = if (type == RecurrenceType.MONTHLY || type == RecurrenceType.YEARLY) {
                                rule.dayOfMonth ?: rule.startLocal.dayOfMonth
                            } else {
                                null
                            },
                            monthOfYear = if (type == RecurrenceType.YEARLY) rule.monthOfYear ?: rule.startLocal.monthValue else null,
                            intervalUnit = if (type == RecurrenceType.INTERVAL) rule.intervalUnit else IntervalUnit.DAY,
                            intervalValue = if (type == RecurrenceType.INTERVAL) rule.intervalValue.coerceAtLeast(1) else 1,
                        )
                    )
                }
            }
        }

        SpacerHeight(14)
        FieldLabel(if (rule.type == RecurrenceType.ONCE) "提醒时间" else "首次提醒 / 时刻")
        DateTimeField(rule.startLocal) { showStartPicker = true }
        if (rule.type.needsWeekdayHint) {
            SpacerHeight(6)
            Text(
                rule.weekdayHint,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextTertiary,
            )
        }

        when (rule.type) {
            RecurrenceType.CUSTOM_WEEKDAYS -> {
                SpacerHeight(14)
                FieldLabel("选择星期")
                WeekdayPicker(rule.weekdays) { days -> onChange(rule.copy(weekdays = days)) }
            }

            RecurrenceType.MONTHLY -> {
                SpacerHeight(14)
                FieldLabel("每月几号（当月不存在时自动落到最后一天）")
                NumberPicker(1, 31, rule.dayOfMonth ?: rule.startLocal.dayOfMonth) { day ->
                    onChange(rule.copy(dayOfMonth = day))
                }
            }

            RecurrenceType.YEARLY -> {
                SpacerHeight(14)
                FieldLabel("月份")
                NumberPicker(1, 12, rule.monthOfYear ?: rule.startLocal.monthValue) { month ->
                    onChange(rule.copy(monthOfYear = month))
                }
                SpacerHeight(14)
                FieldLabel("日期")
                NumberPicker(1, 31, rule.dayOfMonth ?: rule.startLocal.dayOfMonth) { day ->
                    onChange(rule.copy(dayOfMonth = day))
                }
            }

            RecurrenceType.INTERVAL -> {
                SpacerHeight(14)
                FieldLabel("间隔")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepperField(rule.intervalValue.coerceIn(1, 999)) { value ->
                        onChange(rule.copy(intervalValue = value))
                    }
                    Spacer(Modifier.width(12.dp))
                    IntervalUnit.entries.forEach { unit ->
                        FilterChip("每 ${unit.label}", rule.intervalUnit == unit, Modifier.padding(end = 6.dp)) {
                            onChange(rule.copy(intervalUnit = unit))
                        }
                    }
                }
            }

            else -> Unit
        }

        SpacerHeight(14)
        SwitchRow(
            "设置结束日期",
            rule.endDate?.let { "到 $it 为止" } ?: "长期有效",
            rule.endDate != null,
        ) { checked ->
            onChange(rule.copy(endDate = if (checked) rule.startLocal.toLocalDate().plusMonths(1) else null))
        }
        if (rule.endDate != null) {
            SpacerHeight(8)
            EndDateField(rule.endDate) { showEndPicker = true }
        }

        SpacerHeight(14)
        FieldLabel("完成方式")
        SegmentedTabs(
            listOf(
                CompletionMode.CONTINUE to "本次完成",
                CompletionMode.END_SERIES to "结束系列",
            ),
            rule.completionMode,
        ) { mode -> onChange(rule.copy(completionMode = mode)) }
        SpacerHeight(6)
        Text(
            rule.completionMode.label,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextTertiary,
        )

        SpacerHeight(14)
        SwitchRow(
            "未处理时自动再提醒",
            "通知停留后按固定间隔再次提醒，仅影响本次事件",
            rule.autoRepeatEnabled,
        ) { checked -> onChange(rule.copy(autoRepeatEnabled = checked)) }
        if (rule.autoRepeatEnabled) {
            SpacerHeight(10)
            FieldLabel("再提醒间隔")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AUTO_REPEAT_MINUTES) { minutes ->
                    FilterChip("$minutes 分钟", rule.autoRepeatIntervalMinutes == minutes) {
                        onChange(rule.copy(autoRepeatIntervalMinutes = minutes))
                    }
                }
            }
            SpacerHeight(12)
            FieldLabel("最多重复")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AUTO_REPEAT_LIMITS) { limit ->
                    FilterChip(if (limit == 0) "不限" else "$limit 次", rule.autoRepeatLimit == limit) {
                        onChange(rule.copy(autoRepeatLimit = limit))
                    }
                }
            }
        }

        SpacerHeight(14)
        SwitchRow("启用此规则", RecurrenceEngine.describe(rule), rule.isEnabled) { checked ->
            onChange(rule.copy(isEnabled = checked))
        }

        SpacerHeight(14)
        InfoBanner(
            if (rule.type.recurring) {
                "重复提醒按设备当前时区的钟表时间触发，跨时区旅行后仍是当地时间。"
            } else {
                "单次提醒固定在你选择时所属的时区（${rule.timezone}），换时区后绝对时刻不变。"
            }
        )
    }

    if (showStartPicker) {
        DateTimePickerDialog(
            "选择提醒时间",
            rule.startLocal,
            onDismiss = { showStartPicker = false },
            onConfirm = { picked ->
                onChange(rule.copy(startLocal = picked, timezone = ZoneId.systemDefault().id))
                showStartPicker = false
            },
        )
    }
    if (showEndPicker && rule.endDate != null) {
        DateTimePickerDialog(
            "选择结束日期",
            LocalDateTime.of(rule.endDate, LocalTime.of(23, 59)),
            onDismiss = { showEndPicker = false },
            onConfirm = { picked ->
                onChange(rule.copy(endDate = picked.toLocalDate()))
                showEndPicker = false
            },
            showTime = false,
        )
    }
}

private val AUTO_REPEAT_MINUTES = listOf(5, 10, 15, 30, 60, 120)
private val AUTO_REPEAT_LIMITS = listOf(1, 2, 3, 5, 0)

private val RecurrenceType.needsWeekdayHint: Boolean
    get() = this == RecurrenceType.WEEKLY ||
        this == RecurrenceType.MONTHLY ||
        this == RecurrenceType.YEARLY ||
        (this == RecurrenceType.INTERVAL)

private val ReminderRule.weekdayHint: String
    get() = when {
        type == RecurrenceType.WEEKLY -> "每周按 ${WEEKDAY_CN[startLocal.dayOfWeek.value - 1]} 重复"
        type == RecurrenceType.MONTHLY -> "默认按每月 ${dayOfMonth ?: startLocal.dayOfMonth} 日重复"
        type == RecurrenceType.YEARLY -> "默认按每年 ${monthOfYear ?: startLocal.monthValue} 月 ${dayOfMonth ?: startLocal.dayOfMonth} 日重复"
        else -> "按所选日期与时刻起算"
    }

@Composable
private fun EndDateField(date: LocalDate, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.PrimarySoft)
            .noRippleClickable(onClick)
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
    }
}

@Composable
private fun WeekdayPicker(selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items((1..7).toList()) { iso ->
            FilterChip(WEEKDAY_CN[iso - 1], selected.contains(iso)) {
                onChange(if (selected.contains(iso)) selected - iso else selected + iso)
            }
        }
    }
}

@Composable
private fun NumberPicker(from: Int, to: Int, value: Int, onChange: (Int) -> Unit) {
    val options = (from..to).toList()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { option ->
            FilterChip(
                if (option == 31 && to == 31) "31（月末）" else "$option",
                option == value,
            ) { onChange(option) }
        }
    }
}

@Composable
private fun StepperField(value: Int, onChange: (Int) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .clip(shape)
            .border(1.dp, AppColors.GlassBorder, shape)
            .background(AppColors.Glass)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .noRippleClickable { onChange((value - 1).coerceIn(1, 999)) },
            contentAlignment = Alignment.Center,
        ) {
            Text("−", style = MaterialTheme.typography.titleMedium, color = AppColors.TextSecondary)
        }
        Text(
            "$value",
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.TextPrimary,
            modifier = Modifier.width(38.dp),
            textAlign = TextAlign.Center,
        )
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .noRippleClickable { onChange((value + 1).coerceIn(1, 999)) },
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium, color = AppColors.Primary)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextTertiary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = AppColors.Primary,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFE2E8F3),
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}
