package com.skyanchor.anynote.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.skyanchor.anynote.MainActivity
import com.skyanchor.anynote.core.dateTimeText
import com.skyanchor.anynote.data.SettingsStore
import com.skyanchor.anynote.data.dao.NoteDao
import com.skyanchor.anynote.data.dao.ReminderDao
import com.skyanchor.anynote.data.db.AnyNoteDatabase
import com.skyanchor.anynote.data.entity.HealthKind
import com.skyanchor.anynote.data.entity.NoteStatus
import com.skyanchor.anynote.data.entity.OccurrenceStatus
import com.skyanchor.anynote.data.entity.ReminderOccurrence
import com.skyanchor.anynote.data.entity.ReminderRule
import java.time.Instant
import java.time.ZoneId

/**
 * 一次闹钟注册的结果。它必须能被调用方观察到：注册失败如果只被 `runCatching` 吞掉，
 * "到点什么都没发生"就永远无从定位（基线 §24）。
 */
enum class ArmResult(val label: String) {
    /** setAlarmClock：不会被 Doze 合并，状态栏会出现闹钟图标。 */
    ALARM_CLOCK("最高优先级闹钟"),

    EXACT("精确闹钟"),
    WINDOW("时间窗口"),
    SKIPPED("未注册"),
    FAILED("注册失败"),
}

/** 调度器读数，供设置页自检使用。`systemNextAlarmAt` 为 null 说明系统侧根本没有闹钟。 */
data class SchedulerStatus(
    val plannedNextAt: Long?,
    val systemNextAlarmAt: Long?,
    val alarmClockInUse: Boolean,
)

/**
 * 提醒调度层（基线 §22–§24）。
 *
 * 架构原则：本地数据库是唯一事实来源，App 不做"后台常驻轮询"。
 * 引擎算出未来触发点 → 写入 reminder_occurrences → 注册给 AlarmManager → 由系统到点触达。
 * 任何规则变更都必须走"取消旧调度 → 更新数据库 → 重新计算 → 注册新调度"的完整流程。
 *
 * 抗冻结的三个关键设计：
 * 1. 全局最早的那一次用 [AlarmManager.setAlarmClock] 注册——它是唯一不被 Doze 合并、
 *    厂商省电策略拦截最弱的通道，代价是状态栏会出现闹钟图标（可用 preferAlarmClock 关闭）；
 * 2. 每条规则只注册最早 [ARMED_PREFIX] 个事件。熄屏时未免除电池优化的应用每个维护窗口
 *    只会被兑现一个 while-idle 闹钟，把 12 个全挂上去等于让后面 9 个自相排队；
 * 3. 一个 6 小时节奏的看门狗闹钟负责自愈：补前沿、补发错过的、清理诊断表。
 *    它只用非精确的 while-idle 闹钟，绝不占用 setAlarmClock 名额，也不产生任何通知。
 */
class ReminderScheduler(
    context: Context,
    private val db: AnyNoteDatabase,
    private val reminderDao: ReminderDao,
    private val noteDao: NoteDao,
    private val settings: SettingsStore,
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val appContext = context.applicationContext

    /**
     * App 启动 / 开机 / 权限恢复 / 时区变化后的全量重建。
     * 逾期补发（[ReminderCoordinator.recoverMissed]）必须在此之前完成：
     * 下面的过期判定会把计划时间已过去的事件归档，补发要抢在它前面把 next_alarm_at 挪到未来。
     */
    fun syncAll() {
        val now = System.currentTimeMillis()
        val expired = reminderDao.expireUnfiredBefore(now - EXPIRE_GRACE_MS)
        if (expired > 0) {
            reminderDao.recordHealth(
                HealthKind.MISSED_DROPPED.storage,
                reason = "$expired 条已过补发时效，按过期归档",
            )
        }
        // 总开关关闭时必须真正撤下闹钟：状态栏的闹钟图标、到点唤醒的进程都归系统侧，
        // 只靠 trigger() 到达后再吞掉通知，等于"暂停提醒"仍在按点耗电并占着闹钟名额。
        if (!settings.notificationsEnabled) {
            cancelAll()
            reminderDao.pruneHealth(HEALTH_KEEP_LAST)
            return
        }
        reminderDao.activeRules().forEach { syncRule(it, now) }
        // 已触发但用户尚未处理的事件，闹钟可能随重启丢失，需要重新挂上
        reminderDao.pendingAll().filter { it.status != OccurrenceStatus.SCHEDULED }.forEach { arm(it) }
        reminderDao.recordHealth(HealthKind.SELF_HEAL.storage, reason = "全量重建调度")
        reminderDao.pruneHealth(HEALTH_KEEP_LAST)
        armWatchdog(now)
    }

    fun syncNote(noteId: String) {
        val now = System.currentTimeMillis()
        if (noteDao.get(noteId)?.status != NoteStatus.ACTIVE) return
        reminderDao.rulesOfNote(noteId).forEach { syncRule(it, now) }
        armWatchdog(now)
    }

    /**
     * 重排一条规则：先取消它全部未触发的事件（基线 §23 禁止只更新数据库），
     * 再按引擎结果重新物化并注册。已触发/已稍后提醒的"当前这一次"不受影响，
     * 排队补发中的那一次也不受影响（见 [ReminderDao.scheduledForRule]）。
     *
     * 物化前必须按 [ReminderDao.occupiedTimesForRule] 去重：表里已有同一触发时刻的行
     * （提前完成/跳过/推迟的归档行）就不能再建新行，否则"完成一次 → 重排 → 复活同一次"
     * 会无限循环，历史记录里堆满一模一样的提醒。
     */
    fun syncRule(rule: ReminderRule, now: Long = System.currentTimeMillis()) {
        reminderDao.scheduledForRule(rule.id).forEach { cancel(it) }
        reminderDao.deleteScheduledForRule(rule.id)

        val note = noteDao.get(rule.noteId) ?: return
        if (!rule.isEnabled || !settings.notificationsEnabled || note.status.storage != "active") return

        val zone = ZoneId.systemDefault()
        val occupied = reminderDao.occupiedTimesForRule(rule.id)
        val instants = RecurrenceEngine
            .occurrences(rule, Instant.ofEpochMilli(now), Instant.ofEpochMilli(now + SEARCH_WINDOW_MS), zone)
            .map { it.toEpochMilli() }
            .filter { it > now && it !in occupied }
            .take(MAX_MATERIALIZED)

        instants.forEachIndexed { index, millis ->
            val occurrence = ReminderOccurrence(
                id = db.newId(),
                ruleId = rule.id,
                noteId = rule.noteId,
                scheduledAt = millis,
                nextAlarmAt = millis,
                triggeredAt = null,
                completedAt = null,
                status = OccurrenceStatus.SCHEDULED,
                snoozedUntil = null,
                skippedAt = null,
                overdueAt = null,
                notificationId = db.nextNotificationSeq(),
                autoRepeatCount = 0,
                createdAt = now,
                updatedAt = now,
            )
            reminderDao.insertOccurrence(occurrence)
            // 只注册前沿的少数几个，其余留在库里等前沿推进时补齐（见类注释第 2 点）
            if (index < ARMED_PREFIX) arm(occurrence)
        }
    }

    /**
     * 把每条规则"最早且应当在场"的事件重新挂一遍。arm 是幂等的，所以这个方法可以随便重复调用，
     * 它是看门狗自愈的实际动作：长期不打开 App 时靠它把窗口往前推（基线 §24）。
     */
    fun refreshFrontier() {
        val now = System.currentTimeMillis()
        // 关闭期间不重挂提醒，但看门狗必须继续续命：它是"用户再次打开 App 之前"唯一的自愈心跳。
        if (!settings.notificationsEnabled) {
            armWatchdog(now)
            return
        }
        reminderDao.activeRules().forEach { rule ->
            val frontier = (reminderDao.scheduledForRule(rule.id) + reminderDao.overdueForRule(rule.id))
                .filter { it.nextAlarmAt > now }
                .sortedBy { it.nextAlarmAt }
                .take(ARMED_PREFIX)
            frontier.forEach { arm(it) }
        }
        reminderDao.pendingAll().filter { it.status != OccurrenceStatus.SCHEDULED && it.nextAlarmAt > now }
            .forEach { arm(it) }
        detectClearedAlarms(now)
        armWatchdog(now)
    }

    /**
     * 「退出/划掉后台后就不提醒」的唯一可机读证据：库里有一次已经到了点、系统侧却再也没有
     * 任何闹钟登记，说明那一次注册被厂商后台管控或系统清理吞掉了（基线 §24、§33）。
     *
     * [AlarmManager.getNextAlarmClock] 只反映 setAlarmClock 那一档，且是整机读数，
     * 所以这里只在"完全没有闹钟"时定性，绝不在读数属于别的时刻时下结论——
     * 精确闹钟本来就不出现在这个读数里，误报会把正常状态说成故障。
     */
    private fun detectClearedAlarms(now: Long) {
        if (!settings.preferAlarmClock) return
        val missed = reminderDao.overdueForRules(now - ALARM_STALE_MS).ifEmpty { return }
        val systemHasAlarm = runCatching { alarmManager.nextAlarmClock != null }.getOrDefault(true)
        if (systemHasAlarm) return
        reminderDao.recordHealth(
            HealthKind.ALARM_CLEARED.storage,
            reason = "库里 ${missed.size} 条已到点、系统侧却查不到任何闹钟",
            ruleId = missed.first().ruleId,
            occurrenceId = missed.first().id,
            scheduledAt = missed.first().scheduledAt,
            detail = "计划 ${dateTimeText(missed.first().nextAlarmAt)} 的注册未被系统兑现",
        )
    }

    /** 移入回收站：取消全部未来通知，数据保留（基线 §16）。 */
    fun cancelNote(noteId: String) {
        val now = System.currentTimeMillis()
        reminderDao.pendingForNote(noteId).forEach { occurrence ->
            cancel(occurrence)
            if (occurrence.status == OccurrenceStatus.SCHEDULED) {
                reminderDao.deleteOccurrence(occurrence.id)
            } else {
                reminderDao.update(occurrence.copy(status = OccurrenceStatus.CANCELLED, updatedAt = now))
            }
        }
    }

    /** 撤下系统侧全部闹钟：通知总开关关闭时调用，只动闹钟、不动数据库里的规则与事件。 */
    fun cancelAll() {
        reminderDao.armedOccurrences().forEach { cancel(it) }
    }

    /**
     * 把事件当前的 nextAlarmAt 挂到系统闹钟上，并返回注册结果。
     * 全局最早的那一次享受 setAlarmClock 待遇——同一时刻系统只需要一个"用户可见闹钟"。
     */
    fun arm(occurrence: ReminderOccurrence): ArmResult {
        if (!settings.notificationsEnabled) return ArmResult.SKIPPED
        val at = occurrence.nextAlarmAt
        if (at <= System.currentTimeMillis()) return ArmResult.SKIPPED

        val primary = settings.preferAlarmClock && exactAlarmAllowed() &&
            reminderDao.nextPendingOverall()?.id == occurrence.id
        val pending = pendingIntentFor(occurrence)

        fun register(): ArmResult = when {
            primary -> {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, alarmShowIntent()), pending)
                ArmResult.ALARM_CLOCK
            }
            // 未授予精确闹钟时退化为 10 分钟窗口，提醒仍然会到，只是不保证秒级
            exactAlarmAllowed() -> {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                ArmResult.EXACT
            }
            else -> {
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending)
                ArmResult.WINDOW
            }
        }

        return runCatching {
            alarmManager.cancel(pending) // 同槽位重注册，保证幂等
            register()
        }.getOrElse { failure ->
            reminderDao.recordHealth(
                HealthKind.ARM_FAILED.storage,
                reason = failure.javaClass.simpleName,
                ruleId = occurrence.ruleId,
                occurrenceId = occurrence.id,
                scheduledAt = occurrence.scheduledAt,
                detail = failure.message,
            )
            // 精确注册被拒时至少落进一个时间窗口，别让用户完全收不到
            val rescued = runCatching {
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending)
                ArmResult.WINDOW
            }.getOrNull()
            if (rescued != null) {
                reminderDao.recordHealth(
                    HealthKind.ARM_DEGRADED.storage,
                    reason = "精确闹钟注册失败，已退化为窗口闹钟",
                    ruleId = occurrence.ruleId,
                    occurrenceId = occurrence.id,
                    scheduledAt = occurrence.scheduledAt,
                )
            }
            rescued ?: ArmResult.FAILED
        }
    }

    fun cancel(occurrence: ReminderOccurrence) {
        val pending = existingPendingIntent(occurrence) ?: return
        runCatching { alarmManager.cancel(pending) }
    }

    /** 稍后提醒与自动重复都只移动"当前这一次"的闹钟，不触碰长期规则（基线 §8、§9.1）。 */
    fun rearm(occurrence: ReminderOccurrence) {
        cancel(occurrence)
        arm(occurrence)
    }

    fun exactAlarmAllowed(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        alarmManager.canScheduleExactAlarms()
    } else {
        true
    }

    /**
     * 看门狗：6 小时一次的非精确闹钟。进程被强行停止后 BOOT_COMPLETED 不再送达，
     * 它是唯一还能自愈的通道，所以必须比任何一条提醒都活得久（基线 §24）。
     */
    fun armWatchdog(now: Long = System.currentTimeMillis()) {
        val at = now + WATCHDOG_INTERVAL_MS
        runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, watchdogPendingIntent()) }
            .onFailure {
                reminderDao.recordHealth(
                    HealthKind.ARM_FAILED.storage,
                    reason = "看门狗闹钟注册失败：${it.javaClass.simpleName}",
                )
            }
    }

    /** 系统侧到底还挂着我们的闹钟吗——设置页唯一能拿到的硬证据。 */
    fun selfCheck(): SchedulerStatus {
        val next = reminderDao.nextPendingOverall()
        val systemNext = runCatching { alarmManager.nextAlarmClock?.triggerTime }.getOrNull()
        return SchedulerStatus(
            plannedNextAt = next?.nextAlarmAt,
            systemNextAlarmAt = systemNext,
            alarmClockInUse = settings.preferAlarmClock && exactAlarmAllowed() && next != null,
        )
    }

    /** 同一事件的原始触发、稍后提醒、自动重复共用一个 PendingIntent 槽位，重复注册即覆盖。 */
    private fun pendingIntentFor(occurrence: ReminderOccurrence): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        occurrence.notificationId,
        triggerIntent(occurrence),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 取消用的 PendingIntent 只在已经存在时才创建。
     * 用 FLAG_UPDATE_CURRENT 去 cancel 会凭空造一个不占闹钟的 PendingIntent，白耗配额。
     */
    private fun existingPendingIntent(occurrence: ReminderOccurrence): PendingIntent? = PendingIntent.getBroadcast(
        appContext,
        occurrence.notificationId,
        triggerIntent(occurrence),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun triggerIntent(occurrence: ReminderOccurrence): Intent =
        Intent(appContext, ReminderReceiver::class.java).apply {
            action = ReminderIntents.ACTION_TRIGGER
            data = Uri.parse("anynote://reminder/${occurrence.id}")
            putExtra(ReminderIntents.EXTRA_OCCURRENCE_ID, occurrence.id)
            putExtra(ReminderIntents.EXTRA_NOTE_ID, occurrence.noteId)
        }

    private fun watchdogPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        WATCHDOG_REQUEST,
        Intent(appContext, ReminderReceiver::class.java).apply {
            action = ReminderIntents.ACTION_RESYNC
            data = Uri.parse("anynote://watchdog")
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 状态栏闹钟图标被点按时要跳到 App，绝不能复用触发广播——
     * 那会让用户点一下图标就"提前触达"一次还没到点的提醒。
     */
    private fun alarmShowIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        ALARM_SHOW_REQUEST,
        Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        /** 每条规则一次最多物化 12 个未来事件，App 每次启动或看门狗自愈时补齐窗口。 */
        private const val MAX_MATERIALIZED = 12

        /** 每条规则实际注册到系统的前沿数量，见类注释第 2 点。 */
        private const val ARMED_PREFIX = 3

        private const val SEARCH_WINDOW_MS = 1000L * 60 * 60 * 24 * 400
        private const val WINDOW_MS = 10L * 60 * 1000

        /** 闹钟投递总有延迟，过期判定至少要让位于正在送达的那一次。 */
        private const val EXPIRE_GRACE_MS = 3L * 60 * 1000

        /** 计划时刻过去多久仍未兑现，才算"系统侧把我们的一次闹钟清掉了"。留出正常投递延迟。 */
        private const val ALARM_STALE_MS = 10L * 60 * 1000

        private const val WATCHDOG_INTERVAL_MS = 6L * 60 * 60 * 1000

        /** 与 notificationId 的取值域不相交，避免撞掉真实提醒的闹钟槽位。 */
        private const val WATCHDOG_REQUEST = -7_777
        private const val ALARM_SHOW_REQUEST = -7_778

        private const val HEALTH_KEEP_LAST = 200
    }
}
