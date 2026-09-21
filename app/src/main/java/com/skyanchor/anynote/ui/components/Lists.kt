package com.skyanchor.anynote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.core.listTimeText
import com.skyanchor.anynote.data.entity.NoteCard
import com.skyanchor.anynote.data.entity.OccurrenceStatus
import com.skyanchor.anynote.reminder.RecurrenceEngine
import com.skyanchor.anynote.ui.Tab
import com.skyanchor.anynote.ui.theme.AppColors
import com.skyanchor.anynote.ui.theme.CategoryPalette

private val TABS = listOf(
    Tab.Home to "首页",
    Tab.Calendar to "日历",
    Tab.History to "历史",
    Tab.Mine to "我的",
)

private fun tabIcon(tab: Tab): ImageVector = when (tab) {
    Tab.Home -> AppIcons.Home
    Tab.Calendar -> AppIcons.Calendar
    Tab.History -> AppIcons.History
    Tab.Mine -> AppIcons.Person
}

@Composable
fun BottomTabBar(current: Tab, onSelect: (Tab) -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .shadow(14.dp, shape, clip = false, ambientColor = AppColors.Shadow, spotColor = AppColors.Shadow)
            .clip(shape)
            .background(AppColors.GlassStrong)
            .border(1.dp, AppColors.GlassBorder, shape)
            .padding(vertical = 9.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TABS.forEach { (tab, label) ->
            val active = tab == current
            Column(
                Modifier
                    .weight(1f)
                    .noRippleClickable { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    tabIcon(tab),
                    null,
                    tint = if (active) AppColors.Primary else AppColors.TextTertiary,
                    modifier = Modifier.size(21.dp),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) AppColors.Primary else AppColors.TextTertiary,
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .width(16.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (active) AppColors.Primary else Color.Transparent),
                )
            }
        }
    }
}

/** 首页/日历/回收站共用的备忘录卡片（对齐参考图的卡片结构）。 */
@Composable
fun NoteRow(
    card: NoteCard,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val folder = card.folder
    val colorKey = folder?.colorKey ?: folder?.iconKey
    val accent = CategoryPalette.accent(colorKey)
    val occurrence = card.nextOccurrence
    val overdue = occurrence != null &&
        occurrence.status == OccurrenceStatus.SCHEDULED &&
        occurrence.effectiveAt < System.currentTimeMillis()
    val snoozed = occurrence?.status == OccurrenceStatus.SNOOZED
    val bodyLine = card.note.body.lineSequence().firstOrNull { it.isNotBlank() }
    val hasTitle = card.note.title?.isNotBlank() == true
    val title = card.note.title?.takeIf { hasTitle } ?: bodyLine?.take(24) ?: "备忘录"
    val summary = if (hasTitle) bodyLine?.take(28) else null

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        corner = 22,
        contentPadding = PaddingValues(14.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            CategoryTile(colorKey, folderIcon(folder?.iconKey), size = 46, corner = 15)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (trailing != null) {
                        trailing()
                    } else {
                        PriorityDot(card.note.priority)
                    }
                }
                if (summary != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(9.dp))
                val timeLabel = (occurrence?.let { listTimeText(it.effectiveAt) } ?: "未设置提醒") +
                    (card.rule?.let { if (it.type.recurring) " · ${RecurrenceEngine.describe(it)}" else "" } ?: "")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TagPill(
                        timeLabel,
                        modifier = Modifier.weight(1f, fill = false),
                        tint = when {
                            overdue -> AppColors.Danger
                            snoozed -> AppColors.Warning
                            else -> AppColors.TextSecondary
                        },
                        icon = AppIcons.Clock,
                    )
                    folder?.let { TagPill(it.name, tint = accent) }
                    if (snoozed) TagPill("稍后提醒", tint = AppColors.Warning)
                    if (overdue) TagPill("已逾期", tint = AppColors.Danger)
                    if (card.attachmentCount > 0) {
                        TagPill("${card.attachmentCount} 附件", tint = AppColors.TextSecondary)
                    }
                }
            }
        }
    }
}

/** 分类图标块：首页与日历共用，同一分类同一套 container→accent 渐变。 */
@Composable
fun CategoryTile(
    colorKey: String?,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Int = 46,
    corner: Int = 15,
) {
    val accent = CategoryPalette.accent(colorKey)
    Box(
        modifier
            .size(size.dp)
            .clip(RoundedCornerShape(corner.dp))
            .background(Brush.linearGradient(listOf(CategoryPalette.container(colorKey), accent.copy(alpha = 0.30f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size((size * 0.5f).dp))
    }
}

@Composable
fun StatusCheck(completed: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (completed) AppColors.Success else Color.Transparent)
            .then(
                if (completed) Modifier else Modifier.border(1.6.dp, AppColors.TextTertiary, CircleShape)
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (completed) Icon(AppIcons.Check, "已完成", tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

@Composable
fun GroupLabel(text: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
        Spacer(Modifier.width(6.dp))
        Text("$count", style = MaterialTheme.typography.labelSmall, color = AppColors.TextTertiary)
    }
}

@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, tint: Color = AppColors.Primary) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Info, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint, modifier = Modifier.weight(1f))
    }
}

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = AppColors.TextSecondary,
    )
}

@Composable
fun KeyValueRow(key: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextTertiary)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SectionSpacer(height: Int = 12) = Spacer(Modifier.height(height.dp))
