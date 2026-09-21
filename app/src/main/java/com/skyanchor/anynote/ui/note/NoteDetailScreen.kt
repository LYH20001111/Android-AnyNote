package com.skyanchor.anynote.ui.note

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.core.dateTimeText
import com.skyanchor.anynote.core.listTimeText
import com.skyanchor.anynote.data.SettingsStore
import com.skyanchor.anynote.data.entity.Attachment
import com.skyanchor.anynote.data.entity.AttachmentType
import com.skyanchor.anynote.data.entity.NoteCard
import com.skyanchor.anynote.data.entity.NoteStatus
import com.skyanchor.anynote.data.entity.OccurrenceStatus
import com.skyanchor.anynote.data.entity.ReminderOccurrence
import com.skyanchor.anynote.data.entity.ReminderRule
import com.skyanchor.anynote.reminder.RecurrenceEngine
import com.skyanchor.anynote.ui.AppEnv
import com.skyanchor.anynote.ui.Route
import com.skyanchor.anynote.ui.components.AppIcons
import com.skyanchor.anynote.ui.components.AppSwitch
import com.skyanchor.anynote.ui.components.FieldLabel
import com.skyanchor.anynote.ui.components.GlassButton
import com.skyanchor.anynote.ui.components.GlassCard
import com.skyanchor.anynote.ui.components.InfoBanner
import com.skyanchor.anynote.ui.components.KeyValueRow
import com.skyanchor.anynote.ui.components.ScreenScaffold
import com.skyanchor.anynote.ui.components.SectionSpacer
import com.skyanchor.anynote.ui.components.SpacerHeight
import com.skyanchor.anynote.ui.components.TagPill
import com.skyanchor.anynote.ui.components.TextAction
import com.skyanchor.anynote.ui.components.TonalButton
import com.skyanchor.anynote.ui.loadAsync
import com.skyanchor.anynote.ui.settings.ChoiceDialog
import com.skyanchor.anynote.ui.settings.ConfirmDialog
import com.skyanchor.anynote.ui.theme.AppColors
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NoteDetailScreen(env: AppEnv, noteId: String) {
    val repo = env.repository
    val scope = rememberCoroutineScope()

    val cards = loadAsync<List<NoteCard>>(env.state.refreshKey, emptyList()) {
        listOfNotNull(repo.noteCard(noteId))
    }
    val pending = loadAsync<List<ReminderOccurrence>>(env.state.refreshKey, emptyList()) {
        repo.detailOccurrences(noteId)
    }
    val rules = loadAsync<List<ReminderRule>>(env.state.refreshKey, emptyList()) {
        repo.reminders.rulesOfNote(noteId)
    }
    val attachments = loadAsync<List<Attachment>>(env.state.refreshKey, emptyList()) {
        repo.attachments.listOfNote(noteId)
    }

    var snoozeTarget by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }

    fun runWork(work: () -> Unit, done: () -> Unit = {}) {
        scope.launch {
            withContext(Dispatchers.IO) { work() }
            done()
        }
    }

    val card = cards.value.firstOrNull()
    if (card == null) {
        ScreenScaffold(title = "备忘录", onBack = { env.router.pop() }) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("加载中…", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextTertiary)
            }
        }
        return
    }

    val note = card.note
    ScreenScaffold(
        title = card.folder?.name ?: "备忘录",
        onBack = { env.router.pop() },
        actions = { TextAction("编辑") { env.router.push(Route.Editor(note.id, note.folderId)) } },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 36.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            item(key = "body") {
                GlassCard(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    corner = 22,
                    brush = Brush.linearGradient(listOf(Color(0xFFF1F6FF), Color(0xFFDCEBFF))),
                ) {
                    Text(
                        note.title?.takeIf { it.isNotBlank() } ?: "无标题",
                        style = MaterialTheme.typography.headlineSmall,
                        color = AppColors.TextPrimary,
                    )
                    SpacerHeight(8)
                    Text(
                        note.body.ifBlank { "（无正文）" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.TextSecondary,
                    )
                    SpacerHeight(12)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        card.folder?.let { TagPill(it.name) }
                        Spacer(Modifier.width(6.dp))
                        TagPill("${note.priority.label}优先级", tint = AppColors.TextSecondary)
                        if (note.status == NoteStatus.COMPLETED) {
                            Spacer(Modifier.width(6.dp))
                            TagPill("已完成", tint = AppColors.Success)
                        }
                    }
                }
            }

            if (note.completionEnabled) {
                item(key = "actions") {
                    SectionSpacer(14)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton(
                            "完成",
                            modifier = Modifier.weight(1f),
                            icon = AppIcons.Check,
                            enabled = pending.value.isNotEmpty(),
                        ) { runWork(work = { repo.complete(note.id) }, done = { env.state.invalidate() }) }
                        GlassButton(
                            "稍后",
                            modifier = Modifier.weight(1f),
                            icon = AppIcons.Clock,
                        ) { snoozeTarget = true }
                        GlassButton(
                            "跳过",
                            modifier = Modifier.weight(1f),
                            icon = AppIcons.SkipNext,
                        ) { runWork(work = { repo.skipCurrent(note.id) }, done = { env.state.invalidate() }) }
                    }
                }
            }

            item(key = "occurrences") {
                SectionSpacer(16)
                FieldLabel("待处理提醒 ${pending.value.size}")
                GlassCard(Modifier.fillMaxWidth(), corner = 20) {
                    if (pending.value.isEmpty()) {
                        Text(
                            if (rules.value.any { it.isEnabled }) "这一轮已经处理完，等下一次触发。" else "没有待处理的事件。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextTertiary,
                        )
                    } else {
                        pending.value.forEach { occurrence ->
                            OccurrenceRow(
                                occurrence = occurrence,
                                onComplete = {
                                    runWork(
                                        work = { repo.completeOccurrence(occurrence.id) },
                                        done = { env.state.invalidate() },
                                    )
                                },
                                onSkip = {
                                    runWork(
                                        work = { repo.skipOccurrence(occurrence.id) },
                                        done = { env.state.invalidate() },
                                    )
                                },
                            )
                        }
                    }
                }
            }

            item(key = "rules") {
                SectionSpacer(16)
                FieldLabel("提醒规则 ${rules.value.size}")
                GlassCard(Modifier.fillMaxWidth(), corner = 20) {
                    if (rules.value.isEmpty()) {
                        Text(
                            "还没有提醒，先添加一条。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextTertiary,
                        )
                    } else {
                        rules.value.forEachIndexed { index, rule ->
                            if (index > 0) SpacerHeight(10)
                            RuleRow(
                                rule = rule,
                                onToggle = { enabled ->
                                    runWork(
                                        work = { repo.setRuleEnabled(rule.id, enabled) },
                                        done = { env.state.invalidate() },
                                    )
                                },
                                onEdit = { env.router.push(Route.RuleEditor(note.id, rule.id)) },
                            )
                        }
                    }
                    SpacerHeight(12)
                    TonalButton(
                        "添加提醒",
                        modifier = Modifier.fillMaxWidth(),
                        icon = AppIcons.Add,
                    ) { env.router.push(Route.RuleEditor(note.id, null)) }
                }
            }

            if (attachments.value.isNotEmpty()) {
                item(key = "attachments") {
                    SectionSpacer(16)
                    FieldLabel("附件 ${attachments.value.size}")
                    GlassCard(Modifier.fillMaxWidth(), corner = 20) {
                        attachments.value.forEach { attachment ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (attachment.type == AttachmentType.IMAGE) AppIcons.Image else AppIcons.File,
                                    null,
                                    tint = AppColors.Primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    attachment.fileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.TextPrimary,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    attachment.type.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.TextTertiary,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "meta") {
                SectionSpacer(16)
                GlassCard(Modifier.fillMaxWidth(), corner = 20) {
                    KeyValueRow("创建于", dateTimeText(note.createdAt))
                    SpacerHeight(8)
                    KeyValueRow("最近更新", dateTimeText(note.updatedAt))
                    SpacerHeight(8)
                    KeyValueRow("完成按钮", if (note.completionEnabled) "已启用" else "已关闭")
                    SpacerHeight(8)
                    KeyValueRow("锁屏正文", if (note.notificationPreviewEnabled) "展示" else "隐藏")
                }
                SectionSpacer(14)
                if (!env.notifications.permissionGranted) {
                    InfoBanner("系统通知权限未开启，提醒到点也不会送达。")
                    SectionSpacer(10)
                }
                SectionSpacer(10)
                TrashCard(onClick = { confirmTrash = true })
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    if (snoozeTarget) {
        ChoiceDialog(
            title = "稍后提醒",
            text = "只推迟当前这一次，不改变长期重复规则。",
            options = SettingsStore.SNOOZE_PRESETS.map { it to "$it 分钟后" },
            selected = repo.settings.defaultSnoozeMinutes,
            onDismiss = { snoozeTarget = false },
            onPick = { minutes ->
                val target = pending.value.firstOrNull() ?: return@ChoiceDialog
                runWork(
                    work = { repo.snoozeOccurrence(target.id, minutes) },
                    done = { env.state.invalidate() },
                )
            },
        )
    }

    if (confirmTrash) {
        ConfirmDialog(
            title = "移入回收站？",
            text = "备忘录与它的提醒会停止调度，可在回收站恢复。",
            confirmLabel = "移入回收站",
            danger = true,
            onDismiss = { confirmTrash = false },
            onConfirm = {
                confirmTrash = false
                runWork(
                    work = { repo.moveToTrash(note.id) },
                    done = {
                        env.state.invalidate()
                        env.router.pop()
                    },
                )
            },
        )
    }
}

@Composable
private fun OccurrenceRow(occurrence: ReminderOccurrence, onComplete: () -> Unit, onSkip: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (occurrence.status == OccurrenceStatus.SNOOZED) AppIcons.Clock else AppIcons.Bell,
                null,
                tint = if (occurrence.status == OccurrenceStatus.SNOOZED) AppColors.Warning else AppColors.Primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    listTimeText(occurrence.effectiveAt),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.TextPrimary,
                )
                Text(
                    when (occurrence.status) {
                        OccurrenceStatus.SCHEDULED -> "计划中"
                        OccurrenceStatus.TRIGGERED -> "已触达，等待处理"
                        OccurrenceStatus.SNOOZED -> "已推迟到 " +
                            listTimeText(occurrence.snoozedUntil ?: occurrence.scheduledAt)
                        else -> occurrence.status.storage
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary,
                )
            }
            TextAction("完成", color = AppColors.Success) { onComplete() }
            TextAction("跳过", color = AppColors.Danger, modifier = Modifier.padding(start = 4.dp)) { onSkip() }
        }
    }
}

@Composable
private fun RuleRow(rule: ReminderRule, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (rule.type.recurring) AppIcons.Repeat else AppIcons.Bell,
            null,
            tint = if (rule.isEnabled) AppColors.Primary else AppColors.TextTertiary,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${rule.type.label} · ${RecurrenceEngine.describe(rule)}",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val next = RecurrenceEngine.nextOccurrence(rule, Instant.now())
            Text(
                when {
                    !rule.isEnabled -> "已停用"
                    next != null -> "下次 " + dateTimeText(next.toEpochMilli())
                    else -> "当前没有未来触发点"
                },
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary,
            )
        }
        TextAction("编辑", onClick = onEdit)
        Spacer(Modifier.width(6.dp))
        AppSwitch(rule.isEnabled, onToggle)
    }
}

@Composable
private fun TrashCard(onClick: () -> Unit) {
    GlassCard(
        Modifier.fillMaxWidth(),
        corner = 20,
        color = AppColors.DangerSoft,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Delete, null, tint = AppColors.Danger, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "移入回收站",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Danger,
                )
                Text(
                    "回收站中的备忘录不再触发提醒",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Danger.copy(alpha = 0.75f),
                )
            }
        }
    }
}
