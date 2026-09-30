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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.R
import com.skyanchor.anynote.core.dateTimeText
import com.skyanchor.anynote.core.listTimeText
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
import com.skyanchor.anynote.ui.settings.ConfirmDialog
import com.skyanchor.anynote.ui.settings.SnoozeDurationDialog
import com.skyanchor.anynote.ui.theme.AppColors
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NoteDetailScreen(env: AppEnv, noteId: String) {
    val repo = env.repository
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
        ScreenScaffold(title = stringResource(R.string.det_note), onBack = { env.router.pop() }) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.status_loading), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextTertiary)
            }
        }
        return
    }

    val note = card.note
    // 没有待办、也没有任何规则还有未来触发点：这条备忘录已经"耗尽"，
    // 完成按钮必须仍然可用（点击归档到历史），否则唯一出口只剩回收站。
    val nowInstant = Instant.now()
    val exhausted = pending.value.isEmpty() &&
        rules.value.none { it.isEnabled && RecurrenceEngine.nextOccurrence(it, nowInstant) != null }
    ScreenScaffold(
        title = card.folder?.name ?: stringResource(R.string.det_note),
        onBack = { env.router.pop() },
        actions = { TextAction(stringResource(R.string.action_edit)) { env.router.push(Route.Editor(note.id, note.folderId)) } },
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
                        note.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.det_no_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = AppColors.TextPrimary,
                    )
                    SpacerHeight(8)
                    Text(
                        note.body.ifBlank { stringResource(R.string.det_no_body) },
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.TextSecondary,
                    )
                    SpacerHeight(12)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        card.folder?.let { TagPill(it.name) }
                        Spacer(Modifier.width(6.dp))
                        TagPill(stringResource(R.string.det_priority_label, stringResource(note.priority.labelRes)), tint = AppColors.TextSecondary)
                        if (note.status == NoteStatus.COMPLETED) {
                            Spacer(Modifier.width(6.dp))
                            TagPill(stringResource(R.string.status_completed), tint = AppColors.Success)
                        }
                    }
                }
            }

            if (note.completionEnabled) {
                item(key = "actions") {
                    SectionSpacer(14)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton(
                            stringResource(R.string.det_complete),
                            modifier = Modifier.weight(1f),
                            icon = AppIcons.Check,
                            enabled = pending.value.isNotEmpty() || exhausted,
                        ) { runWork(work = { repo.complete(note.id) }, done = { env.state.invalidate() }) }
                        GlassButton(
                            stringResource(R.string.det_snooze_short),
                            modifier = Modifier.weight(1f),
                            icon = AppIcons.Clock,
                            enabled = pending.value.isNotEmpty(),
                        ) { snoozeTarget = true }
                        GlassButton(
                            stringResource(R.string.det_skip_short),
                            modifier = Modifier.weight(1f),
                            icon = AppIcons.SkipNext,
                            enabled = pending.value.isNotEmpty(),
                        ) { runWork(work = { repo.skipCurrent(note.id) }, done = { env.state.invalidate() }) }
                    }
                }
            }

            item(key = "occurrences") {
                SectionSpacer(16)
                FieldLabel(stringResource(R.string.det_pending_reminders, pending.value.size))
                GlassCard(Modifier.fillMaxWidth(), corner = 20) {
                    if (pending.value.isEmpty()) {
                        Text(
                            when {
                                exhausted -> stringResource(R.string.det_exhausted_hint)
                                rules.value.any { it.isEnabled } -> stringResource(R.string.det_round_done_hint)
                                else -> stringResource(R.string.det_no_pending_hint)
                            },
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
                FieldLabel(stringResource(R.string.det_reminder_rules, rules.value.size))
                GlassCard(Modifier.fillMaxWidth(), corner = 20) {
                    if (rules.value.isEmpty()) {
                        Text(
                            stringResource(R.string.det_no_rules_hint),
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
                        stringResource(R.string.det_add_reminder),
                        modifier = Modifier.fillMaxWidth(),
                        icon = AppIcons.Add,
                    ) { env.router.push(Route.RuleEditor(note.id, null)) }
                }
            }

            if (attachments.value.isNotEmpty()) {
                item(key = "attachments") {
                    SectionSpacer(16)
                    FieldLabel(stringResource(R.string.det_attachments_count, attachments.value.size))
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
                                    stringResource(attachment.type.labelRes),
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
                    KeyValueRow(stringResource(R.string.det_created_at), dateTimeText(context, note.createdAt))
                    SpacerHeight(8)
                    KeyValueRow(stringResource(R.string.det_updated_at), dateTimeText(context, note.updatedAt))
                    SpacerHeight(8)
                    KeyValueRow(stringResource(R.string.det_completion_button), if (note.completionEnabled) stringResource(R.string.det_enabled) else stringResource(R.string.det_off))
                    SpacerHeight(8)
                    KeyValueRow(stringResource(R.string.det_lock_screen_body), if (note.notificationPreviewEnabled) stringResource(R.string.det_shown) else stringResource(R.string.det_hidden))
                }
                SectionSpacer(14)
                if (!env.notifications.permissionGranted) {
                    InfoBanner(stringResource(R.string.det_notification_permission_hint))
                    SectionSpacer(10)
                }
                SectionSpacer(10)
                TrashCard(onClick = { confirmTrash = true })
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    if (snoozeTarget) {
        SnoozeDurationDialog(
            title = stringResource(R.string.det_snooze),
            text = stringResource(R.string.det_snooze_desc),
            selected = repo.settings.defaultSnoozeMinutes,
            onDismiss = { snoozeTarget = false },
            onPick = { minutes ->
                val target = pending.value.firstOrNull() ?: return@SnoozeDurationDialog
                runWork(
                    work = { repo.snoozeOccurrence(target.id, minutes) },
                    done = { env.state.invalidate() },
                )
            },
        )
    }

    if (confirmTrash) {
        ConfirmDialog(
            title = stringResource(R.string.det_trash_confirm_title),
            text = stringResource(R.string.det_trash_confirm_text),
            confirmLabel = stringResource(R.string.det_move_to_trash),
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
    val context = LocalContext.current
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
                        OccurrenceStatus.SCHEDULED -> stringResource(R.string.det_status_scheduled)
                        OccurrenceStatus.TRIGGERED -> stringResource(R.string.det_status_triggered)
                        OccurrenceStatus.SNOOZED -> stringResource(
                            R.string.det_status_snoozed,
                            listTimeText(context, occurrence.snoozedUntil ?: occurrence.scheduledAt),
                        )
                        else -> occurrence.status.storage
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary,
                )
            }
            TextAction(stringResource(R.string.det_complete), color = AppColors.Success) { onComplete() }
            TextAction(stringResource(R.string.det_skip_short), color = AppColors.Danger, modifier = Modifier.padding(start = 4.dp)) { onSkip() }
        }
    }
}

@Composable
private fun RuleRow(rule: ReminderRule, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
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
                stringResource(R.string.det_rule_summary, stringResource(rule.type.labelRes), RecurrenceEngine.describe(context, rule)),
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val next = RecurrenceEngine.nextOccurrence(rule, Instant.now())
            Text(
                when {
                    !rule.isEnabled -> stringResource(R.string.det_disabled)
                    next != null -> stringResource(R.string.det_next, dateTimeText(context, next.toEpochMilli()))
                    else -> stringResource(R.string.det_no_future_occurrence)
                },
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary,
            )
        }
        TextAction(stringResource(R.string.action_edit), onClick = onEdit)
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
                    stringResource(R.string.det_move_to_trash),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Danger,
                )
                Text(
                    stringResource(R.string.det_trash_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Danger.copy(alpha = 0.75f),
                )
            }
        }
    }
}
