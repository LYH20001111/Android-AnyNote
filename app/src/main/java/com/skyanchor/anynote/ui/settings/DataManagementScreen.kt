package com.skyanchor.anynote.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.core.FileStore
import com.skyanchor.anynote.core.dateTimeText
import com.skyanchor.anynote.data.backup.BackupInfo
import com.skyanchor.anynote.data.backup.DataSummary
import com.skyanchor.anynote.data.backup.RestoreMode
import com.skyanchor.anynote.ui.AppEnv
import com.skyanchor.anynote.ui.components.AppIcons
import com.skyanchor.anynote.ui.components.GlassCard
import com.skyanchor.anynote.ui.components.Hairline
import com.skyanchor.anynote.ui.components.InfoBanner
import com.skyanchor.anynote.ui.components.ScreenScaffold
import com.skyanchor.anynote.ui.components.SectionHeader
import com.skyanchor.anynote.ui.components.SectionSpacer
import com.skyanchor.anynote.ui.components.SettingRow
import com.skyanchor.anynote.ui.loadAsync
import com.skyanchor.anynote.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据管理：本地备份（.zip）、本地恢复（覆盖 / 合并）、清空数据。
 * 备份与恢复都经 SAF 由用户挑选位置，不申请任何存储权限；
 * 备份包内含整库快照与全部附件文件，备忘录引用的图片/文件都能一起带走。
 */
@Composable
fun DataManagementScreen(env: AppEnv) {
    val scope = rememberCoroutineScope()
    val summary = loadAsync<DataSummary>(env.state.refreshKey) { env.dataBackup.summary() }
    var busy by remember { mutableStateOf<String?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var restoreInfo by remember { mutableStateOf<BackupInfo?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            busy = "正在打包备忘录、提醒规则与附件…"
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { env.dataBackup.backupTo(uri) } }
                busy = null
                result.onSuccess {
                    env.toast("备份完成：${it.entries} 个条目，共 ${FileStore.formatSize(it.bytes)}")
                }.onFailure {
                    env.toast("备份失败：${it.message ?: "未知错误"}")
                }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            busy = "正在读取备份清单…"
            scope.launch {
                val info = withContext(Dispatchers.IO) {
                    runCatching { env.dataBackup.readBackupInfo(uri) }.getOrNull()
                }
                busy = null
                if (info == null) {
                    env.toast("读取失败：请选择本应用导出的 .zip 备份文件")
                } else {
                    pendingRestoreUri = uri
                    restoreInfo = info
                }
            }
        }
    }

    fun runRestore(uri: Uri, mode: RestoreMode) {
        busy = if (mode == RestoreMode.REPLACE) "正在覆盖恢复…" else "正在合并恢复…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { env.dataBackup.restoreFrom(uri, mode) }
            }
            busy = null
            pendingRestoreUri = null
            restoreInfo = null
            result.onSuccess {
                env.state.invalidate()
                env.resyncAll()
                env.toast(
                    if (mode == RestoreMode.REPLACE) "已按备份覆盖恢复：备忘录 ${it.notes} 条、附件 ${it.attachments} 个"
                    else "合并恢复完成：新增备忘录 ${it.notes} 条、附件 ${it.attachments} 个"
                )
            }.onFailure {
                env.toast("恢复失败：${it.message ?: "未知错误"}，当前数据未被改动")
            }
        }
    }

    ScreenScaffold(
        title = "数据管理",
        onBack = { env.router.pop() },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            InfoBanner("备份会把备忘录、回收站、分类、提醒规则与全部附件（图片/文件）打包成一个 .zip 文件，保存到手机任意位置；恢复时可选覆盖或合并。")
            Spacer(Modifier.height(12.dp))

            val data = summary.value
            GlassCard(Modifier.fillMaxWidth(), corner = 20, contentPadding = PaddingValues(16.dp)) {
                Text(
                    "当前数据",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "备忘录 ${data?.notes ?: "—"} 条 · 回收站 ${data?.trashed ?: "—"} 条",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "提醒规则 ${data?.rules ?: "—"} 条 · 提醒事件 ${data?.occurrences ?: "—"} 条",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "附件 ${data?.attachments ?: "—"} 个 · 约 ${data?.attachmentBytes?.let { FileStore.formatSize(it) } ?: "—"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary,
                )
            }

            SectionSpacer(18)
            SectionHeader("备份与恢复")
            SectionSpacer(6)
            GlassCard(Modifier.fillMaxWidth(), corner = 20, contentPadding = PaddingValues(vertical = 4.dp)) {
                SettingRow(
                    AppIcons.Archive,
                    "本地备份",
                    subtitle = "打包全部数据为 .zip，可选择保存位置",
                    onClick = { if (busy == null) backupLauncher.launch(suggestedBackupName()) },
                )
                Hairline(Modifier.padding(horizontal = 16.dp))
                SettingRow(
                    AppIcons.Restore,
                    "本地恢复",
                    subtitle = "从 .zip 备份还原，可覆盖或合并（按 ID 去重）",
                    onClick = {
                        if (busy == null) restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                )
            }

            SectionSpacer(18)
            SectionHeader("危险操作")
            SectionSpacer(6)
            GlassCard(Modifier.fillMaxWidth(), corner = 20, contentPadding = PaddingValues(vertical = 4.dp)) {
                SettingRow(
                    AppIcons.Warning,
                    "清空数据",
                    subtitle = "删除全部备忘录、回收站、提醒与附件文件",
                    tint = AppColors.Danger,
                    onClick = { if (busy == null) confirmClear = true },
                )
            }

            if (busy != null) {
                SectionSpacer(18)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        " ${busy}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    val info = restoreInfo
    if (info != null) {
        ChoiceDialog(
            title = "选择恢复方式",
            text = "备份于 ${dateTimeText(info.createdAt)}（随记 v${info.appVersion}）：\n" +
                "备忘录 ${info.notes} 条 · 分类 ${info.folders} 个 · 规则 ${info.rules} 条 · " +
                "附件 ${info.attachments} 个（约 ${FileStore.formatSize(info.attachmentBytes)}）\n\n" +
                "覆盖恢复会先清空当前全部数据再按备份还原；合并恢复保留当前数据，" +
                "只补充备份中不存在（按记录 ID 判断）的备忘录、分类与附件。",
            options = listOf(
                RestoreMode.REPLACE to "覆盖恢复（先清空当前数据）",
                RestoreMode.MERGE to "合并恢复（跳过重复数据）",
            ),
            selected = RestoreMode.MERGE,
            onDismiss = { restoreInfo = null; pendingRestoreUri = null },
            onPick = { mode ->
                val uri = pendingRestoreUri
                restoreInfo = null
                if (uri != null) runRestore(uri, mode)
            },
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "清空全部数据？",
            text = "将永久删除全部 ${summary.value?.notes ?: 0} 条备忘录（含回收站 ${summary.value?.trashed ?: 0} 条）、" +
                "所有提醒规则与历史记录，并删除全部附件文件。分类与设置将保留。\n\n" +
                "此操作无法撤销，建议先执行一次本地备份。",
            confirmLabel = "清空",
            danger = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                busy = "正在清空数据…"
                scope.launch {
                    withContext(Dispatchers.IO) { env.dataBackup.clearAllData() }
                    busy = null
                    env.state.invalidate()
                    env.resyncAll()
                    env.toast("已清空全部提醒数据")
                }
            },
        )
    }
}

/** SAF 建议文件名：时间戳保证多次备份互不覆盖。 */
private fun suggestedBackupName(): String =
    "anynote-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
