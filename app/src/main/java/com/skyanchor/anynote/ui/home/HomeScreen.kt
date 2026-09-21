package com.skyanchor.anynote.ui.home

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.core.TimeGroup
import com.skyanchor.anynote.core.groupOf
import com.skyanchor.anynote.data.entity.Folder
import com.skyanchor.anynote.data.entity.NoteCard
import com.skyanchor.anynote.ui.AppEnv
import com.skyanchor.anynote.ui.Route
import com.skyanchor.anynote.ui.Tab
import com.skyanchor.anynote.ui.components.AppIcons
import com.skyanchor.anynote.ui.components.BottomTabBar
import com.skyanchor.anynote.ui.components.EmptyState
import com.skyanchor.anynote.ui.components.FilterChip
import com.skyanchor.anynote.ui.components.FloatingActionButton
import com.skyanchor.anynote.ui.components.GroupLabel
import com.skyanchor.anynote.ui.components.IconCircleButton
import com.skyanchor.anynote.ui.components.NoteRow
import com.skyanchor.anynote.ui.components.ScreenScaffold
import com.skyanchor.anynote.ui.components.SearchField
import com.skyanchor.anynote.ui.loadAsync
import com.skyanchor.anynote.ui.theme.AppColors
import com.skyanchor.anynote.ui.theme.CategoryPalette

@Composable
fun HomeScreen(env: AppEnv) {
    val repo = env.repository
    var query by remember { mutableStateOf("") }
    var folderId by remember { mutableStateOf<String?>(null) }
    val folders = loadAsync<List<Folder>>(
        env.state.refreshKey,
        emptyList(),
    ) { repo.folders.list() }
    val cards = loadAsync<List<NoteCard>>(
        Triple(env.state.refreshKey, query, folderId),
        emptyList(),
    ) { repo.homeCards(query, folderId) }

    ScreenScaffold(
        title = "随记",
        actions = {
            IconCircleButton(AppIcons.Calendar, "日历", size = 44, tint = AppColors.TextPrimary) {
                env.router.selectTab(Tab.Calendar)
            }
        },
        bottomBar = { BottomTabBar(Tab.Home) { env.router.selectTab(it) } },
        floatingActionButton = {
            FloatingActionButton { env.router.push(Route.Editor(null, folderId)) }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            SearchField(query, "搜索备忘录…", Modifier.fillMaxWidth()) { query = it }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val selected = folderId
                FilterChip("全部", selected == null) { folderId = null }
                Spacer(Modifier.width(8.dp))
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(folders.value, key = { it.id }) { folder ->
                        FilterChip(
                            folder.name,
                            selected == folder.id,
                            accent = CategoryPalette.accent(folder.colorKey),
                            container = CategoryPalette.container(folder.colorKey),
                        ) { folderId = if (selected == folder.id) null else folder.id }
                    }
                }
            }
            HomeList(cards.value, Modifier.weight(1f)) { env.router.push(Route.Detail(it.note.id)) }
        }
    }
}

@Composable
private fun HomeList(
    cards: List<NoteCard>,
    modifier: Modifier = Modifier,
    onClick: (NoteCard) -> Unit,
) {
    if (cards.isEmpty()) {
        EmptyState(AppIcons.Bell, "还没有提醒", "点击右下角 + 新建一条备忘录")
        return
    }
    val grouped = LinkedHashMap<TimeGroup, MutableList<NoteCard>>()
    cards.forEach { grouped.getOrPut(groupOf(it.nextOccurrence?.effectiveAt)) { mutableListOf() } += it }

    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 10.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        grouped.forEach { (group, groupItems) ->
            item(key = "header_${group.name}") {
                GroupLabel(group.label, groupItems.size, Modifier.padding(top = 8.dp))
            }
            items(groupItems, key = { "card_${it.note.id}" }) { card ->
                NoteRow(card = card, onClick = { onClick(card) })
            }
        }
    }
}
