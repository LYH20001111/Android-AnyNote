package com.skyanchor.anynote.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.data.db.DefaultFolders
import com.skyanchor.anynote.data.entity.Folder
import com.skyanchor.anynote.ui.AppEnv
import com.skyanchor.anynote.ui.components.AppIcons
import com.skyanchor.anynote.ui.components.EmptyState
import com.skyanchor.anynote.ui.components.FloatingActionButton
import com.skyanchor.anynote.ui.components.GlassCard
import com.skyanchor.anynote.ui.components.InfoBanner
import com.skyanchor.anynote.ui.components.ScreenScaffold
import com.skyanchor.anynote.ui.components.TagPill
import com.skyanchor.anynote.ui.components.folderIcon
import com.skyanchor.anynote.ui.components.noRippleClickable
import com.skyanchor.anynote.ui.loadAsync
import com.skyanchor.anynote.ui.theme.AppColors
import com.skyanchor.anynote.ui.theme.CategoryPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface FolderDialog {
    data object Create : FolderDialog
    data class Rename(val folder: Folder) : FolderDialog
    data class Delete(val folder: Folder, val count: Int) : FolderDialog
}

@Composable
fun FolderManageScreen(env: AppEnv) {
    val repo = env.repository
    val scope = rememberCoroutineScope()
    val folders = loadAsync<List<Folder>>(env.state.refreshKey, emptyList()) { repo.folders.list() }
    var dialog by remember { mutableStateOf<FolderDialog?>(null) }
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
        title = "分类管理",
        onBack = { env.router.pop() },
        floatingActionButton = { FloatingActionButton(icon = AppIcons.Add) { dialog = FolderDialog.Create } },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            InfoBanner("用上下箭头调整分类顺序；删除分类不会删除备忘录，它们会统一移到「其他」。")
            Spacer(Modifier.height(12.dp))
            if (folders.value.isEmpty()) {
                EmptyState(AppIcons.Folder, "还没有分类", "点右下角 + 新建一个分类")
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(folders.value, key = { _, it -> it.id }) { index, folder ->
                        val count = loadAsync<Int?>(Pair(folder.id, env.state.refreshKey)) {
                            repo.folders.noteCount(folder.id)
                        }
                        FolderRow(
                            folder = folder,
                            count = count.value,
                            canMoveUp = index > 0,
                            canMoveDown = index < folders.value.lastIndex &&
                                folders.value[index + 1].id != DefaultFolders.OTHER,
                            onMove = { up -> mutate { repo.folders.move(folder.id, up) } },
                            onRename = { dialog = FolderDialog.Rename(folder) },
                            onDelete = { dialog = FolderDialog.Delete(folder, count.value ?: 0) },
                        )
                    }
                }
            }
        }
    }

    when (val current = dialog) {
        is FolderDialog.Create -> TextInputDialog(
            title = "新建分类",
            label = "分类名称",
            initial = "",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                mutate { repo.folders.create(name) }
            },
        )

        is FolderDialog.Rename -> TextInputDialog(
            title = "重命名分类",
            label = "分类名称",
            initial = current.folder.name,
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                mutate { repo.folders.rename(current.folder.id, name) }
            },
        )

        is FolderDialog.Delete -> ConfirmDialog(
            title = "删除「${current.folder.name}」？",
            text = if (current.count > 0) {
                "该分类下的 ${current.count} 条备忘录会移到「其他」。"
            } else {
                "该分类下没有备忘录。"
            },
            confirmLabel = "删除",
            danger = true,
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                mutate { repo.folders.delete(current.folder.id) }
            },
        )

        null -> Unit
    }
}

@Composable
private fun FolderRow(
    folder: Folder,
    count: Int?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (up: Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val accent = CategoryPalette.accent(folder.colorKey)
    GlassCard(Modifier.fillMaxWidth(), corner = 18, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CategoryPalette.container(folder.colorKey)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(folderIcon(folder.iconKey), null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(folder.name, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
                Text(
                    if (folder.isBuiltIn) "内置分类 · ${count ?: 0} 条" else "${count ?: 0} 条备忘录",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary,
                )
            }
            if (folder.id == DefaultFolders.OTHER) {
                TagPill("兜底", tint = AppColors.TextTertiary)
            } else {
                MoveArrow(AppIcons.ArrowUp, "上移", enabled = canMoveUp, onClick = { onMove(true) })
                MoveArrow(AppIcons.ArrowDown, "下移", enabled = canMoveDown, onClick = { onMove(false) })
                Icon(
                    AppIcons.Edit,
                    "重命名",
                    tint = AppColors.TextTertiary,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(17.dp)
                        .noRippleClickable(onRename),
                )
                Icon(
                    AppIcons.Delete,
                    "删除分类",
                    tint = AppColors.TextTertiary,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(17.dp)
                        .noRippleClickable(onDelete),
                )
            }
        }
    }
}

@Composable
private fun MoveArrow(icon: ImageVector, contentDescription: String, enabled: Boolean, onClick: () -> Unit) {
    Icon(
        icon,
        contentDescription,
        tint = if (enabled) AppColors.TextSecondary else AppColors.TextTertiary.copy(alpha = 0.35f),
        modifier = Modifier
            .padding(start = 4.dp)
            .size(20.dp)
            .then(if (enabled) Modifier.noRippleClickable(onClick) else Modifier),
    )
}
