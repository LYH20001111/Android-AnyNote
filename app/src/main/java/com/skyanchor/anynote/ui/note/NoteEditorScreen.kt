package com.skyanchor.anynote.ui.note

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.R
import com.skyanchor.anynote.core.FileStore
import com.skyanchor.anynote.data.NoteDraft
import com.skyanchor.anynote.data.entity.Attachment
import com.skyanchor.anynote.data.entity.AttachmentType
import com.skyanchor.anynote.data.entity.Folder
import com.skyanchor.anynote.data.entity.Note
import com.skyanchor.anynote.data.entity.Priority
import com.skyanchor.anynote.data.entity.ReminderRule
import com.skyanchor.anynote.reminder.RecurrenceEngine
import com.skyanchor.anynote.ui.AppEnv
import com.skyanchor.anynote.ui.Route
import com.skyanchor.anynote.ui.components.AppDialogTitle
import com.skyanchor.anynote.ui.components.AppIcons
import com.skyanchor.anynote.ui.components.AttachmentStrip
import com.skyanchor.anynote.ui.components.AttachmentUi
import com.skyanchor.anynote.ui.components.FieldLabel
import com.skyanchor.anynote.ui.components.FilterChip
import com.skyanchor.anynote.ui.components.GlassCard
import com.skyanchor.anynote.ui.components.PrimaryButton
import com.skyanchor.anynote.ui.components.ScreenScaffold
import com.skyanchor.anynote.ui.components.SegmentedTabs
import com.skyanchor.anynote.ui.components.SpacerHeight
import com.skyanchor.anynote.ui.components.TagPill
import com.skyanchor.anynote.ui.components.TextAction
import com.skyanchor.anynote.ui.components.noRippleClickable
import com.skyanchor.anynote.ui.components.rememberAttachmentPickers
import com.skyanchor.anynote.ui.components.toUi
import com.skyanchor.anynote.ui.loadAsync
import com.skyanchor.anynote.ui.reminder.RuleForm
import com.skyanchor.anynote.ui.reminder.newRule
import com.skyanchor.anynote.ui.settings.BackgroundRunDialog
import com.skyanchor.anynote.ui.theme.AppColors
import com.skyanchor.anynote.ui.theme.CategoryPalette
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class EditorSeed(
    val note: Note,
    val rules: List<ReminderRule>,
    val attachments: List<Attachment>,
)

@Composable
fun NoteEditorScreen(env: AppEnv, noteId: String?, presetFolderId: String?) {
    val repo = env.repository
    val seed = loadAsync(noteId) {
        noteId?.let { id ->
            repo.notes.get(id)?.let { note ->
                EditorSeed(note, repo.reminders.rulesOfNote(id), repo.attachments.listOfNote(id))
            }
        }
    }
    val folders = loadAsync<List<Folder>>(env.state.refreshKey, emptyList()) { repo.folders.list() }

    if (noteId != null && seed.value == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.status_loading), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextTertiary)
        }
        return
    }
    EditorForm(env, noteId, presetFolderId, seed.value, folders.value)
}

@Composable
private fun EditorForm(
    env: AppEnv,
    noteId: String?,
    presetFolderId: String?,
    seed: EditorSeed?,
    folders: List<Folder>,
) {
    val repo = env.repository
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = repo.settings
    val initialAttachments = remember(seed) {
        seed?.attachments?.map {
            AttachmentUi(it.id, it.type, it.localPath, it.fileName, it.size, it.mimeType, persisted = true)
        } ?: emptyList()
    }

    var title by remember(seed) { mutableStateOf(seed?.note?.title ?: "") }
    var body by remember(seed) { mutableStateOf(seed?.note?.body ?: "") }
    var folderId by remember(seed, folders) {
        mutableStateOf(
            seed?.note?.folderId ?: presetFolderId ?: settings.defaultFolderId ?: folders.firstOrNull()?.id ?: ""
        )
    }
    var priority by remember(seed) { mutableStateOf(seed?.note?.priority ?: settings.defaultPriority) }
    var completionEnabled by remember(seed) {
        mutableStateOf(seed?.note?.completionEnabled ?: settings.defaultCompletionEnabled)
    }
    var previewEnabled by remember(seed) {
        mutableStateOf(seed?.note?.notificationPreviewEnabled ?: settings.defaultPreviewEnabled)
    }
    var rules by remember(seed) { mutableStateOf(seed?.rules ?: emptyList()) }
    var attachments by remember(seed) { mutableStateOf(initialAttachments) }
    var editingRule by remember { mutableStateOf<ReminderRule?>(null) }
    var previewing by remember { mutableStateOf<AttachmentUi?>(null) }
    var saving by remember { mutableStateOf(false) }
    var backgroundHint by remember { mutableStateOf(false) }
    var pendingNav by remember { mutableStateOf<(() -> Unit)?>(null) }

    val pickAttachment = rememberAttachmentPickers(
        onPicked = { stored, type -> attachments = attachments + stored.toUi(type) },
        onFailed = { env.toast(context.getString(R.string.ed_attachment_import_failed)) },
    )

    val keepKeys = attachments.filter { it.persisted }.map { it.key }.toSet()
    val removed = initialAttachments.filter { it.persisted && it.key !in keepKeys }
    val added = attachments.filterNot { it.persisted }

    fun discardUnsavedFiles() = attachments.filterNot { it.persisted }.forEach { File(it.path).delete() }

    fun save() {
        if (body.isBlank() && title.isBlank()) {
            env.toast(context.getString(R.string.ed_empty_hint))
            return
        }
        if (folderId.isEmpty()) {
            env.toast(context.getString(R.string.ed_need_category))
            return
        }
        saving = true
        val draft = NoteDraft(
            id = noteId,
            title = title,
            body = body,
            folderId = folderId,
            priority = priority,
            completionEnabled = completionEnabled,
            notificationPreviewEnabled = previewEnabled,
        )
        val rulesSnapshot = rules
        val addedSnapshot = added
        val removedSnapshot = removed
        val isNew = noteId == null
        scope.launch {
            val savedId = withContext(Dispatchers.IO) {
                val note = repo.saveNote(draft, rulesSnapshot)
                removedSnapshot.forEach { repo.removeAttachment(it.key) }
                addedSnapshot.forEach {
                    repo.addAttachment(note.id, it.type, it.path, it.name, it.mime, it.size)
                }
                note.id
            }
            saving = false
            env.state.invalidate()
            val navigate: () -> Unit = if (isNew) {
                { env.router.replace(Route.Detail(savedId)) }
            } else {
                { env.router.pop() }
            }
            // 保存了启用提醒、但电池优化未豁免：先弹一次后台运行引导，再离开编辑页。
            val needHint = rulesSnapshot.any { it.isEnabled } && settings.notificationsEnabled &&
                !env.notifications.ignoresBatteryOptimizations && !settings.backgroundHintShown
            if (needHint) {
                settings.backgroundHintShown = true
                pendingNav = navigate
                backgroundHint = true
            } else {
                navigate()
            }
        }
    }

    ScreenScaffold(
        title = if (noteId == null) stringResource(R.string.ed_new_note) else stringResource(R.string.ed_edit_note),
        onBack = {
            discardUnsavedFiles()
            env.router.pop()
        },
        actions = {
            TextAction(if (saving) stringResource(R.string.ed_saving) else stringResource(R.string.action_save)) { if (!saving) save() }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            GlassCard(Modifier.fillMaxWidth(), corner = 20) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text(stringResource(R.string.ed_title_placeholder), color = AppColors.TextTertiary) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = AppColors.TextPrimary),
                    colors = outlinedField(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { if (it.length <= BODY_MAX_LENGTH) body = it },
                    placeholder = { Text(stringResource(R.string.ed_body_placeholder), color = AppColors.TextTertiary) },
                    minLines = 4,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppColors.TextPrimary),
                    colors = outlinedField(),
                    shape = RoundedCornerShape(14.dp),
                    supportingText = {
                        Text(
                            "${body.length}/$BODY_MAX_LENGTH",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (body.length >= BODY_MAX_LENGTH) AppColors.Danger else AppColors.TextTertiary,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SpacerHeight(14)
            FieldLabel(stringResource(R.string.ed_category))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(folders, key = { it.id }) { folder ->
                    FilterChip(
                        folder.name,
                        folder.id == folderId,
                        accent = CategoryPalette.accent(folder.colorKey),
                        container = CategoryPalette.container(folder.colorKey),
                    ) { folderId = folder.id }
                }
            }

            SpacerHeight(14)
            FieldLabel(stringResource(R.string.ed_priority))
            SegmentedTabs(Priority.entries.map { it to stringResource(it.labelRes) }, priority) { priority = it }

            SpacerHeight(14)
            FieldLabel(stringResource(R.string.ed_reminder_section))
            GlassCard(Modifier.fillMaxWidth(), corner = 18) {
                if (rules.isEmpty()) {
                    Text(
                        stringResource(R.string.ed_no_reminder_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextTertiary,
                    )
                } else {
                    rules.forEach { rule ->
                        RuleSummaryRow(
                            rule = rule,
                            onEdit = { editingRule = rule },
                            onDelete = { rules = rules.filterNot { it.id == rule.id } },
                        )
                        SpacerHeight(10)
                    }
                }
                PrimaryButton(
                    if (rules.isEmpty()) stringResource(R.string.ed_add_reminder) else stringResource(R.string.ed_add_another_reminder),
                    modifier = Modifier.fillMaxWidth(),
                    icon = AppIcons.Add,
                ) { editingRule = newRule(noteId ?: NEW_NOTE_KEY).copy(completionMode = settings.defaultCompletionMode) }
            }

            SpacerHeight(14)
            FieldLabel(stringResource(R.string.ed_attachments))
            GlassCard(Modifier.fillMaxWidth(), corner = 18) {
                AttachmentStrip(
                    items = attachments,
                    onRemove = { item ->
                        if (!item.persisted) File(item.path).delete()
                        attachments = attachments.filterNot { it.key == item.key }
                    },
                    onClick = { item -> previewing = item },
                    onAdd = { type -> pickAttachment(type) },
                )
                SpacerHeight(10)
                Text(
                    stringResource(R.string.ed_attachment_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary,
                )
            }

            SpacerHeight(14)
            FieldLabel(stringResource(R.string.ed_behavior))
            GlassCard(Modifier.fillMaxWidth(), corner = 18) {
                SettingSwitch(
                    stringResource(R.string.ed_enable_completion),
                    stringResource(R.string.ed_enable_completion_desc),
                    completionEnabled,
                ) { completionEnabled = it }
                SettingSwitch(
                    stringResource(R.string.ed_lock_screen_preview),
                    stringResource(R.string.ed_lock_screen_preview_desc),
                    previewEnabled,
                ) { previewEnabled = it }
            }

            SpacerHeight(20)
            PrimaryButton(stringResource(R.string.action_save), modifier = Modifier.fillMaxWidth(), enabled = !saving) { save() }
            Spacer(Modifier.height(28.dp))
        }
    }

    editingRule?.let { current ->
        RuleFormDialog(
            rule = current,
            onDismiss = { editingRule = null },
            onConfirm = { updated ->
                rules = if (rules.any { it.id == updated.id }) {
                    rules.map { if (it.id == updated.id) updated else it }
                } else {
                    rules + updated
                }
                editingRule = null
            },
        )
    }

    previewing?.let { item -> AttachmentPreview(item, onDismiss = { previewing = null }) }

    if (backgroundHint) {
        BackgroundRunDialog(
            onGo = {
                backgroundHint = false
                env.notifications.openBatteryOptimizationSettings()
                pendingNav?.invoke()
                pendingNav = null
            },
            onDismiss = {
                backgroundHint = false
                pendingNav?.invoke()
                pendingNav = null
            },
        )
    }
}

/** 新建流程里规则还没有真正的 noteId，保存时由 Repository 统一改写。 */
internal const val NEW_NOTE_KEY = "pending-note"

/** 备忘录正文字数上限。 */
private const val BODY_MAX_LENGTH = 1000

@Composable
private fun RuleSummaryRow(rule: ReminderRule, onEdit: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Bell, null, tint = AppColors.Primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(rule.type.labelRes), style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
                Text(
                    RecurrenceEngine.describe(context, rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }
            if (!rule.isEnabled) TagPill(stringResource(R.string.ed_disabled), tint = AppColors.TextTertiary)
            TextAction(stringResource(R.string.action_edit), modifier = Modifier.padding(start = 6.dp)) { onEdit() }
            Icon(
                AppIcons.Delete,
                stringResource(R.string.ed_delete_reminder),
                tint = AppColors.TextTertiary,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(18.dp)
                    .noRippleClickable(onDelete),
            )
        }
        val endDate = rule.endDate
        if (endDate != null) {
            Text(
                stringResource(R.string.ed_valid_until, endDate),
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(start = 25.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun outlinedField() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AppColors.Primary,
    unfocusedBorderColor = Color(0xFFC4D2EA),
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    cursorColor = AppColors.Primary,
)

@Composable
internal fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextTertiary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = AppColors.Primary,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFE2E8F3),
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun RuleFormDialog(
    rule: ReminderRule,
    onDismiss: () -> Unit,
    onConfirm: (ReminderRule) -> Unit,
) {
    var draft by remember(rule) { mutableStateOf(rule) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextAction(stringResource(R.string.ed_done)) { onConfirm(draft) } },
        dismissButton = { TextAction(stringResource(R.string.action_cancel), color = AppColors.TextSecondary, onClick = onDismiss) },
        title = { AppDialogTitle(stringResource(R.string.ed_reminder_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RuleForm(draft) { draft = it }
            }
        },
        containerColor = Color.White,
    )
}

@Composable
private fun AttachmentPreview(item: AttachmentUi, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextAction(stringResource(R.string.action_close), onClick = onDismiss) },
        title = { AppDialogTitle(item.name) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val bitmap = remember(item.path) { FileStore.decodeImage(item.path) }
                if (bitmap != null) {
                    Image(
                        bitmap,
                        item.name,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        "${item.path}\n${FileStore.formatSize(item.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        containerColor = Color.White,
    )
}
