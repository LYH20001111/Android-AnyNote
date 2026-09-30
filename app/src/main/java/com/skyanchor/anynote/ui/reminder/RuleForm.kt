package com.skyanchor.anynote.ui.reminder

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.R
import com.skyanchor.anynote.core.weekdayRes
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
    val context = LocalContext.current

    Column(Modifier.fillMaxWidth()) {
        FieldLabel(stringResource(R.string.rf_repeat_type))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(RecurrenceType.entries.toList()) { type ->
                FilterChip(stringResource(type.labelRes), rule.type == type) {
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
        FieldLabel(if (rule.type == RecurrenceType.ONCE) stringResource(R.string.rf_reminder_time) else stringResource(R.string.rf_first_reminder_time))
        DateTimeField(rule.startLocal) { showStartPicker = true }
        if (rule.type.needsWeekdayHint) {
            SpacerHeight(6)
            Text(
                rule.weekdayHint(context),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextTertiary,
            )
        }

        when (rule.type) {
            RecurrenceType.CUSTOM_WEEKDAYS -> {
                SpacerHeight(14)
                FieldLabel(stringResource(R.string.rf_select_weekdays))
                WeekdayPicker(rule.weekdays) { days -> onChange(rule.copy(weekdays = days)) }
            }

            RecurrenceType.MONTHLY -> {
                SpacerHeight(14)
                FieldLabel(stringResource(R.string.rf_monthly_day_label))
                NumberPicker(1, 31, rule.dayOfMonth ?: rule.startLocal.dayOfMonth) { day ->
                    onChange(rule.copy(dayOfMonth = day))
                }
            }

            RecurrenceType.YEARLY -> {
                SpacerHeight(14)
                FieldLabel(stringResource(R.string.rf_month))
                NumberPicker(1, 12, rule.monthOfYear ?: rule.startLocal.monthValue) { month ->
                    onChange(rule.copy(monthOfYear = month))
                }
                SpacerHeight(14)
                FieldLabel(stringResource(R.string.rf_day))
                NumberPicker(1, 31, rule.dayOfMonth ?: rule.startLocal.dayOfMonth) { day ->
                    onChange(rule.copy(dayOfMonth = day))
                }
            }

            RecurrenceType.INTERVAL -> {
                SpacerHeight(14)
                FieldLabel(stringResource(R.string.rf_interval))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepperField(rule.intervalValue.coerceIn(1, 999)) { value ->
                        onChange(rule.copy(intervalValue = value))
                    }
                    Spacer(Modifier.width(12.dp))
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IntervalUnit.entries.forEach { unit ->
                            FilterChip(stringResource(R.string.rf_every_unit, stringResource(unit.labelRes)), rule.intervalUnit == unit, Modifier.padding(end = 6.dp)) {
                                onChange(rule.copy(intervalUnit = unit))
                            }
                        }
                    }
                }
            }

            else -> Unit
        }

        SpacerHeight(14)
        SwitchRow(
            stringResource(R.string.rf_set_end_date),
            rule.endDate?.let { stringResource(R.string.rf_end_until, it) } ?: stringResource(R.string.rf_no_end_date),
            rule.endDate != null,
        ) { checked ->
            onChange(rule.copy(endDate = if (checked) rule.startLocal.toLocalDate().plusMonths(1) else null))
        }
        if (rule.endDate != null) {
            SpacerHeight(8)
            EndDateField(rule.endDate) { showEndPicker = true }
        }

        SpacerHeight(14)
        FieldLabel(stringResource(R.string.rf_completion_mode))
        SegmentedTabs(
            listOf(
                CompletionMode.CONTINUE to stringResource(R.string.rf_mode_continue),
                CompletionMode.END_SERIES to stringResource(R.string.rf_mode_end_series),
            ),
            rule.completionMode,
        ) { mode -> onChange(rule.copy(completionMode = mode)) }
        SpacerHeight(6)
        Text(
            stringResource(rule.completionMode.labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextTertiary,
        )

        SpacerHeight(14)
        SwitchRow(
            stringResource(R.string.rf_auto_re_remind),
            stringResource(R.string.rf_auto_re_remind_desc),
            rule.autoRepeatEnabled,
        ) { checked -> onChange(rule.copy(autoRepeatEnabled = checked)) }
        if (rule.autoRepeatEnabled) {
            SpacerHeight(10)
            FieldLabel(stringResource(R.string.rf_re_remind_interval))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AUTO_REPEAT_MINUTES) { minutes ->
                    FilterChip(stringResource(R.string.rf_minutes, minutes), rule.autoRepeatIntervalMinutes == minutes) {
                        onChange(rule.copy(autoRepeatIntervalMinutes = minutes))
                    }
                }
            }
            SpacerHeight(12)
            FieldLabel(stringResource(R.string.rf_max_repeats))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AUTO_REPEAT_LIMITS) { limit ->
                    FilterChip(if (limit == 0) stringResource(R.string.rf_unlimited) else stringResource(R.string.rf_times, limit), rule.autoRepeatLimit == limit) {
                        onChange(rule.copy(autoRepeatLimit = limit))
                    }
                }
            }
        }

        SpacerHeight(14)
        SwitchRow(stringResource(R.string.rf_enable_rule), RecurrenceEngine.describe(context, rule), rule.isEnabled) { checked ->
            onChange(rule.copy(isEnabled = checked))
        }

        SpacerHeight(14)
        InfoBanner(
            if (rule.type.recurring) {
                stringResource(R.string.rf_recurring_zone_note)
            } else {
                stringResource(R.string.rf_once_zone_note, rule.timezone)
            }
        )
    }

    if (showStartPicker) {
        DateTimePickerDialog(
            stringResource(R.string.rf_pick_reminder_time),
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
            stringResource(R.string.rf_pick_end_date),
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

private fun ReminderRule.weekdayHint(context: Context): String = when {
    type == RecurrenceType.WEEKLY -> context.getString(R.string.rf_weekly_hint, context.getString(weekdayRes(startLocal.dayOfWeek.value)))
    type == RecurrenceType.MONTHLY -> context.getString(R.string.rf_monthly_hint, dayOfMonth ?: startLocal.dayOfMonth)
    type == RecurrenceType.YEARLY -> context.getString(R.string.rf_yearly_hint, monthOfYear ?: startLocal.monthValue, dayOfMonth ?: startLocal.dayOfMonth)
    else -> context.getString(R.string.rf_hint_from_start)
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
            stringResource(R.string.rf_date_ymd, date.year, date.monthValue, date.dayOfMonth),
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
            FilterChip(stringResource(weekdayRes(iso)), selected.contains(iso)) {
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
                if (option == 31 && to == 31) stringResource(R.string.rf_day_end_of_month) else "$option",
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
