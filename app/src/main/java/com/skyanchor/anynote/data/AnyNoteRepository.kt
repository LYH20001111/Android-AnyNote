package com.skyanchor.anynote.data

import com.skyanchor.anynote.core.compactDateTimeText
import com.skyanchor.anynote.data.dao.AttachmentDao
import com.skyanchor.anynote.data.dao.FolderDao
import com.skyanchor.anynote.data.dao.NoteDao
import com.skyanchor.anynote.data.dao.NoteQuery
import com.skyanchor.anynote.data.dao.ReminderDao
import com.skyanchor.anynote.data.entity.AttachmentType
import com.skyanchor.anynote.data.entity.CompletionMode
import com.skyanchor.anynote.data.entity.Folder
import com.skyanchor.anynote.data.entity.HealthEvent
import com.skyanchor.anynote.data.entity.HealthKind
import com.skyanchor.anynote.data.entity.Note
import com.skyanchor.anynote.data.entity.NoteCard
import com.skyanchor.anynote.data.entity.NoteStatus
import com.skyanchor.anynote.data.entity.OccurrenceStatus
import com.skyanchor.anynote.data.entity.Priority
import com.skyanchor.anynote.data.entity.ReminderOccurrence
import com.skyanchor.anynote.data.entity.ReminderRule
import com.skyanchor.anynote.reminder.IntervalUnit
import com.skyanchor.anynote.reminder.RecurrenceEngine
import com.skyanchor.anynote.reminder.RecurrenceType
import com.skyanchor.anynote.reminder.ReminderCoordinator
import com.skyanchor.anynote.reminder.ReminderScheduler
import com.skyanchor.anynote.reminder.SchedulerStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 新建/编辑备忘录时提交的内容。 */
data class NoteDraft(
    val id: String? = null,
    val title: String? = null,
    val body: String,
    val folderId: String,
    val priority: com.skyanchor.anynote.data.entity.Priority,
    val completionEnabled: Boolean,
    val notificationPreviewEnabled: Boolean,
)

/**
 * 应用唯一的数据入口。写操作一律遵循"更新数据库 → 重新调度系统闹钟"（基线 §23）。
 */
class AnyNoteRepository(
    val folders: FolderDao,
    val notes: NoteDao,
    val reminders: ReminderDao,
    val attachments: AttachmentDao,
    val settings: SettingsStore,
    private val scheduler: ReminderScheduler,
    private val coordinator: ReminderCoordinator,
) {

    // region queries

    fun folderMap(): Map<String, Folder> = folders.list().associateBy { it.id }

    /** 首页：活动备忘录 + 各自最紧迫的一条待处理事件，按时间升序。 */
    fun homeCards(query: String?, folderId: String?): List<NoteCard> =
        notes.query(
            NoteQuery(status = NoteStatus.ACTIVE, text = query, folderId = folderId)
        ).map { cardFor(it) }
            .sortedWith(
                compareBy<NoteCard> { it.nextOccurrence?.effectiveAt ?: Long.MAX_VALUE }
                    .thenByDescending { it.note.updatedAt }
            )

    /** 日历：某一天的事件 + 对应备忘录。 */
    fun dayEntries(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<NoteCard> {
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return reminders.occurrencesBetween(from, to)
            .mapNotNull { occurrence -> notes.get(occurrence.noteId)?.let { cardFor(it, occurrence) } }
            .sortedBy { it.nextOccurrence?.effectiveAt ?: 0L }
    }

    /** 日历页的当月打点：日 → 事件数。 */
    fun monthMarks(month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Map<Int, Int> {
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return reminders.occurrencesBetween(from, to)
            .groupingBy { Instant.ofEpochMilli(it.effectiveAt).atZone(zone).toLocalDate().dayOfMonth }
            .eachCount()
    }

    fun noteCard(noteId: String): NoteCard? = notes.get(noteId)?.let { cardFor(it) }

    fun detailOccurrences(noteId: String): List<ReminderOccurrence> =
        reminders.pendingForNote(noteId)

    fun history(
        statuses: List<OccurrenceStatus>,
        text: String?,
        folderId: String?,
        from: LocalDate?,
        to: LocalDate?,
    ): List<Pair<NoteCard, ReminderOccurrence>> {
        val zone = ZoneId.systemDefault()
        val fromMillis = from?.let { it.atStartOfDay(zone).toInstant().toEpochMilli() }
        val toMillis = to?.plusDays(1)?.let { it.atStartOfDay(zone).toInstant().toEpochMilli() }
        return reminders.history(statuses, text, folderId, fromMillis, toMillis).mapNotNull { occurrence ->
            notes.get(occurrence.noteId)?.let { Pair(cardFor(it, occurrence), occurrence) }
        }
    }

    fun trashedCards(): List<NoteCard> =
        notes.query(NoteQuery(status = NoteStatus.TRASHED)).map { cardFor(it) }

    // endregion

    // region note writes

    /**
     * 保存备忘录及其全部提醒规则。规则是整体替换语义：
     * 先取消旧调度，再写入，再按新规则重新计算并注册（基线 §23）。
     */
    fun saveNote(draft: NoteDraft, rules: List<ReminderRule>): Note {
        val now = System.currentTimeMillis()
        val existing = draft.id?.let { notes.get(it) }
        val note = existing?.copy(
            title = draft.title?.trim()?.takeIf { it.isNotEmpty() },
            body = draft.body,
            folderId = draft.folderId,
            priority = draft.priority,
            completionEnabled = draft.completionEnabled,
            notificationPreviewEnabled = draft.notificationPreviewEnabled,
            status = if (existing.status == NoteStatus.COMPLETED) NoteStatus.ACTIVE else existing.status,
            completedAt = null,
            updatedAt = now,
        ) ?: Note(
            id = UUID.randomUUID().toString(),
            folderId = draft.folderId,
            title = draft.title?.trim()?.takeIf { it.isNotEmpty() },
            body = draft.body,
            priority = draft.priority,
            status = NoteStatus.ACTIVE,
            notificationPreviewEnabled = draft.notificationPreviewEnabled,
            completionEnabled = draft.completionEnabled,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
            deletedAt = null,
        )

        if (existing == null) notes.insert(note) else notes.update(note)

        val keepIds = rules.map { it.id }.toSet()
        reminders.rulesOfNote(note.id).filter { it.id !in keepIds }.forEach { stale ->
            reminders.scheduledForRule(stale.id).forEach { scheduler.cancel(it) }
            reminders.deleteRule(stale.id)
        }

        rules.forEach { rule ->
            val stamped = rule.copy(noteId = note.id, updatedAt = now)
            if (reminders.getRule(stamped.id) == null) reminders.insertRule(stamped) else reminders.updateRule(stamped)
        }

        scheduler.syncNote(note.id)
        return note
    }

    fun deleteRule(ruleId: String) {
        val rule = reminders.getRule(ruleId) ?: return
        reminders.scheduledForRule(ruleId).forEach { scheduler.cancel(it) }
        reminders.deleteRule(ruleId)
        scheduler.syncNote(rule.noteId)
    }

    /** 只改一条规则（详情页/规则编辑页），不重写备忘录正文。 */
    fun saveRule(rule: ReminderRule) {
        val stamped = rule.copy(updatedAt = System.currentTimeMillis())
        if (reminders.getRule(stamped.id) == null) reminders.insertRule(stamped) else reminders.updateRule(stamped)
        if (!stamped.isEnabled) reminders.scheduledForRule(stamped.id).forEach { scheduler.cancel(it) }
        scheduler.syncNote(stamped.noteId)
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        reminders.setRuleEnabled(ruleId, enabled)
        reminders.getRule(ruleId)?.let { scheduler.syncNote(it.noteId) }
    }

    /** 基线 §16：删除 = 进入回收站 + 取消全部未来通知。 */
    fun moveToTrash(noteId: String) {
        scheduler.cancelNote(noteId)
        notes.moveToTrash(noteId)
    }

    fun restoreFromTrash(noteId: String) {
        notes.restore(noteId)
        scheduler.syncNote(noteId)
    }

    /** 永久删除：连带规则、未来通知、附件与历史记录。 */
    fun purge(noteId: String) {
        reminders.pendingForNote(noteId).forEach { scheduler.cancel(it) }
        val paths = notes.purge(noteId)
        paths.forEach { path -> runCatching { java.io.File(path).delete() } }
    }

    fun purgeAllTrash() {
        notes.query(NoteQuery(status = NoteStatus.TRASHED)).map { it.id }.forEach { purge(it) }
    }

    fun addAttachment(noteId: String, type: AttachmentType, path: String, name: String, mime: String, size: Long) {
        attachments.add(noteId, type, path, name, mime, size)
        notes.touch(noteId)
    }

    fun removeAttachment(attachmentId: String) {
        attachments.get(attachmentId)?.let {
            runCatching { java.io.File(it.localPath).delete() }
            attachments.delete(attachmentId)
        }
    }

    // endregion

    // region reminder actions

    fun complete(noteId: String) = coordinator.completeNote(noteId)
    fun snooze(noteId: String, minutes: Int) = coordinator.snoozeNote(noteId, minutes)
    fun skipCurrent(noteId: String) = coordinator.skipCurrent(noteId)
    fun completeOccurrence(occurrenceId: String) = coordinator.complete(occurrenceId)
    fun snoozeOccurrence(occurrenceId: String, minutes: Int) = coordinator.snooze(occurrenceId, minutes)
    fun skipOccurrence(occurrenceId: String) = coordinator.skip(occurrenceId)

    /**
     * 全量重建。顺序是硬约束（基线 §24）：先补发错过的事件，再重排调度——
     * [ReminderScheduler.syncAll] 的过期判定会把计划时间已过去的事件归档，
     * 补发必须抢在它前面把那一次的 next_alarm_at 挪到将来。
     */
    fun resyncAll() {
        coordinator.recoverMissed()
        scheduler.syncAll()
    }

    /**
     * 提醒自检：造一条 [delaySeconds] 秒后到点的单次提醒。
     *
     * 刻意复用 [saveNote] —— 也就是用户设置真实提醒时走的同一条路径（写库 → syncNote → arm），
     * 这样"测试提醒没收到"就等价于"真实提醒会没收",而不是另一套只测通知的假绿灯。
     * 返回计划触达的时刻，供 UI 显示倒计时；null 表示连一个分类都没有。
     *
     * 这条备忘录留在列表里由用户自行删除，不做自动清理：多一套生命周期就多一处和真实数据不一致的地方。
     */
    fun scheduleSelfTest(delaySeconds: Long = 35): Long? {
        val zone = ZoneId.systemDefault()
        val folderId = settings.defaultFolderId ?: folders.list().firstOrNull()?.id ?: return null
        val startAt = LocalDateTime.now(zone).truncatedTo(ChronoUnit.SECONDS).plusSeconds(delaySeconds)
        val plannedAt = startAt.atZone(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val rule = ReminderRule(
            id = UUID.randomUUID().toString(),
            noteId = "",
            type = RecurrenceType.ONCE,
            startLocal = startAt,
            timezone = zone.id,
            intervalValue = 1,
            intervalUnit = IntervalUnit.DAY,
            weekdays = emptySet(),
            dayOfMonth = null,
            monthOfYear = null,
            endDate = null,
            completionMode = CompletionMode.CONTINUE,
            autoRepeatEnabled = false,
            autoRepeatIntervalMinutes = 10,
            autoRepeatLimit = 3,
            isEnabled = true,
            createdAt = now,
            updatedAt = now,
        )
        val note = saveNote(
            NoteDraft(
                title = "提醒自检",
                body = "系统闹钟应在 ${compactDateTimeText(startAt)} 到点时弹出一条通知。收到后可以直接删除本条备忘录；" +
                    "没收到请到「通知与精确闹钟 → 提醒自检」查看失败记录，并确认是否已允许自启动。",
                folderId = folderId,
                priority = Priority.LOW,
                completionEnabled = true,
                notificationPreviewEnabled = true,
            ),
            listOf(rule),
        )
        val queued = reminders.pendingForNote(note.id).firstOrNull()
        reminders.recordHealth(
            HealthKind.SELF_TEST.storage,
            reason = if (queued == null) "已保存但未生成任何事件：通知总开关可能已关闭，或系统时间异常"
            else "已排定 ${compactDateTimeText(startAt)}",
            ruleId = rule.id,
            occurrenceId = queued?.id,
            scheduledAt = plannedAt,
        )
        return plannedAt
    }

    /** 自检读数：库里计划的下一次 vs 系统侧真正挂着的下一次，两者不一致就是没注册上。 */
    fun schedulerStatus(): SchedulerStatus = scheduler.selfCheck()

    /** 最近一条指定类别的诊断记录，供「发送测试提醒」还原上一轮的结论。 */
    fun latestHealth(kind: String): HealthEvent? =
        reminders.recentHealth(HEALTH_FEED_SIZE).firstOrNull { it.kind == kind }

    /**
     * 用户回填自检结果。App 无法观测"通知被厂商静默丢弃"——广播没到、什么异常都不抛，
     * 所以"划掉后台就不提醒"这件事只有用户自己知道。这一条记录是把它变成证据的唯一机会。
     */
    fun confirmSelfTest(received: Boolean) {
        val planned = latestHealth(HealthKind.SELF_TEST.storage)
        reminders.recordHealth(
            if (received) HealthKind.RECEIPT_OK.storage else HealthKind.RECEIPT_MISSING.storage,
            reason = if (received) "用户确认熄屏/划掉后台后仍按时收到"
            else "用户确认没收到：闹钟注册正常，说明广播被系统或厂商拦截",
            ruleId = planned?.ruleId,
            occurrenceId = planned?.occurrenceId,
            scheduledAt = planned?.scheduledAt,
        )
    }

    // endregion

    private fun cardFor(note: Note, pinned: ReminderOccurrence? = null): NoteCard {
        val pending = pinned?.takeIf { it.pending } ?: reminders.pendingForNote(note.id).firstOrNull()
        return NoteCard(
            note = note,
            folder = folders.get(note.folderId),
            nextOccurrence = pending,
            rule = pending?.let { reminders.getRule(it.ruleId) },
            attachmentCount = attachments.countOfNote(note.id),
        )
    }

    companion object {
        /** 回填自检结论时向上追溯的诊断条数：自检提醒到点即归档，窗口太窄会找不到那一轮的计划记录。 */
        private const val HEALTH_FEED_SIZE = 40

        fun describeRule(rule: ReminderRule): String = RecurrenceEngine.describe(rule)

        fun hasFutureOccurrence(rule: ReminderRule): Boolean =
            RecurrenceEngine.nextOccurrence(rule, Instant.now()) != null
    }
}
