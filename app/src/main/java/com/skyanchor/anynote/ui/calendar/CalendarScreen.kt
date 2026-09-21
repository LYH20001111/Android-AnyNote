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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.skyanchor.anynote.core.listTimeText
import com.skyanchor.anynote.data.entity.NoteCard
import com.skyanchor.anynote.data.entity.ReminderOccurrence
import com.skyanchor.anynote.ui.AppEnv
import com.skyanchor.anynote.ui.Route
import com.skyanchor.anynote.ui.Tab
import com.skyanchor.anynote.ui.components.AppIcons
import com.skyanchor.anynote.ui.components.BottomTabBar
import com.skyanchor.anynote.ui.components.EmptyState
import com.skyanchor.anynote.ui.components.FloatingActionButton
import com.skyanchor.anynote.ui.components.GlassCard
import com.skyanchor.anynote.ui.components.ScreenScaffold
import com.skyanchor.anynote.ui.components.TagPill
import com.skyanchor.anynote.ui.components.TextAction
import com.skyanchor.anynote.ui.components.noRippleClickable
import com.skyanchor.anynote.ui.loadAsync
import com.skyanchor.anynote.ui.theme.AppColors
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun CalendarScreen(env: AppEnv) {
    val repo = env.repository
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }

    val marks = loadAsync<List<Pair<Int, Int>>>(month, emptyList()) {
        repo.monthMarks(month).map { it.key to it.value }
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
            )
            Spacer(Modifier.height(10.dp))
            MonthGrid(
                month = month,
                selected = selected,
                marks = marks.value.toMap(),
                onSelect = { selected = it },
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "${selected.monthValue} 月 ${selected.dayOfMonth} 日 · ${dayCards.value.size} 条提醒",
                style = MaterialTheme.typography.titleSmall,
                color = AppColors.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            if (dayCards.value.isEmpty()) {
                EmptyState(AppIcons.Calendar, "这一天没有提醒", "换一天看看，或点右下角 + 新建")
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(dayCards.value, key = { it.second.id }) { (card, occurrence) ->
                        DayEntryRow(card, occurrence) { env.router.push(Route.Detail(card.note.id)) }
                    }
                }
            }
            Spacer(Modifier.height(84.dp))
        }
    }
}

@Composable
private fun MonthHeader(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Chevron(AppIcons.ChevronLeft, "上一月", onPrev)
        Text(
            "${month.year} 年 ${month.monthValue} 月",
            style = MaterialTheme.typography.titleLarge,
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
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
        Icon(icon, description, tint = AppColors.TextSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MonthGrid(month: YearMonth, selected: LocalDate, marks: Map<Int, Int>, onSelect: (LocalDate) -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        corner = 18,
        contentPadding = PaddingValues(10.dp),
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
        Spacer(Modifier.height(6.dp))

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
                        contentAlignment = Alignment.Center,
                    ) {
                        if (date != null) {
                            DayCell(date = date, selected = date == selected, count = marks[date.dayOfMonth] ?: 0, onClick = { onSelect(date) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, selected: Boolean, count: Int, onClick: () -> Unit) {
    val isToday = date == LocalDate.now()
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
                when {
                    selected -> AppColors.Primary
                    isToday -> AppColors.PrimarySoft
                    else -> Color.Transparent
                }
            )
            .then(
                if (isToday && !selected) Modifier.border(1.dp, AppColors.Primary, CircleShape) else Modifier
            )
            .noRippleClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${date.dayOfMonth}",
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    selected -> AppColors.OnPrimary
                    isToday -> AppColors.Primary
                    else -> AppColors.TextPrimary
                },
            )
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            count <= 0 -> Color.Transparent
                            selected -> AppColors.OnPrimary
                            else -> AppColors.Primary
                        }
                    ),
            )
        }
    }
}

@Composable
private fun DayEntryRow(card: NoteCard, occurrence: ReminderOccurrence, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), corner = 18, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.PrimarySoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Clock, null, tint = AppColors.Primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    card.note.title?.takeIf { it.isNotBlank() }
                        ?: card.note.body.lineSequence().firstOrNull()?.take(20)
                        ?: "备忘录",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                )
                Text(
                    listTimeText(occurrence.effectiveAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }
            card.folder?.let { TagPill(it.name) }
        }
    }
}
