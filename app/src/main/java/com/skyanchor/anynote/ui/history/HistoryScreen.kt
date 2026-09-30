package com.skyanchor.anynote.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.R
import com.skyanchor.anynote.core.dateText
import com.skyanchor.anynote.core.timeOnlyText
import com.skyanchor.anynote.core.toLocal
import com.skyanchor.anynote.data.entity.Folder
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
import com.skyanchor.anynote.ui.components.FilterChip
import com.skyanchor.anynote.ui.components.GlassCard
import com.skyanchor.anynote.ui.components.ScreenScaffold
import com.skyanchor.anynote.ui.components.SearchField
import com.skyanchor.anynote.ui.components.SegmentedTabs
import com.skyanchor.anynote.ui.components.SpacerHeight
import com.skyanchor.anynote.ui.components.TagPill
import com.skyanchor.anynote.ui.components.folderIcon
import com.skyanchor.anynote.ui.loadAsync
import com.skyanchor.anynote.ui.theme.AppColors
import com.skyanchor.anynote.ui.theme.CategoryPalette
import java.time.LocalDate

private enum class HistoryTab(val label: String, val statuses: List<OccurrenceStatus>) {
    COMPLETED("已完成", listOf(OccurrenceStatus.COMPLETED)),
    SKIPPED("已跳过", listOf(OccurrenceStatus.SKIPPED)),
    EXPIRED("已逾期", listOf(OccurrenceStatus.EXPIRED)),
    ALL("全部", OccurrenceStatus.entries.filter { it != OccurrenceStatus.SCHEDULED }),
}

private enum class RangeTab(val label: String, val days: Int?) {
    WEEK("近 7 天", 7),
    MONTH("近 30 天", 30),
    ALL("全部", null),
}

private fun HistoryTab.labelRes(): Int = when (this) {
    HistoryTab.COMPLETED -> R.string.status_completed
    HistoryTab.SKIPPED -> R.string.hist_skipped
    HistoryTab.EXPIRED -> R.string.status_overdue
    HistoryTab.ALL -> R.string.action_all
}

private fun RangeTab.labelRes(): Int = when (this) {
    RangeTab.WEEK -> R.string.hist_range_week
    RangeTab.MONTH -> R.string.hist_range_month
    RangeTab.ALL -> R.string.action_all
}

@Composable
fun HistoryScreen(env: AppEnv) {
    val repo = env.repository
    var tab by remember { mutableStateOf(HistoryTab.COMPLETED) }
    var range by remember { mutableStateOf(RangeTab.MONTH) }
    var query by remember { mutableStateOf("") }
    var folderId by remember { mutableStateOf<String?>(null) }

    val folders = loadAsync<List<Folder>>(env.state.refreshKey, emptyList()) { repo.folders.list() }
    val entries = loadAsync<List<Pair<NoteCard, ReminderOccurrence>>>(
        HistoryKey(env.state.refreshKey, tab, range, query, folderId),
        emptyList(),
    ) {
        val to = if (range.days == null) null else LocalDate.now().plusDays(1)
        val from = range.days?.let { LocalDate.now().minusDays((it - 1).toLong()) }
        repo.history(tab.statuses, query, folderId, from, to)
    }

    ScreenScaffold(
        hideTitleBar = true,
        bottomBar = { BottomTabBar(Tab.History) { env.router.selectTab(it) } },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            SearchField(query, stringResource(R.string.hist_search_hint), Modifier.fillMaxWidth()) { query = it }
            Spacer(Modifier.height(10.dp))
            SegmentedTabs(HistoryTab.entries.map { it to stringResource(it.labelRes()) }, tab) { tab = it }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RangeTab.entries.forEach { option ->
                    FilterChip(stringResource(option.labelRes()), option == range, modifier = Modifier.padding(end = 8.dp)) { range = option }
                }
            }
            SpacerHeight(10)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "all") {
                    FilterChip(stringResource(R.string.hist_all_categories), folderId == null) { folderId = null }
                }
                items(folders.value, key = { it.id }) { folder ->
                    FilterChip(
                        folder.name,
                        folder.id == folderId,
                        modifier = Modifier.padding(end = 0.dp),
                        accent = CategoryPalette.accent(folder.colorKey),
                        container = CategoryPalette.container(folder.colorKey),
                    ) {
                        folderId = if (folderId == folder.id) null else folder.id
                    }
                }
            }
            SpacerHeight(12)
            if (entries.value.isEmpty()) {
                EmptyState(AppIcons.History, stringResource(R.string.hist_empty_title), stringResource(R.string.hist_empty_message))
            } else {
                val grouped = LinkedHashMap<LocalDate, MutableList<Pair<NoteCard, ReminderOccurrence>>>()
                entries.value.forEach { (card, occurrence) ->
                    val day = toLocal(occurrence.completedAt ?: occurrence.effectiveAt).toLocalDate()
                    grouped.getOrPut(day) { mutableListOf() } += card to occurrence
                }
                LazyColumn(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    grouped.forEach { (day, bucket) ->
                        item(key = "header_${day}_$tab") {
                            val ctx = LocalContext.current
                            Text(
                                stringResource(
                                    R.string.hist_day_count,
                                    dateText(ctx, day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()),
                                    bucket.size,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = AppColors.TextPrimary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(bucket, key = { (_, occurrence) -> occurrence.id }) { (card, occurrence) ->
                            HistoryRow(card, occurrence) { env.router.push(Route.Detail(card.note.id)) }
                        }
                    }
                }
            }
        }
    }
}

/** loadAsync 的 key 需要把多个筛选条件打包成一个稳定的复合键。 */
private data class HistoryKey(
    val refresh: Int,
    val tab: HistoryTab,
    val range: RangeTab,
    val query: String,
    val folderId: String?,
)

@Composable
private fun HistoryRow(card: NoteCard, occurrence: ReminderOccurrence, onClick: () -> Unit) {
    val folder = card.folder
    val colorKey = folder?.colorKey ?: folder?.iconKey
    val accent = CategoryPalette.accent(colorKey)
    val (tint, label, statusIcon) = when (occurrence.status) {
        OccurrenceStatus.COMPLETED -> Triple(AppColors.Success, stringResource(R.string.status_completed), AppIcons.Check)
        OccurrenceStatus.SKIPPED -> Triple(AppColors.Warning, stringResource(R.string.hist_skipped), AppIcons.SkipNext)
        OccurrenceStatus.EXPIRED -> Triple(AppColors.Danger, stringResource(R.string.status_overdue), AppIcons.Warning)
        OccurrenceStatus.CANCELLED -> Triple(AppColors.TextTertiary, stringResource(R.string.hist_cancelled), AppIcons.Close)
        else -> Triple(AppColors.TextSecondary, occurrence.status.storage, AppIcons.History)
    }
    GlassCard(Modifier.fillMaxWidth(), corner = 20, contentPadding = PaddingValues(12.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryTile(colorKey, folderIcon(folder?.iconKey), size = 46, corner = 15)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    card.note.title?.takeIf { it.isNotBlank() }
                        ?: card.note.body.lineSequence().firstOrNull()?.take(24)
                        ?: stringResource(R.string.list_fallback_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                folder?.let {
                    Spacer(Modifier.height(6.dp))
                    TagPill(it.name, tint = accent)
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                timeOnlyText(toLocal(occurrence.completedAt ?: occurrence.effectiveAt).toLocalTime()),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextTertiary,
            )
            Spacer(Modifier.width(8.dp))
            TagPill(label, tint = tint, icon = statusIcon)
        }
    }
}
