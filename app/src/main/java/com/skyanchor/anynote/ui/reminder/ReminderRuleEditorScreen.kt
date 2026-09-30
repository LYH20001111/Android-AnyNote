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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.R
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
    val context = LocalContext.current

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
        ScreenScaffold(title = stringResource(R.string.re_screen_title), onBack = { env.router.pop() }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.status_loading), style = MaterialTheme.typography.bodyMedium, color = AppColors.TextTertiary)
            }
        }
        return
    }

    ScreenScaffold(
        title = if (isNew) stringResource(R.string.re_add_reminder) else stringResource(R.string.re_screen_title),
        onBack = { env.router.pop() },
        actions = {
            if (!isNew) {
                TextAction(stringResource(R.string.action_delete), color = AppColors.Danger) { confirmDelete = true }
            }
            TextAction(if (saving) stringResource(R.string.re_saving) else stringResource(R.string.action_save)) { save() }
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
            InfoBanner(stringResource(R.string.re_preview, RecurrenceEngine.describe(context, draft)))
            PrimaryButton(
                stringResource(R.string.re_save_this_reminder),
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
            ) { save() }
            Text(
                stringResource(R.string.re_rule_change_note),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextTertiary,
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.re_delete_confirm_title),
            text = stringResource(R.string.re_delete_confirm_text),
            confirmLabel = stringResource(R.string.action_delete),
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
