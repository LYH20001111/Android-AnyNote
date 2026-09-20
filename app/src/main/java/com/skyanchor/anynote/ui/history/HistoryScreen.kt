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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.skyanchor.anynote.ui.components.EmptyState
import com.skyanchor.anynote.ui.components.FilterChip
import com.skyanchor.anynote.ui.components.GlassCard
import com.skyanchor.anynote.ui.components.ScreenScaffold
import com.skyanchor.anynote.ui.components.SearchField
import com.skyanchor.anynote.ui.components.SegmentedTabs
import com.skyanchor.anynote.ui.components.SpacerHeight
import com.skyanchor.anynote.ui.components.TagPill
import com.skyanchor.anynote.ui.loadAsync
import com.skyanchor.anynote.ui.theme.AppColors
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
        title = "历史",
        bottomBar = { BottomTabBar(Tab.History) { env.router.selectTab(it) } },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            SearchField(query, "搜索历史提醒…", Modifier.fillMaxWidth()) { query = it }
            Spacer(Modifier.height(10.dp))
            SegmentedTabs(HistoryTab.entries.map { it to it.label }, tab) { tab = it }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RangeTab.entries.forEach { option ->
                    FilterChip(option.label, option == range, modifier = Modifier.padding(end = 8.dp)) { range = option }
                }
            }
            SpacerHeight(10)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "all") {
                    FilterChip("全部分类", folderId == null) { folderId = null }
                }
                items(folders.value, key = { it.id }) { folder ->
                    FilterChip(folder.name, folder.id == folderId, modifier = Modifier.padding(end = 0.dp)) {
                        folderId = if (folderId == folder.id) null else folder.id
                    }
                }
            }
            SpacerHeight(12)
            if (entries.value.isEmpty()) {
                EmptyState(AppIcons.History, "没有记录", "处理过的提醒会出现在这里。")
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
                            Text(
                                "${dateText(day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())} · ${bucket.size}",
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
    val (tint, label) = when (occurrence.status) {
        OccurrenceStatus.COMPLETED -> AppColors.Success to "已完成"
        OccurrenceStatus.SKIPPED -> AppColors.Warning to "已跳过"
        OccurrenceStatus.EXPIRED -> AppColors.Danger to "已逾期"
        OccurrenceStatus.CANCELLED -> AppColors.TextTertiary to "已取消"
        else -> AppColors.TextSecondary to occurrence.status.storage
    }
    GlassCard(Modifier.fillMaxWidth(), corner = 18, contentPadding = PaddingValues(12.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (occurrence.status == OccurrenceStatus.COMPLETED) AppIcons.DoneAll else AppIcons.History,
                null,
                tint = tint,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    card.note.title?.takeIf { it.isNotBlank() }
                        ?: card.note.body.lineSequence().firstOrNull()?.take(24)
                        ?: "备忘录",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    timeOnlyText(toLocal(occurrence.completedAt ?: occurrence.effectiveAt).toLocalTime()),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary,
                )
            }
            TagPill(label, tint = tint)
        }
    }
}
