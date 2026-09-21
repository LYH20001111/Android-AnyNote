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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.core.compactDateTimeText
import com.skyanchor.anynote.ui.theme.AppColors
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged

private val WHEEL_ITEM_HEIGHT = 40.dp
private const val WHEEL_VISIBLE = 5

/**
 * 单列滚轮选择器：手指拖动 + 惯性滚动，停止后自动对齐到中心；点击任意一项可直接滚过去。
 *
 * 滚轮与外部 [value] 是双向的：用户滚动结束后才回传落点；外部改动则动画滚过去。
 * 程序化滚动（[programmaticScroll]）期间不回传途经的中间位置——否则中间值会反过来
 * 改写外部状态、打断本次动画，导致快捷按钮（如"明早9点"）要连点多次才能到位。
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
    var programmaticScroll by remember { mutableStateOf(false) }
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

    LaunchedEffect(nearestIndex, programmaticScroll) {
        if (programmaticScroll) return@LaunchedEffect
        val settled = nearestIndex.coerceIn(0, maxValue)
        if (settled != lastEmitted.intValue) {
            lastEmitted.intValue = settled
            onValueChange(settled)
        }
    }

    LaunchedEffect(value) {
        val target = value.coerceIn(0, maxValue)
        if (target == lastEmitted.intValue) return@LaunchedEffect
        lastEmitted.intValue = target
        // 动画期间不回传中间位置；若被用户手势打断，落点由上面的效果在
        // programmaticScroll 翻回 false 后同步给外部。
        programmaticScroll = true
        try {
            listState.animateScrollToItem(target)
        } finally {
            programmaticScroll = false
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
                            .clickable { onValueChange(index) },
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
 * 日期 + 时间选择对话框。时间精度到秒（基线 §5.2）：时/分/秒各一列滚轮，
 * 日期走日历弹层，不提供手输——滚轮选定即可，避免手输格式校验的负担。
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

    fun picked(): LocalDateTime = LocalDateTime.of(date, LocalTime.of(hour, minute, second))

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextAction("确定") { onConfirm(picked()) } },
        dismissButton = { TextAction("取消", color = AppColors.TextSecondary, onClick = onDismiss) },
        title = { AppDialogTitle(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
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
                        WheelSeparator()
                        WheelPicker("秒", second, 59) { second = it }
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
