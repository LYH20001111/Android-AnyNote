package com.skyanchor.anynote.ui.settings

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.core.dateTimeText
import com.skyanchor.anynote.data.entity.Folder
import com.skyanchor.anynote.data.entity.NoteCard
import com.skyanchor.anynote.ui.AppEnv
import com.skyanchor.anynote.ui.components.AppIcons
import com.skyanchor.anynote.ui.components.CategoryTile
import com.skyanchor.anynote.ui.components.EmptyState
import com.skyanchor.anynote.ui.components.FilterChip
import com.skyanchor.anynote.ui.components.GlassCard
import com.skyanchor.anynote.ui.components.InfoBanner
import com.skyanchor.anynote.ui.components.ScreenScaffold
import com.skyanchor.anynote.ui.components.TextAction
import com.skyanchor.anynote.ui.components.TonalButton
import com.skyanchor.anynote.ui.components.folderIcon
import com.skyanchor.anynote.ui.loadAsync
import com.skyanchor.anynote.ui.theme.AppColors
import com.skyanchor.anynote.ui.theme.CategoryPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface TrashDialog {
    data class Purge(val card: NoteCard) : TrashDialog
    data object Empty : TrashDialog
}

/** loadAsync 的 key 要把分类筛选打包成复合键，与历史页同一做法。 */
private data class TrashKey(val refresh: Int, val folderId: String?)

/**
 * 回收站（基线 §16）：删除只停止调度并移出当前列表，永久删除才连带规则、事件与附件一起清理。
 * 产品上不设自动过期，因此这里也没有"保留 N 天"的倒计时。
 */
@Composable
fun TrashScreen(env: AppEnv) {
    val repo = env.repository
    val scope = rememberCoroutineScope()
    var folderId by remember { mutableStateOf<String?>(null) }
    val folders = loadAsync<List<Folder>>(env.state.refreshKey, emptyList()) { repo.folders.list() }
    val cards = loadAsync<List<NoteCard>>(
        TrashKey(env.state.refreshKey, folderId),
        emptyList(),
    ) { repo.trashedCards(folderId) }
    var dialog by remember { mutableStateOf<TrashDialog?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun mutate(work: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { work() }
            busy = false
            env.state.invalidate()
        }
    }

    ScreenScaffold(
        title = "回收站",
        onBack = { env.router.pop() },
        actions = {
            if (cards.value.isNotEmpty()) {
                TextAction("清空", color = AppColors.Danger) { dialog = TrashDialog.Empty }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            InfoBanner("回收站中的备忘录不会自动清理，请自行确认后再永久删除。")
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "all") {
                    FilterChip("全部分类", folderId == null) { folderId = null }
                }
                items(folders.value, key = { it.id }) { folder ->
                    FilterChip(
                        folder.name,
                        folder.id == folderId,
                        accent = CategoryPalette.accent(folder.colorKey),
                        container = CategoryPalette.container(folder.colorKey),
                    ) { folderId = if (folderId == folder.id) null else folder.id }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (cards.value.isEmpty()) {
                EmptyState(
                    AppIcons.Archive,
                    if (folderId == null) "回收站是空的" else "该分类下没有备忘录",
                    "删除的备忘录会先留在这里。",
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(cards.value, key = { it.note.id }) { card ->
                        TrashRow(
                            card = card,
                            onPurge = { dialog = TrashDialog.Purge(card) },
                            onRestore = { mutate { repo.restoreFromTrash(card.note.id) } },
                        )
                    }
                }
            }
        }
    }

    when (val current = dialog) {
        is TrashDialog.Purge -> ConfirmDialog(
            title = "永久删除这条备忘录？",
            text = "它的提醒规则、历史记录与附件都会一起删除，且无法恢复。",
            confirmLabel = "永久删除",
            danger = true,
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                mutate { repo.purge(current.card.note.id) }
            },
        )

        TrashDialog.Empty -> ConfirmDialog(
            title = "清空回收站？",
            text = "${if (folderId == null) "" else "该分类下全部 "}${cards.value.size} 条备忘录及其规则、历史、附件都会被永久删除。",
            confirmLabel = "清空",
            danger = true,
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                mutate { repo.purgeAllTrash(folderId) }
            },
        )

        null -> Unit
    }
}

/** 回收站列表项：分类图标块与色板沿用首页的 CategoryTile / CategoryPalette。 */
@Composable
private fun TrashRow(
    card: NoteCard,
    onPurge: () -> Unit,
    onRestore: () -> Unit,
) {
    val folder = card.folder
    val colorKey = folder?.colorKey ?: folder?.iconKey
    GlassCard(
        Modifier.fillMaxWidth(),
        corner = 18,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = onPurge,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryTile(colorKey, folderIcon(folder?.iconKey), size = 40, corner = 13)
            Spacer(Modifier.width(12.dp))
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
                Spacer(Modifier.height(5.dp))
                Text(
                    "删除于 ${card.note.deletedAt?.let { dateTimeText(it) } ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary,
                )
            }
            Spacer(Modifier.width(10.dp))
            TonalButton("恢复", icon = AppIcons.RestoreLoop, onClick = onRestore)
        }
    }
}
