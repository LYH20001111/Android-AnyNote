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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
            Text("加载中…", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextTertiary)
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
        onFailed = { env.toast("附件导入失败，请重试") },
    )

    val keepKeys = attachments.filter { it.persisted }.map { it.key }.toSet()
    val removed = initialAttachments.filter { it.persisted && it.key !in keepKeys }
    val added = attachments.filterNot { it.persisted }

    fun discardUnsavedFiles() = attachments.filterNot { it.persisted }.forEach { File(it.path).delete() }

    fun save() {
        if (body.isBlank() && title.isBlank()) {
            env.toast("先写点什么吧")
            return
        }
        if (folderId.isEmpty()) {
            env.toast("请先在\"我的 → 分类管理\"里新建一个分类")
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
        title = if (noteId == null) "新建备忘录" else "编辑备忘录",
        onBack = {
            discardUnsavedFiles()
            env.router.pop()
        },
        actions = {
            TextAction(if (saving) "保存中…" else "保存") { if (!saving) save() }
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
                TextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("标题（可留空）", color = AppColors.TextTertiary) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = AppColors.TextPrimary),
                    colors = borderlessField(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(2.dp))
                TextField(
                    value = body,
                    onValueChange = { body = it },
                    placeholder = { Text("记录内容…", color = AppColors.TextTertiary) },
                    minLines = 4,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppColors.TextPrimary),
                    colors = borderlessField(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SpacerHeight(14)
            FieldLabel("分类")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(folders, key = { it.id }) { folder ->
                    FilterChip(folder.name, folder.id == folderId) { folderId = folder.id }
                }
            }

            SpacerHeight(14)
            FieldLabel("优先级")
            SegmentedTabs(Priority.entries.map { it to it.label }, priority) { priority = it }

            SpacerHeight(14)
            FieldLabel("提醒（支持多条）")
            GlassCard(Modifier.fillMaxWidth(), corner = 18) {
                if (rules.isEmpty()) {
                    Text(
                        "尚未设置提醒，保存后这条备忘录会出现在「无提醒」分组。",
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
                    if (rules.isEmpty()) "添加提醒" else "再添加一条提醒",
                    modifier = Modifier.fillMaxWidth(),
                    icon = AppIcons.Add,
                ) { editingRule = newRule(noteId ?: NEW_NOTE_KEY).copy(completionMode = settings.defaultCompletionMode) }
            }

            SpacerHeight(14)
            FieldLabel("附件")
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
                    "语音与视频附件暂未开放；图片、文件会复制进应用私有目录，删除原文件不影响备忘录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary,
                )
            }

            SpacerHeight(14)
            FieldLabel("行为")
            GlassCard(Modifier.fillMaxWidth(), corner = 18) {
                SettingSwitch(
                    "启用完成状态",
                    "通知上出现「完成 / 稍后提醒 / 跳过本次」",
                    completionEnabled,
                ) { completionEnabled = it }
                SettingSwitch(
                    "锁屏显示正文",
                    "关闭后通知只提示「你有一个新的提醒」",
                    previewEnabled,
                ) { previewEnabled = it }
            }

            SpacerHeight(20)
            PrimaryButton("保存", modifier = Modifier.fillMaxWidth(), enabled = !saving) { save() }
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

@Composable
private fun RuleSummaryRow(rule: ReminderRule, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Bell, null, tint = AppColors.Primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(rule.type.label, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
                Text(
                    RecurrenceEngine.describe(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }
            if (!rule.isEnabled) TagPill("已停用", tint = AppColors.TextTertiary)
            TextAction("编辑", modifier = Modifier.padding(start = 6.dp)) { onEdit() }
            Icon(
                AppIcons.Delete,
                "删除提醒",
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
                "有效期至 $endDate",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(start = 25.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun borderlessField() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
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
        confirmButton = { TextAction("完成") { onConfirm(draft) } },
        dismissButton = { TextAction("取消", color = AppColors.TextSecondary, onClick = onDismiss) },
        title = { Text("提醒设置", style = MaterialTheme.typography.titleMedium) },
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
        confirmButton = { TextAction("关闭", onClick = onDismiss) },
        title = { Text(item.name, style = MaterialTheme.typography.titleMedium) },
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
