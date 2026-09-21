package com.skyanchor.anynote.ui.reminder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.skyanchor.anynote.data.entity.ReminderRule
import com.skyanchor.anynote.reminder.RecurrenceEngine
import com.skyanchor.anynote.ui.AppEnv
import com.skyanchor.anynote.ui.components.GlassCard
import com.skyanchor.anynote.ui.components.InfoBanner
import com.skyanchor.anynote.ui.components.PrimaryButton
import com.skyanchor.anynote.ui.components.ScreenScaffold
import com.skyanchor.anynote.ui.components.TextAction
import com.skyanchor.anynote.ui.loadAsync
import com.skyanchor.anynote.ui.settings.BackgroundRunDialog
import com.skyanchor.anynote.ui.settings.ConfirmDialog
import com.skyanchor.anynote.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReminderRuleEditorScreen(env: AppEnv, noteId: String, ruleId: String?) {
    val repo = env.repository
    val scope = rememberCoroutineScope()

    val persisted = loadAsync(ruleId ?: "new") { ruleId?.let { repo.reminders.getRule(it) } }
    val isNew = ruleId == null
    var draft by remember(ruleId, persisted.value) {
        mutableStateOf(persisted.value ?: newRule(noteId).copy(completionMode = repo.settings.defaultCompletionMode))
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var backgroundHint by remember { mutableStateOf(false) }

    fun save() {
        if (saving) return
        saving = true
        val snapshot = draft
        scope.launch {
            withContext(Dispatchers.IO) { repo.saveRule(snapshot) }
            saving = false
            env.state.invalidate()
            // 电池优化未豁免时，清后台/锁屏后的到点投递可能被系统延后或拦截。
            // 引导只在"刚保存了启用规则"时弹一次，入口长期保留在通知设置页。
            val needHint = snapshot.isEnabled && repo.settings.notificationsEnabled &&
                !env.notifications.ignoresBatteryOptimizations && !repo.settings.backgroundHintShown
            if (needHint) {
                repo.settings.backgroundHintShown = true
                backgroundHint = true
            } else {
                env.router.pop()
            }
        }
    }

    if (!isNew && persisted.value == null) {
        ScreenScaffold(title = "提醒设置", onBack = { env.router.pop() }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("加载中…", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextTertiary)
            }
        }
        return
    }

    ScreenScaffold(
        title = if (isNew) "添加提醒" else "提醒设置",
        onBack = { env.router.pop() },
        actions = {
            if (!isNew) {
                TextAction("删除", color = AppColors.Danger) { confirmDelete = true }
            }
            TextAction(if (saving) "保存中…" else "保存") { save() }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassCard(Modifier.fillMaxWidth().padding(top = 6.dp), corner = 22) {
                RuleForm(draft) { draft = it }
            }
            InfoBanner("预览：${RecurrenceEngine.describe(draft)}")
            PrimaryButton(
                "保存这条提醒",
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
            ) { save() }
            Text(
                "修改规则会取消旧闹钟并按新规则重新登记（基线 §23），已触达、已推迟的这一次事件保持不变。",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextTertiary,
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除这条提醒？",
            text = "它会连同尚未触发的提醒事件一起移除。",
            confirmLabel = "删除",
            danger = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                val id = draft.id
                scope.launch {
                    withContext(Dispatchers.IO) { repo.deleteRule(id) }
                    env.state.invalidate()
                    env.router.pop()
                }
            },
        )
    }

    if (backgroundHint) {
        BackgroundRunDialog(
            onGo = {
                backgroundHint = false
                env.notifications.openBatteryOptimizationSettings()
                env.router.pop()
            },
            onDismiss = {
                backgroundHint = false
                env.router.pop()
            },
        )
    }
}

/** 供编辑页在内存中构造"至少能保存"的规则草稿。 */
fun blankRuleFor(noteId: String): ReminderRule = newRule(noteId)
