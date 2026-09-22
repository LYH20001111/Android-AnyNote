package com.skyanchor.anynote.data.dao

import android.content.ContentValues
import android.database.Cursor
import com.skyanchor.anynote.data.db.AnyNoteDatabase
import com.skyanchor.anynote.data.db.Schema
import com.skyanchor.anynote.data.db.boolean
import com.skyanchor.anynote.data.db.intOrNull
import com.skyanchor.anynote.data.db.longOrNull
import com.skyanchor.anynote.data.db.stringOrNull
import com.skyanchor.anynote.data.entity.CompletionMode
import com.skyanchor.anynote.data.entity.HealthEvent
import com.skyanchor.anynote.data.entity.OccurrenceStatus
import com.skyanchor.anynote.data.entity.ReminderOccurrence
import com.skyanchor.anynote.data.entity.ReminderRule
import com.skyanchor.anynote.reminder.IntervalUnit
import com.skyanchor.anynote.reminder.RecurrenceType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ReminderDao(private val db: AnyNoteDatabase) {

    // region rules

    fun insertRule(rule: ReminderRule) {
        db.writableDatabase.insert(Schema.RULES, null, rule.toValues())
    }

    fun updateRule(rule: ReminderRule) {
        db.writableDatabase.update(Schema.RULES, rule.toValues(), "id = ?", arrayOf(rule.id))
    }

    fun getRule(id: String): ReminderRule? = db.readableDatabase.rawQuery(
        "SELECT * FROM reminder_rules WHERE id = ?", arrayOf(id)
    ).use { if (it.moveToFirst()) it.toRule() else null }

    fun rulesOfNote(noteId: String): List<ReminderRule> = db.readableDatabase.rawQuery(
        "SELECT * FROM reminder_rules WHERE note_id = ? ORDER BY start_local ASC", arrayOf(noteId)
    ).use { c -> buildList { while (c.moveToNext()) add(c.toRule()) } }

    /** 所有启用中的规则，且所属备忘录仍在活动列表（回收站中的备忘录不再产生提醒，基线 §16）。 */
    fun activeRules(): List<ReminderRule> = db.readableDatabase.rawQuery(
        """
        SELECT r.* FROM reminder_rules r
        JOIN notes n ON n.id = r.note_id
        WHERE r.is_enabled = 1 AND n.status = 'active'
        ORDER BY r.start_local ASC
        """.trimIndent(), null
    ).use { c -> buildList { while (c.moveToNext()) add(c.toRule()) } }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        db.writableDatabase.update(
            Schema.RULES,
            ContentValues().apply {
                put("is_enabled", if (enabled) 1 else 0)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?", arrayOf(ruleId)
        )
    }

    fun deleteRule(ruleId: String) {
        db.writableDatabase.apply {
            beginTransaction()
            try {
                execSQL(
                    "DELETE FROM reminder_occurrences WHERE rule_id = ? AND status IN ('scheduled','snoozed')",
                    arrayOf(ruleId)
                )
                execSQL("DELETE FROM reminder_rules WHERE id = ?", arrayOf(ruleId))
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    // endregion

    // region occurrences

    fun insertOccurrence(occurrence: ReminderOccurrence) {
        db.writableDatabase.insert(Schema.OCCURRENCES, null, occurrence.toValues())
    }

    /** 手动完成"已耗尽"备忘录时补记的一次已完成事件：历史与日历都要留痕。 */
    fun recordManualCompletion(ruleId: String, noteId: String, scheduledAt: Long) {
        val now = System.currentTimeMillis()
        insertOccurrence(
            ReminderOccurrence(
                id = db.newId(),
                ruleId = ruleId,
                noteId = noteId,
                scheduledAt = scheduledAt,
                nextAlarmAt = scheduledAt,
                triggeredAt = null,
                completedAt = now,
                status = OccurrenceStatus.COMPLETED,
                snoozedUntil = null,
                skippedAt = null,
                overdueAt = null,
                notificationId = db.nextNotificationSeq(),
                autoRepeatCount = 0,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    fun getOccurrence(id: String): ReminderOccurrence? = db.readableDatabase.rawQuery(
        "SELECT * FROM reminder_occurrences WHERE id = ?", arrayOf(id)
    ).use { if (it.moveToFirst()) it.toOccurrence() else null }

    fun occurrenceByNotification(notificationId: Int): ReminderOccurrence? = db.readableDatabase.rawQuery(
        "SELECT * FROM reminder_occurrences WHERE notification_id = ? LIMIT 1", arrayOf(notificationId.toString())
    ).use { if (it.moveToFirst()) it.toOccurrence() else null }

    fun occurrencesOfNote(noteId: String): List<ReminderOccurrence> = db.readableDatabase.rawQuery(
        "SELECT * FROM reminder_occurrences WHERE note_id = ? ORDER BY scheduled_at ASC", arrayOf(noteId)
    ).use { c -> buildList { while (c.moveToNext()) add(c.toOccurrence()) } }

    /** 当前待处理（未结束）的事件。 */
    fun pendingForNote(noteId: String): List<ReminderOccurrence> = db.readableDatabase.rawQuery(
        """
        SELECT * FROM reminder_occurrences WHERE note_id = ?
          AND status IN ('scheduled','triggered','snoozed')
        ORDER BY COALESCE(snoozed_until, scheduled_at) ASC
        """.trimIndent(), arrayOf(noteId)
    ).use { c -> buildList { while (c.moveToNext()) add(c.toOccurrence()) } }

    fun pendingAll(): List<ReminderOccurrence> = db.readableDatabase.rawQuery(
        """
        SELECT * FROM reminder_occurrences
        WHERE status IN ('scheduled','triggered','snoozed')
        ORDER BY COALESCE(snoozed_until, scheduled_at) ASC
        """.trimIndent(), null
    ).use { c -> buildList { while (c.moveToNext()) add(c.toOccurrence()) } }

    /** 角标计数用：回收站中或已完成的备忘录不计入待处理数量。 */
    fun pendingActive(): List<ReminderOccurrence> = db.readableDatabase.rawQuery(
        """
        SELECT o.* FROM reminder_occurrences o JOIN notes n ON n.id = o.note_id
        WHERE o.status IN ('scheduled','triggered','snoozed') AND n.status = 'active'
        ORDER BY COALESCE(o.snoozed_until, o.scheduled_at) ASC
        """.trimIndent(), null
    ).use { c -> buildList { while (c.moveToNext()) add(c.toOccurrence()) } }

    fun pendingAllForRule(ruleId: String): List<ReminderOccurrence> = db.readableDatabase.rawQuery(
        """
        SELECT * FROM reminder_occurrences
        WHERE rule_id = ? AND status IN ('scheduled','triggered','snoozed')
        ORDER BY scheduled_at ASC
        """.trimIndent(), arrayOf(ruleId)
    ).use { c -> buildList { while (c.moveToNext()) add(c.toOccurrence()) } }

    fun update(occurrence: ReminderOccurrence) {
        db.writableDatabase.update(Schema.OCCURRENCES, occurrence.toValues(), "id = ?", arrayOf(occurrence.id))
    }

    /** resync 时清掉尚未触发的未来事件，由引擎重新物化。 */
    fun deleteUnfiredAfter(ruleId: String, from: Long): List<ReminderOccurrence> {
        val doomed = db.readableDatabase.rawQuery(
            "SELECT * FROM reminder_occurrences WHERE rule_id = ? AND status = 'scheduled' AND scheduled_at > ?",
            arrayOf(ruleId, from.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.toOccurrence()) } }
        db.writableDatabase.execSQL(
            "DELETE FROM reminder_occurrences WHERE rule_id = ? AND status = 'scheduled' AND scheduled_at > ?",
            arrayOf(ruleId, from.toString())
        )
        return doomed
    }

    fun deleteUnfiredForNote(noteId: String): List<ReminderOccurrence> {
        val doomed = db.readableDatabase.rawQuery(
            "SELECT * FROM reminder_occurrences WHERE note_id = ? AND status IN ('scheduled','snoozed')",
            arrayOf(noteId)
        ).use { c -> buildList { while (c.moveToNext()) add(c.toOccurrence()) } }
        db.writableDatabase.execSQL(
            "DELETE FROM reminder_occurrences WHERE note_id = ? AND status IN ('scheduled','snoozed')",
            arrayOf(noteId)
        )
        return doomed
    }

    /**
     * 该规则"未来才会触发"的事件。刻意排除 overdue_at 非空的行：
     * 那一次已经错过并排队补发，重排规则不能把它抹掉（基线 §24）。
     */
    fun scheduledForRule(ruleId: String): List<ReminderOccurrence> = db.readableDatabase.rawQuery(
        """
        SELECT * FROM reminder_occurrences
        WHERE rule_id = ? AND status = 'scheduled' AND overdue_at IS NULL ORDER BY scheduled_at ASC
        """.trimIndent(), arrayOf(ruleId)
    ).use { c -> buildList { while (c.moveToNext()) add(c.toOccurrence()) } }

    /** 已排队补发的事件，结束系列/删除规则时必须连带取消。 */
    fun overdueForRule(ruleId: String): List<ReminderOccurrence> = db.readableDatabase.rawQuery(
        "SELECT * FROM reminder_occurrences WHERE rule_id = ? AND status = 'scheduled' AND overdue_at IS NOT NULL",
        arrayOf(ruleId)
    ).use { c -> buildList { while (c.moveToNext()) add(c.toOccurrence()) } }

    /**
     * 注册时刻已过、却仍未被系统兑现的事件：一次闹钟被厂商后台管控或系统清理吞掉的直接迹象。
     * 含已排队补发的那一次——补发闹钟同样会被清掉，所以只看 next_alarm_at 是否也已经过去。
     */
    fun overdueForRules(now: Long): List<ReminderOccurrence> = db.readableDatabase.rawQuery(
        """
        SELECT o.* FROM reminder_occurrences o JOIN notes n ON n.id = o.note_id
        WHERE o.status = 'scheduled' AND o.next_alarm_at <= ? AND n.status = 'active'
        ORDER BY o.next_alarm_at ASC LIMIT 5
        """.trimIndent(), arrayOf(now.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.toOccurrence()) } }

    fun deleteScheduledForRule(ruleId: String) {
        db.writableDatabase.delete(
            Schema.OCCURRENCES,
            "rule_id = ? AND status = 'scheduled' AND overdue_at IS NULL",
            arrayOf(ruleId),
        )
    }

    fun deleteOccurrence(occurrenceId: String) {
        db.writableDatabase.delete(Schema.OCCURRENCES, "id = ?", arrayOf(occurrenceId))
    }

    /** 当前仍占用系统闹钟的事件，全量重排前需要逐个取消。 */
    fun armedOccurrences(): List<ReminderOccurrence> = db.readableDatabase.rawQuery(
        "SELECT * FROM reminder_occurrences WHERE status IN ('scheduled','snoozed')", null
    ).use { c -> buildList { while (c.moveToNext()) add(c.toOccurrence()) } }

    /**
     * 该规则已在表中占用、且还会与未来重合物化的触发时刻集合。
     * [ReminderScheduler.syncRule] 靠它去重：被提前完成/跳过/推迟的未来时点不能被重排"复活"，
     * 否则历史记录无限累积、待处理提醒也永远清不掉。
     * 排除 scheduled（重排前本来就会被删）与 cancelled（事件只是随回收站挂起，恢复后必须能再物化）。
     */
    fun occupiedTimesForRule(ruleId: String): Set<Long> = db.readableDatabase.rawQuery(
        """
        SELECT scheduled_at FROM reminder_occurrences
        WHERE rule_id = ? AND status IN ('triggered','snoozed','completed','skipped','expired')
        """.trimIndent(), arrayOf(ruleId)
    ).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }

    fun occurrenceAt(ruleId: String, scheduledAt: Long): ReminderOccurrence? = db.readableDatabase.rawQuery(
        "SELECT * FROM reminder_occurrences WHERE rule_id = ? AND scheduled_at = ? LIMIT 1",
        arrayOf(ruleId, scheduledAt.toString())
    ).use { if (it.moveToFirst()) it.toOccurrence() else null }

    /** 与 [expireUnfiredBefore] 同一批候选行：归档前调用方要用它撤销残留通知、对账在栏状态。 */
    fun unfiredExpiredCandidates(cutoff: Long): List<ReminderOccurrence> = db.readableDatabase.rawQuery(
        "SELECT * FROM reminder_occurrences WHERE status = 'scheduled' AND scheduled_at < ? AND next_alarm_at < ?",
        arrayOf(cutoff.toString(), cutoff.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.toOccurrence()) } }

    /**
     * 计划时间已过却从未触发的事件，标记为已过期，返回被归档的条数。
     * cutoff 由调用方给出并需要留出宽限期：进程被闹钟本身拉起时，全量重建会先于广播执行，
     * 零宽限就会正在送达的这一次被判成过期。
     * 保护条件挂在 next_alarm_at 而不是 scheduled_at 上：逾期补发会把那一次的 next_alarm_at
     * 挪到将来，只要系统侧还挂着注册就不该归档；补发闹钟也被吞掉时才会真正过期。
     */
    fun expireUnfiredBefore(cutoff: Long): Int = db.writableDatabase.update(
        Schema.OCCURRENCES,
        ContentValues().apply {
            put("status", OccurrenceStatus.EXPIRED.storage)
            put("updated_at", System.currentTimeMillis())
        },
        "status = 'scheduled' AND scheduled_at < ? AND next_alarm_at < ?",
        arrayOf(cutoff.toString(), cutoff.toString()),
    )

    /**
     * 错过但还值得补发的事件：计划时间已经过去 [redetectMs] 以上（给正常投递留出送达时间），
     * 但仍在补发时效内，且从未被补发过。按最早错过排序，由调用方限量取用。
     */
    fun missedCandidates(now: Long, redetectMs: Long, notBefore: Long, limit: Int): List<ReminderOccurrence> =
        db.readableDatabase.rawQuery(
            """
            SELECT o.* FROM reminder_occurrences o JOIN notes n ON n.id = o.note_id
            WHERE o.status = 'scheduled' AND o.overdue_at IS NULL
              AND o.scheduled_at < ? AND o.scheduled_at >= ? AND n.status = 'active'
            ORDER BY o.scheduled_at ASC LIMIT ?
            """.trimIndent(),
            arrayOf((now - redetectMs).toString(), notBefore.toString(), limit.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.toOccurrence()) } }

    /** 全局最早的一条待触达事件——只有它值得占用 setAlarmClock 这一个"用户可见闹钟"名额。 */
    fun nextPendingOverall(): ReminderOccurrence? = db.readableDatabase.rawQuery(
        """
        SELECT o.* FROM reminder_occurrences o JOIN notes n ON n.id = o.note_id
        WHERE o.status IN ('scheduled','snoozed') AND o.next_alarm_at > ? AND n.status = 'active'
        ORDER BY o.next_alarm_at ASC LIMIT 1
        """.trimIndent(), arrayOf(System.currentTimeMillis().toString())
    ).use { if (it.moveToFirst()) it.toOccurrence() else null }

    fun recordHealth(
        kind: String,
        reason: String,
        ruleId: String? = null,
        occurrenceId: String? = null,
        scheduledAt: Long? = null,
        detail: String? = null,
    ) {
        runCatching {
            db.writableDatabase.insert(
                Schema.HEALTH,
                null,
                ContentValues().apply {
                    put("kind", kind)
                    put("reason", reason)
                    if (ruleId == null) putNull("rule_id") else put("rule_id", ruleId)
                    if (occurrenceId == null) putNull("occurrence_id") else put("occurrence_id", occurrenceId)
                    if (scheduledAt == null) putNull("scheduled_at") else put("scheduled_at", scheduledAt)
                    if (detail == null) putNull("detail") else put("detail", detail)
                    put("recorded_at", System.currentTimeMillis())
                }
            )
        }
    }

    fun recentHealth(limit: Int): List<HealthEvent> = db.readableDatabase.rawQuery(
        "SELECT * FROM scheduler_health ORDER BY recorded_at DESC, id DESC LIMIT ?",
        arrayOf(limit.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.toHealthEvent()) } }

    /** 诊断表只留最近 [keepLast] 条，避免长期累积。 */
    fun pruneHealth(keepLast: Int) {
        runCatching {
            db.writableDatabase.execSQL(
                "DELETE FROM scheduler_health WHERE id NOT IN " +
                    "(SELECT id FROM scheduler_health ORDER BY recorded_at DESC, id DESC LIMIT ?)",
                arrayOf<Any>(keepLast)
            )
        }
    }

    /**
     * 日历页：某时间区间内的全部事件（含已完成，用于打点）。
     *
     * 区间比较必须拆到裸列上：`COALESCE(a, b) >= ?` 左边是表达式、右边是绑定参数，
     * 两者都没有 affinity，SQLite 会按存储类比较——INTEGER 恒小于 TEXT，条件永假，
     * 日历因此永远查不到任何事件。裸列才能把列的数值 affinity 作用到参数上。
     */
    fun occurrencesBetween(from: Long, to: Long): List<ReminderOccurrence> = db.readableDatabase.rawQuery(
        """
        SELECT * FROM reminder_occurrences
        WHERE status != 'cancelled'
          AND ((snoozed_until IS NULL AND scheduled_at >= ? AND scheduled_at < ?)
            OR (snoozed_until IS NOT NULL AND snoozed_until >= ? AND snoozed_until < ?))
        ORDER BY COALESCE(snoozed_until, scheduled_at) ASC
        """.trimIndent(),
        arrayOf(from.toString(), to.toString(), from.toString(), to.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.toOccurrence()) } }

    /** 历史页查询（基线 §14）。 */
    fun history(
        statuses: List<OccurrenceStatus>,
        text: String?,
        folderId: String?,
        from: Long?,
        to: Long?,
    ): List<ReminderOccurrence> {
        val where = StringBuilder("o.status IN (${statuses.joinToString(",") { "?" }})")
        val args = ArrayList<String>().apply { statuses.forEach { add(it.storage) } }
        text?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where.append(" AND (n.title LIKE ? OR n.body LIKE ?)")
            args += "%$it%"
            args += "%$it%"
        }
        folderId?.let { where.append(" AND n.folder_id = ?"); args += it }
        // 区间比较拆到裸列上，原因同 [occurrencesBetween]：表达式的 affinity 会丢，整数只按存储类比。
        if (from != null || to != null) {
            val lo = (from ?: Long.MIN_VALUE).toString()
            val hi = (to ?: Long.MAX_VALUE).toString()
            where.append(
                " AND ((o.completed_at IS NULL AND o.scheduled_at >= ? AND o.scheduled_at < ?)" +
                    " OR (o.completed_at IS NOT NULL AND o.completed_at >= ? AND o.completed_at < ?))"
            )
            args += lo
            args += hi
            args += lo
            args += hi
        }
        return db.readableDatabase.rawQuery(
            """
            SELECT o.* FROM reminder_occurrences o JOIN notes n ON n.id = o.note_id
            WHERE $where ORDER BY COALESCE(o.completed_at, o.scheduled_at) DESC LIMIT 500
            """.trimIndent(), args.toTypedArray()
        ).use { c -> buildList { while (c.moveToNext()) add(c.toOccurrence()) } }
    }

    // endregion
}

private val ISO_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

internal fun Cursor.toRule() = ReminderRule(
    id = getString(getColumnIndexOrThrow("id")),
    noteId = getString(getColumnIndexOrThrow("note_id")),
    type = RecurrenceType.from(stringOrNull("type")),
    startLocal = LocalDateTime.parse(getString(getColumnIndexOrThrow("start_local")), ISO_DATE_TIME),
    timezone = getString(getColumnIndexOrThrow("timezone")),
    intervalValue = getInt(getColumnIndexOrThrow("interval_value")),
    intervalUnit = IntervalUnit.from(stringOrNull("interval_unit")),
    weekdays = (stringOrNull("weekdays") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet(),
    dayOfMonth = intOrNull("day_of_month"),
    monthOfYear = intOrNull("month_of_year"),
    endDate = stringOrNull("end_date")?.let { LocalDate.parse(it, ISO_DATE) },
    completionMode = CompletionMode.from(stringOrNull("completion_mode")),
    autoRepeatEnabled = boolean("auto_repeat_enabled"),
    autoRepeatIntervalMinutes = getInt(getColumnIndexOrThrow("auto_repeat_interval")),
    autoRepeatLimit = getInt(getColumnIndexOrThrow("auto_repeat_limit")),
    isEnabled = boolean("is_enabled"),
    createdAt = getLong(getColumnIndexOrThrow("created_at")),
    updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
)

internal fun ReminderRule.toValues() = ContentValues().apply {
    put("id", id)
    put("note_id", noteId)
    put("type", type.storage)
    put("start_local", startLocal.format(ISO_DATE_TIME))
    put("timezone", timezone)
    put("interval_value", intervalValue)
    put("interval_unit", intervalUnit.storage)
    put("weekdays", weekdays.joinToString(","))
    if (dayOfMonth == null) putNull("day_of_month") else put("day_of_month", dayOfMonth)
    if (monthOfYear == null) putNull("month_of_year") else put("month_of_year", monthOfYear)
    if (endDate == null) putNull("end_date") else put("end_date", endDate.format(ISO_DATE))
    put("completion_mode", completionMode.storage)
    put("auto_repeat_enabled", if (autoRepeatEnabled) 1 else 0)
    put("auto_repeat_interval", autoRepeatIntervalMinutes)
    put("auto_repeat_limit", autoRepeatLimit)
    put("is_enabled", if (isEnabled) 1 else 0)
    put("created_at", createdAt)
    put("updated_at", updatedAt)
}

internal fun Cursor.toOccurrence() = ReminderOccurrence(
    id = getString(getColumnIndexOrThrow("id")),
    ruleId = getString(getColumnIndexOrThrow("rule_id")),
    noteId = getString(getColumnIndexOrThrow("note_id")),
    scheduledAt = getLong(getColumnIndexOrThrow("scheduled_at")),
    nextAlarmAt = getLong(getColumnIndexOrThrow("next_alarm_at")),
    triggeredAt = longOrNull("triggered_at"),
    completedAt = longOrNull("completed_at"),
    status = OccurrenceStatus.from(stringOrNull("status")),
    snoozedUntil = longOrNull("snoozed_until"),
    skippedAt = longOrNull("skipped_at"),
    overdueAt = longOrNull("overdue_at"),
    notificationId = getInt(getColumnIndexOrThrow("notification_id")),
    autoRepeatCount = getInt(getColumnIndexOrThrow("auto_repeat_count")),
    createdAt = getLong(getColumnIndexOrThrow("created_at")),
    updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
)

internal fun ReminderOccurrence.toValues() = ContentValues().apply {
    put("id", id)
    put("rule_id", ruleId)
    put("note_id", noteId)
    put("scheduled_at", scheduledAt)
    put("next_alarm_at", nextAlarmAt)
    if (triggeredAt == null) putNull("triggered_at") else put("triggered_at", triggeredAt)
    if (completedAt == null) putNull("completed_at") else put("completed_at", completedAt)
    put("status", status.storage)
    if (snoozedUntil == null) putNull("snoozed_until") else put("snoozed_until", snoozedUntil)
    if (skippedAt == null) putNull("skipped_at") else put("skipped_at", skippedAt)
    if (overdueAt == null) putNull("overdue_at") else put("overdue_at", overdueAt)
    put("notification_id", notificationId)
    put("auto_repeat_count", autoRepeatCount)
    put("created_at", createdAt)
    put("updated_at", updatedAt)
}

internal fun Cursor.toHealthEvent() = HealthEvent(
    kind = getString(getColumnIndexOrThrow("kind")),
    ruleId = stringOrNull("rule_id"),
    occurrenceId = stringOrNull("occurrence_id"),
    scheduledAt = longOrNull("scheduled_at"),
    reason = getString(getColumnIndexOrThrow("reason")),
    detail = stringOrNull("detail"),
    recordedAt = getLong(getColumnIndexOrThrow("recorded_at")),
)
