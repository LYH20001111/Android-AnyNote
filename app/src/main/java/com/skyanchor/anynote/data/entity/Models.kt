package com.skyanchor.anynote.data.entity

import com.skyanchor.anynote.reminder.IntervalUnit
import com.skyanchor.anynote.reminder.RecurrenceType
import java.time.LocalDate
import java.time.LocalDateTime

enum class Priority(val storage: String, val label: String) {
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高");

    companion object {
        fun from(value: String?): Priority = entries.firstOrNull { it.storage == value } ?: MEDIUM
    }
}

enum class NoteStatus(val storage: String) {
    ACTIVE("active"),
    COMPLETED("completed"),
    TRASHED("trashed");

    companion object {
        fun from(value: String?): NoteStatus = entries.firstOrNull { it.storage == value } ?: ACTIVE
    }
}

enum class AttachmentType(val storage: String, val label: String) {
    IMAGE("image", "图片"),
    FILE("file", "文件"),
    AUDIO("audio", "语音");

    companion object {
        fun from(value: String?): AttachmentType = entries.firstOrNull { it.storage == value } ?: FILE
    }
}

enum class OccurrenceStatus(val storage: String) {
    SCHEDULED("scheduled"),
    TRIGGERED("triggered"),
    SNOOZED("snoozed"),
    COMPLETED("completed"),
    SKIPPED("skipped"),
    EXPIRED("expired"),
    CANCELLED("cancelled");

    companion object {
        fun from(value: String?): OccurrenceStatus =
            entries.firstOrNull { it.storage == value } ?: SCHEDULED
    }
}

/** 完成一次 vs 结束整个重复系列（基线 §7.2 / §38-5） */
enum class CompletionMode(val storage: String, val label: String) {
    CONTINUE("continue", "完成本次，后续继续提醒"),
    END_SERIES("end_series", "完成后结束整个重复提醒");

    companion object {
        fun from(value: String?): CompletionMode =
            entries.firstOrNull { it.storage == value } ?: CONTINUE
    }
}

data class Folder(
    val id: String,
    val name: String,
    val colorKey: String,
    val iconKey: String,
    val isBuiltIn: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Note(
    val id: String,
    val folderId: String,
    val title: String?,
    val body: String,
    val priority: Priority,
    val status: NoteStatus,
    val notificationPreviewEnabled: Boolean,
    val completionEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val deletedAt: Long?,
)

data class Attachment(
    val id: String,
    val noteId: String,
    val type: AttachmentType,
    val localPath: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,
)

/**
 * 提醒规则。时间以"墙钟语义"保存（基线 §25）：
 * [startLocal] 是用户看到的本地日期时间，[timezone] 记录创建时的 IANA 时区，
 * 触发点由引擎按当前本地时区重新推算，而不是永久折算成 UTC。
 */
data class ReminderRule(
    val id: String,
    val noteId: String,
    val type: RecurrenceType,
    val startLocal: LocalDateTime,
    val timezone: String,
    val intervalValue: Int,
    val intervalUnit: IntervalUnit,
    val weekdays: Set<Int>,
    val dayOfMonth: Int?,
    val monthOfYear: Int?,
    val endDate: LocalDate?,
    val completionMode: CompletionMode,
    val autoRepeatEnabled: Boolean,
    val autoRepeatIntervalMinutes: Int,
    val autoRepeatLimit: Int,
    val isEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val once: Boolean get() = type == RecurrenceType.ONCE
}

data class ReminderOccurrence(
    val id: String,
    val ruleId: String,
    val noteId: String,
    val scheduledAt: Long,
    val nextAlarmAt: Long,
    val triggeredAt: Long?,
    val completedAt: Long?,
    val status: OccurrenceStatus,
    val snoozedUntil: Long?,
    val skippedAt: Long?,
    /** 已确认"这一次错过过"的时刻；null 表示从未逾期，补投链路靠它保证幂等。 */
    val overdueAt: Long?,
    val notificationId: Int,
    val autoRepeatCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** 用户视角下的"这条提醒什么时候到"：稍后提醒优先于原计划时间。 */
    val effectiveAt: Long get() = snoozedUntil ?: scheduledAt

    val overdue: Boolean get() = overdueAt != null

    val pending: Boolean
        get() = status == OccurrenceStatus.SCHEDULED ||
            status == OccurrenceStatus.TRIGGERED ||
            status == OccurrenceStatus.SNOOZED
}

/** 一条调度诊断记录：闹钟注册失败、投递受阻、逾期补发、看门狗运行（基线 §24）。 */
data class HealthEvent(
    val kind: String,
    val ruleId: String?,
    val occurrenceId: String?,
    val scheduledAt: Long?,
    val reason: String,
    val detail: String?,
    val recordedAt: Long,
) {
    val kindLabel: String get() = HealthKind.from(kind).label
}

/** scheduler_health.kind 的取值集合。label 直接给设置页展示，不再在 UI 层映射。 */
enum class HealthKind(val storage: String, val label: String) {
    ARM_FAILED("arm_failed", "闹钟注册失败"),
    ARM_DEGRADED("arm_degraded", "闹钟已降级"),
    DELIVERY_BLOCKED("delivery_blocked", "通知未能弹出"),
    MISSED_REDELIVERED("missed_redelivered", "逾期已补发"),
    MISSED_DROPPED("missed_dropped", "错过已过时效"),
    RECEIVER_INTERRUPTED("receiver_interrupted", "后台执行中断"),
    SELF_HEAL("self_heal", "调度自愈"),
    SELF_TEST("self_test", "自检提醒"),
    ALARM_CLEARED("alarm_cleared", "系统侧闹钟已消失"),
    RECEIPT_OK("receipt_ok", "自检已收到"),
    RECEIPT_MISSING("receipt_missing", "自检未收到"),
    OTHER("other", "其他调度事件");

    companion object {
        /** 兜底必须是中性的：把未知 kind 显示成"调度自愈"会把一次真实失败伪装成正常心跳。 */
        fun from(value: String?): HealthKind = entries.firstOrNull { it.storage == value } ?: OTHER
    }
}

/** 列表用投影：一条备忘录 + 它当前最紧迫的一条待处理提醒事件 */
data class NoteCard(
    val note: Note,
    val folder: Folder?,
    val nextOccurrence: ReminderOccurrence?,
    val rule: ReminderRule?,
    val attachmentCount: Int,
)
