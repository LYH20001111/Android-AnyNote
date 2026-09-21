package com.skyanchor.anynote.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.core.WEEKDAY_CN
import com.skyanchor.anynote.core.dayOfWeekCn
import com.skyanchor.anynote.core.timeOnlyText
import com.skyanchor.anynote.core.toLocal
import com.skyanchor.anynote.data.entity.NoteCard
import com.skyanchor.anynote.data.entity.OccurrenceStatus
import com.skyanchor.anynote.data.entity.ReminderOccurrence
import com.skyanchor.anynote.ui.AppEnv
import com.skyanchor.anynote.ui.Route
import com.skyanchor.anynote.ui.Tab
import com.skyanchor.anynote.ui.components.AppIcons
import com.skyanchor.anynote.ui.components.BottomTabBar
import com.skyanchor.anynote.ui.components.CategoryTile
import com.skyanchor.anynote.ui.components.EmptyState
import com.skyanchor.anynote.ui.components.FloatingActionButton
import com.skyanchor.anynote.ui.components.GlassCard
import com.skyanchor.anynote.ui.components.ScreenScaffold
import com.skyanchor.anynote.ui.components.TagPill
import com.skyanchor.anynote.ui.components.TextAction
import com.skyanchor.anynote.ui.components.folderIcon
import com.skyanchor.anynote.ui.components.noRippleClickable
import com.skyanchor.anynote.ui.loadAsync
import com.skyanchor.anynote.ui.theme.AppColors
import com.skyanchor.anynote.ui.theme.CategoryPalette
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** 日历格最多画几个分类点，超出的分类在当天的列表里看。 */
private const val MAX_DOTS = 3

@Composable
fun CalendarScreen(env: AppEnv) {
    val repo = env.repository
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var showJump by remember { mutableStateOf(false) }

    val marks = loadAsync<Map<Int, List<String>>>(month, emptyMap()) {
        repo.monthMarks(month)
    }
    val dayCards = loadAsync<List<Pair<NoteCard, ReminderOccurrence>>>(selected, emptyList()) {
        repo.dayEntries(selected)
    }

    ScreenScaffold(
        title = "日历",
        actions = {
            TextAction("今天") {
                month = YearMonth.now()
                selected = LocalDate.now()
            }
        },
        bottomBar = { BottomTabBar(Tab.Calendar) { env.router.selectTab(it) } },
        floatingActionButton = {
            FloatingActionButton { env.router.push(Route.Editor(null, null)) }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            MonthHeader(
                month = month,
                onPrev = { month = month.minusMonths(1) },
                onNext = { month = month.plusMonths(1) },
                onJump = { showJump = true },
            )
            Spacer(Modifier.height(10.dp))
            // 网格高度随月份周数变化（最多 6 周），整页可滚动避免下方内容被裁切。
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                item(key = "month_grid") {
                    MonthGrid(
                        month = month,
                        selected = selected,
                        marks = marks.value,
                        onSelect = { selected = it },
                    )
                }
                item(key = "day_header") {
                    Column(Modifier.padding(top = 16.dp)) {
                        DaySectionHeader(date = selected, count = dayCards.value.size)
                    }
                }
                if (dayCards.value.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            AppIcons.Calendar,
                            "这一天没有提醒",
                            "换一天看看，或点右下角 + 新建",
                            modifier = Modifier.padding(top = 9.dp),
                        )
                    }
                } else {
                    items(dayCards.value, key = { it.second.id }) { (card, occurrence) ->
                        Box(Modifier.padding(top = 11.dp)) {
                            DayEntryRow(card, occurrence) { env.router.push(Route.Detail(card.note.id)) }
                        }
                    }
                }
            }
        }
    }

    if (showJump) {
        JumpToDateDialog(
            initial = selected,
            onDismiss = { showJump = false },
            onConfirm = { date ->
                selected = date
                month = YearMonth.from(date)
                showJump = false
            },
        )
    }
}

/** 点标题栏的快速跳转：系统日历弹层，可切到年份列表，选定后直接定位到具体号数。 */
@Composable
private fun JumpToDateDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    // DatePicker 的 millis 按 UTC 存放，读写都以 UTC 换算，避免时区把日期挪一天。
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextAction("确定") {
                val millis = state.selectedDateMillis
                if (millis != null) onConfirm(LocalDate.ofEpochDay(millis / 86_400_000L)) else onDismiss()
            }
        },
        dismissButton = { TextAction("取消", color = AppColors.TextSecondary, onClick = onDismiss) },
    ) {
        DatePicker(
            state = state,
            title = {
                Text(
                    "跳转到指定日期",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 4.dp),
                )
            },
            showModeToggle = true,
        )
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJump: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Chevron(AppIcons.ChevronLeft, "上一月", onPrev)
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .noRippleClickable(onJump)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${month.year} 年 ${month.monthValue} 月",
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                AppIcons.ChevronDown,
                "快速跳转",
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
        Chevron(AppIcons.ChevronRight, "下一月", onNext)
    }
}

@Composable
private fun Chevron(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .noRippleClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = AppColors.TextSecondary, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    marks: Map<Int, List<String>>,
    onSelect: (LocalDate) -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        corner = 20,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            WEEKDAY_CN.forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        val first = month.atDay(1)
        val cells = ArrayList<LocalDate?>().apply {
            repeat(first.dayOfWeek.value - 1) { add(null) }
            repeat(month.lengthOfMonth()) { add(first.plusDays(it.toLong())) }
            while (size % 7 != 0) add(null)
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp),
                    ) {
                        if (date != null) {
                            DayCell(
                                date = date,
                                selected = date == selected,
                                colors = marks[date.dayOfMonth].orEmpty(),
                                onSelect = onSelect,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, selected: Boolean, colors: List<String>, onSelect: (LocalDate) -> Unit) {
    val isToday = date == LocalDate.now()
    Box(
        Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(
                when {
                    selected -> AppColors.Primary
                    isToday -> AppColors.PrimarySoft
                    else -> Color.Transparent
                }
            )
            .then(
                if (isToday && !selected) {
                    Modifier.border(1.4.dp, AppColors.Primary.copy(alpha = 0.55f), CircleShape)
                } else {
                    Modifier
                }
            )
            .noRippleClickable { onSelect(date) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${date.dayOfMonth}",
                style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                color = when {
                    selected -> AppColors.OnPrimary
                    isToday -> AppColors.Primary
                    else -> AppColors.TextPrimary
                },
            )
            Spacer(Modifier.height(3.dp))
            CategoryDots(colors, selected)
        }
    }
}

/** 一格下面的分类点：颜色取自首页同一套 CategoryPalette，选中格内整体转白。 */
@Composable
private fun CategoryDots(colors: List<String>, selected: Boolean) {
    Row(
        Modifier.height(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.take(MAX_DOTS).forEach { key ->
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(if (selected) AppColors.OnPrimary else CategoryPalette.accent(key)),
            )
        }
    }
}

@Composable
private fun DaySectionHeader(date: LocalDate, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            "${date.monthValue} 月 ${date.dayOfMonth} 日 · ${dayOfWeekCn(date.dayOfWeek)}",
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (count > 0) "$count 条提醒" else "暂无提醒",
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.TextTertiary,
        )
    }
}

@Composable
private fun DayEntryRow(card: NoteCard, occurrence: ReminderOccurrence, onClick: () -> Unit) {
    val folder = card.folder
    val colorKey = folder?.colorKey ?: folder?.iconKey
    val accent = CategoryPalette.accent(colorKey)
    val overdue = occurrence.status == OccurrenceStatus.SCHEDULED &&
        occurrence.effectiveAt < System.currentTimeMillis()
    val snoozed = occurrence.status == OccurrenceStatus.SNOOZED
    val hasTitle = card.note.title?.isNotBlank() == true
    val bodyLine = card.note.body.lineSequence().firstOrNull { it.isNotBlank() }
    val title = card.note.title?.takeIf { hasTitle } ?: bodyLine?.take(24) ?: "备忘录"

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        corner = 20,
        contentPadding = PaddingValues(13.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryTile(colorKey, folderIcon(folder?.iconKey), size = 44, corner = 14)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    folder?.let { TagPill(it.name, tint = accent) }
                    if (snoozed) TagPill("稍后提醒", tint = AppColors.Warning)
                    if (overdue) TagPill("已逾期", tint = AppColors.Danger)
                    if (card.attachmentCount > 0) {
                        TagPill("${card.attachmentCount} 附件", tint = AppColors.TextSecondary)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    timeOnlyText(toLocal(occurrence.effectiveAt).toLocalTime()),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (overdue) AppColors.Danger else AppColors.TextPrimary,
                )
                if (card.rule?.type?.recurring == true) {
                    Spacer(Modifier.height(5.dp))
                    Icon(AppIcons.Repeat, "重复提醒", tint = accent, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}
