package com.skyanchor.anynote.ui.mine

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
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.data.SettingsStore
import com.skyanchor.anynote.data.entity.CompletionMode
import com.skyanchor.anynote.ui.AppEnv
import com.skyanchor.anynote.ui.Route
import com.skyanchor.anynote.ui.Tab
import com.skyanchor.anynote.ui.components.AppIcons
import com.skyanchor.anynote.ui.components.BottomTabBar
import com.skyanchor.anynote.ui.components.GlassCard
import com.skyanchor.anynote.ui.components.Hairline
import com.skyanchor.anynote.ui.components.ScreenScaffold
import com.skyanchor.anynote.ui.components.SectionHeader
import com.skyanchor.anynote.ui.components.SectionSpacer
import com.skyanchor.anynote.ui.components.SettingRow
import com.skyanchor.anynote.ui.components.SpacerHeight
import com.skyanchor.anynote.ui.components.TagPill
import com.skyanchor.anynote.ui.loadAsync
import com.skyanchor.anynote.ui.settings.ChoiceDialog
import com.skyanchor.anynote.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Stats(val pending: Int, val active: Int, val completed: Int, val trashed: Int)

@Composable
fun MineScreen(env: AppEnv) {
    val repo = env.repository
    val scope = rememberCoroutineScope()
    val settings = repo.settings

    val stats = loadAsync<Stats?>(env.state.refreshKey) {
        Stats(
            pending = repo.reminders.pendingActive().size,
            active = repo.notes.countByStatus(com.skyanchor.anynote.data.entity.NoteStatus.ACTIVE),
            completed = repo.notes.countByStatus(com.skyanchor.anynote.data.entity.NoteStatus.COMPLETED),
            trashed = repo.notes.countByStatus(com.skyanchor.anynote.data.entity.NoteStatus.TRASHED),
        )
    }

    var snoozePicker by remember { mutableStateOf(false) }
    var modePicker by remember { mutableStateOf(false) }
    var defaultSnooze by remember { mutableStateOf(settings.defaultSnoozeMinutes) }
    var defaultMode by remember { mutableStateOf(settings.defaultCompletionMode) }
    var notificationsOn by remember { mutableStateOf(settings.notificationsEnabled) }

    ScreenScaffold(
        title = "我的",
        bottomBar = { BottomTabBar(Tab.Mine) { env.router.selectTab(it) } },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            GlassCard(Modifier.fillMaxWidth().padding(top = 6.dp), corner = 24) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(AppColors.PrimarySoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AppIcons.Person, null, tint = AppColors.Primary, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "随记用户",
                                style = MaterialTheme.typography.titleLarge,
                                color = AppColors.TextPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            TagPill("本地", tint = AppColors.Warning, icon = AppIcons.Lock)
                        }
                        SpacerHeight(3)
                        Text(
                            "本机 ID: ${settings.localInstanceId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextTertiary,
                        )
                    }
                }
                SpacerHeight(14)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(AppColors.Hairline)
                        .padding(vertical = 12.dp),
                ) {
                    StatCell("待处理", stats.value?.pending, AppColors.Primary, Modifier.weight(1f))
                    StatCell("进行中", stats.value?.active, AppColors.TextPrimary, Modifier.weight(1f))
                    StatCell("已完成", stats.value?.completed, AppColors.Success, Modifier.weight(1f))
                    StatCell("回收站", stats.value?.trashed, AppColors.TextTertiary, Modifier.weight(1f))
                }
            }

            SectionSpacer(18)
            SectionHeader("提醒")
            SectionSpacer(6)
            GlassCard(Modifier.fillMaxWidth(), corner = 20, contentPadding = PaddingValues(vertical = 4.dp)) {
                SettingRow(
                    AppIcons.Bell,
                    "系统通知",
                    subtitle = if (notificationsOn) "到点发送提醒通知" else "已暂停全部通知，闹钟同步退出",
                    value = if (notificationsOn) "开" else "关",
                    checked = notificationsOn,
                    onCheckedChange = { enabled ->
                        notificationsOn = enabled
                        settings.notificationsEnabled = enabled
                        env.resyncAll()
                    },
                    showChevron = false,
                )
                Hairline(Modifier.padding(horizontal = 16.dp))
                SettingRow(
                    AppIcons.Tune,
                    "通知与精确闹钟",
                    subtitle = "权限、声音、角标与锁屏预览",
                    onClick = { env.router.push(Route.NotificationSettings) },
                )
                Hairline(Modifier.padding(horizontal = 16.dp))
                SettingRow(
                    AppIcons.Clock,
                    "默认稍后提醒",
                    value = "$defaultSnooze 分钟",
                    onClick = { snoozePicker = true },
                )
                Hairline(Modifier.padding(horizontal = 16.dp))
                SettingRow(
                    AppIcons.DoneAll,
                    "默认完成方式",
                    subtitle = "新建规则时的默认勾选",
                    value = if (defaultMode == CompletionMode.CONTINUE) "完成本次" else "结束系列",
                    onClick = { modePicker = true },
                )
            }

            SectionSpacer(18)
            SectionHeader("数据")
            SectionSpacer(6)
            GlassCard(Modifier.fillMaxWidth(), corner = 20, contentPadding = PaddingValues(vertical = 4.dp)) {
                SettingRow(
                    AppIcons.Folder,
                    "分类管理",
                    subtitle = "新建、重命名与删除分类",
                    onClick = { env.router.push(Route.Folders) },
                )
                Hairline(Modifier.padding(horizontal = 16.dp))
                SettingRow(
                    AppIcons.DeleteOutline,
                    "回收站（${stats.value?.trashed ?: 0}）",
                    subtitle = "删除的备忘录会保留在这里，不会自动清理",
                    onClick = { env.router.push(Route.Trash) },
                )
            }

            SectionSpacer(18)
            SectionHeader("关于")
            SectionSpacer(6)
            GlassCard(Modifier.fillMaxWidth(), corner = 20) {
                Text("版本 1.0 · 本地优先", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary)
                SpacerHeight(6)
                Text(
                    "所有备忘录、规则与提醒记录都保存在本机 SQLite；未联网、未上传。" +
                        "提醒时间精确到秒，实际送达由系统通知与省电策略决定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (snoozePicker) {
        ChoiceDialog(
            title = "默认稍后提醒",
            options = SettingsStore.SNOOZE_PRESETS.map { it to "$it 分钟后" },
            selected = defaultSnooze,
            onDismiss = { snoozePicker = false },
            onPick = { minutes ->
                defaultSnooze = minutes
                settings.defaultSnoozeMinutes = minutes
            },
        )
    }
    if (modePicker) {
        ChoiceDialog(
            title = "默认完成方式",
            text = "只影响新建规则，已有规则保持各自设置。",
            options = CompletionMode.entries.map { it to it.label },
            selected = defaultMode,
            onDismiss = { modePicker = false },
            onPick = { mode ->
                defaultMode = mode
                settings.defaultCompletionMode = mode
            },
        )
    }
}

@Composable
private fun StatCell(label: String, value: Int?, tint: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value?.toString() ?: "–",
            style = MaterialTheme.typography.headlineSmall,
            color = tint,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppColors.TextTertiary)
    }
}
