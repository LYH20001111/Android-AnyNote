package com.skyanchor.anynote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.core.compactDateTimeText
import com.skyanchor.anynote.ui.theme.AppColors
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val WHEEL_ITEM_HEIGHT = 40.dp
private const val WHEEL_VISIBLE = 5

/**
 * 单列滚轮选择器：手指拖动 + 惯性滚动，停止后自动对齐到中心；点击任意一项可直接滚过去。
 *
 * 滚轮与外部 [value] 是双向的，但必须避免"滚动 → 改状态 → 又回滚滚轮"的自激：
 * [lastEmitted] 记录最近一次由滚轮自己发出的值，只有外部改动与它不一致时才反向滚动。
 */
@Composable
private fun WheelPicker(
    label: String,
    value: Int,
    maxValue: Int,
    modifier: Modifier = Modifier,
    onValueChange: (Int) -> Unit,
) {
    val initialIndex = value.coerceIn(0, maxValue)
    val listState = remember { LazyListState(initialIndex) }
    val lastEmitted = remember { mutableIntStateOf(initialIndex) }
    val scope = rememberCoroutineScope()
    val half = WHEEL_VISIBLE / 2

    val nearestIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo
                .minByOrNull { abs(it.offset + it.size / 2 - center) }
                ?.index
                ?: initialIndex
        }
    }

    // 惯性滚动结束后，把离中心最近的项精确对齐到中心
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) return@collect
                val info = listState.layoutInfo
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                val item = info.visibleItemsInfo
                    .minByOrNull { abs(it.offset + it.size / 2 - center) }
                    ?: return@collect
                val delta = item.offset + item.size / 2 - center
                if (abs(delta) > 1) listState.animateScrollToItem(item.index, delta)
            }
    }

    LaunchedEffect(nearestIndex) {
        val settled = nearestIndex.coerceIn(0, maxValue)
        if (settled != lastEmitted.intValue) {
            lastEmitted.intValue = settled
            onValueChange(settled)
        }
    }

    LaunchedEffect(value) {
        if (value != lastEmitted.intValue) {
            lastEmitted.intValue = value.coerceIn(0, maxValue)
            // 用独立 scope 滚动：animateScrollToItem 期间 value 会跟随滚动逐帧变化，
            // 若挂在 LaunchedEffect(value) 上会被自己取消，滚不到目标。
            scope.launch { listState.animateScrollToItem(lastEmitted.intValue) }
        }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(64.dp)
                .height(WHEEL_ITEM_HEIGHT * WHEEL_VISIBLE)
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.PrimarySoft.copy(alpha = 0.32f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WHEEL_ITEM_HEIGHT)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.PrimarySoft)
                    .border(1.dp, AppColors.Primary.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = WHEEL_ITEM_HEIGHT * half),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(maxValue + 1) { index ->
                    val selected = index == nearestIndex
                    val distance = abs(index - nearestIndex)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(WHEEL_ITEM_HEIGHT)
                            .clickable { scope.launch { listState.animateScrollToItem(index) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            index.toString().padStart(2, '0'),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                selected -> AppColors.Primary
                                distance == 1 -> AppColors.TextSecondary
                                else -> AppColors.TextTertiary
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppColors.TextTertiary)
    }
}

@Composable
private fun WheelSeparator() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(16.dp)
                .height(WHEEL_ITEM_HEIGHT * WHEEL_VISIBLE),
            contentAlignment = Alignment.Center,
        ) {
            Text(":", style = MaterialTheme.typography.titleMedium, color = AppColors.TextTertiary)
        }
        Spacer(Modifier.height(6.dp))
        Text(" ", style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * 日期 + 时间选择对话框。时间精度到秒（基线 §5.2）：滚轮负责时/分，秒连同日期一起在
 * 顶部输入框里手输，避免为一列几乎用不上的秒把滚轮挤窄。
 */
@Composable
fun DateTimePickerDialog(
    title: String,
    initial: LocalDateTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalDateTime) -> Unit,
    showTime: Boolean = true,
) {
    var date by remember { mutableStateOf(initial.toLocalDate()) }
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    var second by remember { mutableIntStateOf(initial.second) }
    var showCalendar by remember { mutableStateOf(false) }
    var rawInput by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }

    fun picked(): LocalDateTime = LocalDateTime.of(date, LocalTime.of(hour, minute, second))

    /** 能解析就立刻写回状态（滚轮与日历随之滚动），解析不了返回 false 且保持原值。 */
    fun tryApply(text: String): Boolean {
        if (text.isBlank()) return true
        val parsed = parseFlexible(text, picked(), showTime) ?: return false
        date = parsed.toLocalDate()
        hour = parsed.hour
        minute = parsed.minute
        second = parsed.second
        return true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextAction("确定") {
                if (focused && !tryApply(rawInput)) invalid = true else onConfirm(picked())
            }
        },
        dismissButton = { TextAction("取消", color = AppColors.TextSecondary, onClick = onDismiss) },
        title = { AppDialogTitle(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(if (invalid) AppColors.DangerSoft else AppColors.PrimarySoft.copy(alpha = 0.32f))
                        .border(1.dp, if (invalid) AppColors.Danger else AppColors.Primary.copy(alpha = 0.28f), shape)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        AppIcons.Edit,
                        null,
                        tint = if (invalid) AppColors.Danger else AppColors.Primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = if (focused) rawInput else formatPicked(picked(), showTime),
                        onValueChange = { rawInput = it; if (tryApply(it)) invalid = false },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { state ->
                                if (state.isFocused && !focused) rawInput = formatPicked(picked(), showTime)
                                focused = state.isFocused
                                if (!state.isFocused) invalid = false
                            },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppColors.TextPrimary),
                        cursorBrush = SolidColor(AppColors.Primary),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (tryApply(rawInput)) onConfirm(picked()) else invalid = true
                            },
                        ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (invalid) "无法识别的格式" else inputHint(showTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (invalid) AppColors.Danger else AppColors.TextTertiary,
                )
                Spacer(Modifier.height(14.dp))
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
                if (showTime) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.Top,
                    ) {
                        WheelPicker("时", hour, 23) { hour = it }
                        WheelSeparator()
                        WheelPicker("分", minute, 59) { minute = it }
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

private val FORMATTER_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val FORMATTER_TIME = DateTimeFormatter.ofPattern("HH:mm")
private val FORMATTER_TIME_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss")

internal fun formatPicked(value: LocalDateTime, showTime: Boolean): String = when {
    !showTime -> value.format(FORMATTER_DATE)
    value.second != 0 -> value.format(FORMATTER_DATE) + " " + value.format(FORMATTER_TIME_SECONDS)
    else -> value.format(FORMATTER_DATE) + " " + value.format(FORMATTER_TIME)
}

private fun inputHint(showTime: Boolean): String =
    if (showTime) "可直接输入，如 2026-09-21 09:30、9-21 9:30:15，或只写时间 09:30"
    else "可直接输入日期，如 2026-09-21 或 9-21"

private val DATE_TOKEN = Regex("""^(\d{1,4})[-/.](\d{1,2})(?:[-/.](\d{1,2}))?${'$'}""")
private val TIME_TOKEN = Regex("""^(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?${'$'}""")

/**
 * 宽松解析手输内容：缺日期段沿用当前日期，缺时间段沿用当前时刻，所以"只改时间"
 * 和"只改日期"都能一次写完。任何一段越界（如 2 月 30 日）返回 null 交给界面报错。
 */
internal fun parseFlexible(raw: String, fallback: LocalDateTime, showTime: Boolean): LocalDateTime? {
    val normalized = raw.trim()
        .replace('：', ':')
        .replace('－', '-')
        .replace('．', '.')
        .replace('年', '-')
        .replace('月', '-')
        .replace('日', ' ')
        .replace('点', ':')
        .replace('时', ':')
        .replace('分', ' ')
        .replace('T', ' ')
        .replace('t', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
    if (normalized.isEmpty()) return null
    if (!showTime && normalized.contains(':')) return null

    var date: LocalDate? = null
    var time: LocalTime? = null
    for (token in normalized.split(' ')) {
        if (token.isEmpty()) continue
        val parsedDate = parseDateToken(token, fallback.toLocalDate())
        val parsedTime = if (parsedDate == null) parseTimeToken(token) else null
        when {
            parsedDate != null -> {
                if (date != null) return null
                date = parsedDate
            }
            parsedTime != null -> {
                if (time != null) return null
                time = parsedTime
            }
            else -> return null
        }
    }
    if (date == null && time == null) return null
    return LocalDateTime.of(date ?: fallback.toLocalDate(), time ?: fallback.toLocalTime())
}

private fun parseDateToken(token: String, fallback: LocalDate): LocalDate? {
    if (token.length == 8 && token.all(Char::isDigit)) {
        return runCatching {
            LocalDate.of(
                token.substring(0, 4).toInt(),
                token.substring(4, 6).toInt(),
                token.substring(6, 8).toInt(),
            )
        }.getOrNull()
    }
    val matched = DATE_TOKEN.matchEntire(token) ?: return null
    val first = matched.groupValues[1]
    val g1 = first.toIntOrNull() ?: return null
    val g2 = matched.groupValues[2].toIntOrNull() ?: return null
    val g3 = matched.groupValues[3].toIntOrNull()
    return if (g3 == null) {
        runCatching {
            if (first.length == 4) LocalDate.of(g1, g2, fallback.dayOfMonth)
            else LocalDate.of(fallback.year, g1, g2)
        }.getOrNull()
    } else {
        val year = if (first.length <= 2) 2000 + g1 else g1
        runCatching { LocalDate.of(year, g2, g3) }.getOrNull()
    }
}

private fun parseTimeToken(token: String): LocalTime? {
    val matched = TIME_TOKEN.matchEntire(token) ?: return null
    val hour = matched.groupValues[1].toIntOrNull() ?: return null
    val minute = matched.groupValues[2].toIntOrNull() ?: return null
    val second = matched.groupValues[3].toIntOrNull() ?: 0
    return if (hour > 23 || minute > 59 || second > 59) null else LocalTime.of(hour, minute, second)
}

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
