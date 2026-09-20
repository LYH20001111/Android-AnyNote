package com.skyanchor.anynote.reminder

import com.skyanchor.anynote.core.humanSpan
import com.skyanchor.anynote.data.SettingsStore
import com.skyanchor.anynote.data.dao.FolderDao
import com.skyanchor.anynote.data.dao.NoteDao
import com.skyanchor.anynote.data.dao.ReminderDao
import com.skyanchor.anynote.data.entity.CompletionMode
import com.skyanchor.anynote.data.entity.HealthKind
import com.skyanchor.anynote.data.entity.NoteStatus
import com.skyanchor.anynote.data.entity.OccurrenceStatus
import com.skyanchor.anynote.data.entity.ReminderOccurrence
import java.time.Instant

/**
 * 提醒状态机的唯一入口（基线 §35-D、§7、§8、§9、§15）。
 *
 * Scheduled → Triggered →（Completed | Snoozed→Triggered | Skipped | Expired | Cancelled）
 *
 * 关键不变量：
 * - Snooze / Skip / 自动重复都只作用于当前这一条 ReminderOccurrence；
 * - 长期 ReminderRule 只在"完成后结束整个重复系列"或用户手动停用时才改变。
 */
class ReminderCoordinator(
    private val noteDao: NoteDao,
    private val reminderDao: ReminderDao,
    private val folderDao: FolderDao,
    private val settings: SettingsStore,
    private val scheduler: ReminderScheduler,
    private val notifications: NotificationHelper,
) {

    /** 系统闹钟到点。只负责把事件标记为已触达并展示通知。 */
    fun trigger(occurrenceId: String) {
        val occurrence = reminderDao.getOccurrence(occurrenceId) ?: return
        val note = noteDao.get(occurrence.noteId) ?: return
        // 系统既然把闹钟送到了，这一次就该触达。expired 只可能来自我们自己重建调度时的判定。
        val deliverable = occurrence.pending || occurrence.status == OccurrenceStatus.EXPIRED
        if (note.status != NoteStatus.ACTIVE || !deliverable) {
            scheduler.cancel(occurrence)
            return
        }
        if (!settings.notificationsEnabled) {
            scheduler.cancel(occurrence)
            return
        }

        val now = System.currentTimeMillis()
        val outcome = notifications.show(
            occurrence = occurrence,
            note = note,
            folderName = folderDao.get(note.folderId)?.name,
            pendingCount = reminderDao.pendingActive().size,
            overdueMs = if (occurrence.overdue) now - occurrence.scheduledAt else 0L,
        )
        if (outcome != ShowOutcome.Posted) {
            handleDeliveryFailure(occurrence, outcome, now)
            return
        }

        val triggered = occurrence.copy(
            status = OccurrenceStatus.TRIGGERED,
            triggeredAt = occurrence.triggeredAt ?: now,
            updatedAt = now,
        )
        reminderDao.update(triggered)
        scheduleAutoRepeat(triggered)
        topUpSeries(triggered.ruleId)
    }

    /**
     * 投递失败绝不能把事件写成"已触达"：通知没弹出去、状态却已经消费掉，用户就永远收不到这一次。
     *
     * 权限未授予时原样保留即可——下一次全量重建会把它当作逾期事件补发，不需要轮询重试。
     * notify() 异常通常是瞬时的，标记 overdue_at 后只补一次闹钟，避免无限重试。
     */
    private fun handleDeliveryFailure(occurrence: ReminderOccurrence, outcome: ShowOutcome, now: Long) {
        val reason = when (outcome) {
            ShowOutcome.NoPermission -> "POST_NOTIFICATIONS 未授权"
            is ShowOutcome.Error -> "notify() 抛出 ${outcome.cause}"
            else -> "未知"
        }
        if (outcome is ShowOutcome.Error) {
            val retry = occurrence.copy(
                overdueAt = occurrence.overdueAt ?: now,
                nextAlarmAt = now + DELIVERY_RETRY_MS,
                updatedAt = now,
            )
            reminderDao.update(retry)
            scheduler.arm(retry)
        }
        reminderDao.recordHealth(
            HealthKind.DELIVERY_BLOCKED.storage,
            reason = reason,
            ruleId = occurrence.ruleId,
            occurrenceId = occurrence.id,
            scheduledAt = occurrence.scheduledAt,
        )
    }

    /**
     * 补发"系统根本没送到、但还在时效内"的提醒（基线 §24）。
     *
     * 补发不直接弹通知，而是把 nextAlarmAt 挪到几十秒后重新走 [trigger] 的正常链路——
     * 自动重复、补齐窗口、角标这些副作用因此只有一份实现。返回本次排上的条数。
     * 必须在 [ReminderScheduler.syncAll] 的过期判定之前调用。
     */
    fun recoverMissed(now: Long = System.currentTimeMillis()): Int {
        if (!settings.notificationsEnabled) return 0
        val notBefore = now - settings.missedGraceHours * 60L * 60_000L
        val candidates = reminderDao.missedCandidates(now, MISSED_REDETECT_MS, notBefore, MAX_REDELIVER_PER_SYNC)
        candidates.forEachIndexed { index, occurrence ->
            val at = now + REDELIVER_DELAY_MS + index * REDELIVER_STAGGER_MS
            val updated = occurrence.copy(overdueAt = now, nextAlarmAt = at, updatedAt = now)
            reminderDao.update(updated)
            val result = scheduler.arm(updated)
            reminderDao.recordHealth(
                HealthKind.MISSED_REDELIVERED.storage,
                reason = "错过 ${humanSpan(now - occurrence.scheduledAt)}，已排队补发（${result.label}）",
                ruleId = occurrence.ruleId,
                occurrenceId = occurrence.id,
                scheduledAt = occurrence.scheduledAt,
            )
        }
        if (candidates.isNotEmpty()) notifications.refreshBadge()
        return candidates.size
    }

    /** 完成本次提醒事件（基线 §7、§15）。 */
    fun complete(occurrenceId: String) {
        val occurrence = reminderDao.getOccurrence(occurrenceId) ?: return
        if (occurrence.status == OccurrenceStatus.COMPLETED) return
        val now = System.currentTimeMillis()

        scheduler.cancel(occurrence)
        notifications.cancel(occurrence.notificationId)
        reminderDao.update(
            occurrence.copy(
                status = OccurrenceStatus.COMPLETED,
                completedAt = now,
                updatedAt = now,
            )
        )

        val rule = reminderDao.getRule(occurrence.ruleId)
        if (rule != null && rule.completionMode == CompletionMode.END_SERIES && rule.type.recurring) {
            endSeries(rule.id)
        } else if (rule != null) {
            scheduler.syncRule(rule, now)
        }
        settleNote(occurrence.noteId)
        notifications.refreshBadge()
    }

    /**
     * 稍后提醒（基线 §9.1）：只把当前事件的闹钟往后挪，
     * 绝不修改重复规则里"每周一 09:00"这样的长期语义。
     */
    fun snooze(occurrenceId: String, minutes: Int) {
        val occurrence = reminderDao.getOccurrence(occurrenceId) ?: return
        if (occurrence.status == OccurrenceStatus.COMPLETED) return
        val at = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        val updated = occurrence.copy(
            status = OccurrenceStatus.SNOOZED,
            snoozedUntil = at,
            nextAlarmAt = at,
            updatedAt = System.currentTimeMillis(),
        )
        notifications.cancel(occurrence.notificationId)
        reminderDao.update(updated)
        scheduler.rearm(updated)
        notifications.refreshBadge()
    }

    /** 跳过本次（基线 §6.4）：本次 skipped，后续重复照常。 */
    fun skip(occurrenceId: String) {
        val occurrence = reminderDao.getOccurrence(occurrenceId) ?: return
        if (occurrence.status == OccurrenceStatus.COMPLETED) return
        val now = System.currentTimeMillis()
        scheduler.cancel(occurrence)
        notifications.cancel(occurrence.notificationId)
        reminderDao.update(
            occurrence.copy(status = OccurrenceStatus.SKIPPED, skippedAt = now, updatedAt = now)
        )
        reminderDao.getRule(occurrence.ruleId)?.takeIf { it.type.recurring }?.let { scheduler.syncRule(it, now) }
        settleNote(occurrence.noteId)
        notifications.refreshBadge()
    }

    /** 详情页"完成"：处理该备忘录当前全部待办事件。 */
    fun completeNote(noteId: String) {
        reminderDao.pendingForNote(noteId).map { it.id }.forEach { complete(it) }
    }

    fun snoozeNote(noteId: String, minutes: Int) {
        reminderDao.pendingForNote(noteId).map { it.id }.forEach { snooze(it, minutes) }
    }

    fun skipCurrent(noteId: String) {
        reminderDao.pendingForNote(noteId).firstOrNull()?.let { skip(it.id) }
    }

    /** 结束整个重复系列：停用规则并清掉它所有未触发的事件。 */
    private fun endSeries(ruleId: String) {
        (reminderDao.scheduledForRule(ruleId) + reminderDao.overdueForRule(ruleId)).forEach {
            scheduler.cancel(it)
            reminderDao.deleteOccurrence(it.id)
        }
        reminderDao.setRuleEnabled(ruleId, false)
    }

    /**
     * 自动重复（基线 §8）：只针对当前这一次未处理的提醒追加闹钟，
     * 不推进、也不覆盖原始周期规则。
     */
    private fun scheduleAutoRepeat(occurrence: ReminderOccurrence) {
        val rule = reminderDao.getRule(occurrence.ruleId) ?: return
        if (!rule.autoRepeatEnabled || !settings.notificationsEnabled) return
        val note = noteDao.get(occurrence.noteId) ?: return
        if (!note.completionEnabled) return
        if (rule.autoRepeatLimit > 0 && occurrence.autoRepeatCount >= rule.autoRepeatLimit) return

        val at = System.currentTimeMillis() + rule.autoRepeatIntervalMinutes.coerceAtLeast(1) * 60_000L
        val next = occurrence.copy(
            nextAlarmAt = at,
            autoRepeatCount = occurrence.autoRepeatCount + 1,
            updatedAt = System.currentTimeMillis(),
        )
        reminderDao.update(next)
        scheduler.arm(next)
    }

    /**
     * 一条备忘录没有任何待处理事件、也没有未来会触发的规则时，从当前列表移入历史（基线 §15）。
     */
    private fun settleNote(noteId: String) {
        val note = noteDao.get(noteId) ?: return
        if (note.status != NoteStatus.ACTIVE) return
        if (reminderDao.pendingForNote(noteId).isNotEmpty()) return
        val now = Instant.now()
        val hasFuture = reminderDao.rulesOfNote(noteId).any {
            it.isEnabled && RecurrenceEngine.nextOccurrence(it, now) != null
        }
        if (!hasFuture && note.completionEnabled) noteDao.markCompleted(noteId, true)
    }

    /**
     * 每次真实触达后补齐该规则的待调度窗口：每条规则只物化有限个未来事件，
     * 用户长期不打开 App 时窗口会耗尽，之后再也没有闹钟可依赖（基线 §24）。
     */
    private fun topUpSeries(ruleId: String) {
        val rule = reminderDao.getRule(ruleId) ?: return
        if (!rule.type.recurring) return
        if (reminderDao.scheduledForRule(ruleId).size >= MIN_SCHEDULED_AHEAD) return
        scheduler.syncRule(rule)
    }

    companion object {
        private const val MIN_SCHEDULED_AHEAD = 3

        /** notify() 瞬时失败后的唯一一次重试间隔。 */
        private const val DELIVERY_RETRY_MS = 60_000L

        /**
         * 计划时间过去多久才算"确实错过"。必须与调度器的过期宽限同量级：
         * 短了会在正常投递还在路上时误判补发，长了真错过也要多等才能被补回。
         */
        private const val MISSED_REDETECT_MS = 3L * 60 * 1000

        /** 一次重建最多补发 3 条，剩下的留给下一次，避免开机瞬间被历史提醒刷屏。 */
        private const val MAX_REDELIVER_PER_SYNC = 3

        private const val REDELIVER_DELAY_MS = 30_000L
        private const val REDELIVER_STAGGER_MS = 15_000L
    }
}
