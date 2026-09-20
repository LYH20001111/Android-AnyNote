package com.skyanchor.anynote.data.db

/**
 * 数据库结构（基线 §3、§29）。
 * 所有主键为稳定 TEXT UUID，不用自增位置，为未来云同步预留。
 */
object Schema {
    const val DATABASE_NAME = "anynote.db"

    /** v2：`reminder_occurrences.overdue_at` + `scheduler_health` 调度诊断表（基线 §22、§24）。 */
    const val DATABASE_VERSION = 2

    const val FOLDERS = "folders"
    const val NOTES = "notes"
    const val ATTACHMENTS = "attachments"
    const val RULES = "reminder_rules"
    const val OCCURRENCES = "reminder_occurrences"
    const val META = "meta"
    const val HEALTH = "scheduler_health"

    val CREATE = arrayOf(
        """
        CREATE TABLE folders (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            color_key TEXT NOT NULL,
            icon_key TEXT NOT NULL,
            is_builtin INTEGER NOT NULL DEFAULT 0,
            sort_order INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent(),

        """
        CREATE TABLE notes (
            id TEXT PRIMARY KEY NOT NULL,
            folder_id TEXT NOT NULL,
            title TEXT,
            body TEXT NOT NULL DEFAULT '',
            priority TEXT NOT NULL DEFAULT 'medium',
            status TEXT NOT NULL DEFAULT 'active',
            notification_preview_enabled INTEGER NOT NULL DEFAULT 1,
            completion_enabled INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            completed_at INTEGER,
            deleted_at INTEGER
        )
        """.trimIndent(),

        """
        CREATE TABLE attachments (
            id TEXT PRIMARY KEY NOT NULL,
            note_id TEXT NOT NULL,
            type TEXT NOT NULL,
            local_path TEXT NOT NULL,
            file_name TEXT NOT NULL,
            mime_type TEXT NOT NULL,
            size INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL
        )
        """.trimIndent(),

        """
        CREATE TABLE reminder_rules (
            id TEXT PRIMARY KEY NOT NULL,
            note_id TEXT NOT NULL,
            type TEXT NOT NULL,
            start_local TEXT NOT NULL,
            timezone TEXT NOT NULL,
            interval_value INTEGER NOT NULL DEFAULT 1,
            interval_unit TEXT NOT NULL DEFAULT 'day',
            weekdays TEXT NOT NULL DEFAULT '',
            day_of_month INTEGER,
            month_of_year INTEGER,
            end_date TEXT,
            completion_mode TEXT NOT NULL DEFAULT 'continue',
            auto_repeat_enabled INTEGER NOT NULL DEFAULT 0,
            auto_repeat_interval INTEGER NOT NULL DEFAULT 10,
            auto_repeat_limit INTEGER NOT NULL DEFAULT 0,
            is_enabled INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent(),

        """
        CREATE TABLE reminder_occurrences (
            id TEXT PRIMARY KEY NOT NULL,
            rule_id TEXT NOT NULL,
            note_id TEXT NOT NULL,
            scheduled_at INTEGER NOT NULL,
            next_alarm_at INTEGER NOT NULL,
            triggered_at INTEGER,
            completed_at INTEGER,
            status TEXT NOT NULL DEFAULT 'scheduled',
            snoozed_until INTEGER,
            skipped_at INTEGER,
            overdue_at INTEGER,
            notification_id INTEGER NOT NULL DEFAULT 0,
            auto_repeat_count INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent(),

        """
        CREATE TABLE meta (
            key TEXT PRIMARY KEY NOT NULL,
            value TEXT NOT NULL
        )
        """.trimIndent(),

        HEALTH_TABLE,
        HEALTH_INDEX,

        "CREATE INDEX idx_notes_folder ON notes(folder_id)",
        "CREATE INDEX idx_notes_status ON notes(status)",
        "CREATE INDEX idx_attachments_note ON attachments(note_id)",
        "CREATE INDEX idx_rules_note ON reminder_rules(note_id)",
        "CREATE INDEX idx_occ_note ON reminder_occurrences(note_id)",
        "CREATE INDEX idx_occ_rule ON reminder_occurrences(rule_id)",
        "CREATE INDEX idx_occ_status_time ON reminder_occurrences(status, scheduled_at)",
        "CREATE INDEX idx_occ_next_alarm ON reminder_occurrences(next_alarm_at)",
        "CREATE INDEX idx_occ_overdue ON reminder_occurrences(status, overdue_at)",
    )

    val DROP = arrayOf(
        FOLDERS, NOTES, ATTACHMENTS, RULES, OCCURRENCES, META, HEALTH
    ).map { "DROP TABLE IF EXISTS $it" }

    /**
     * 调度诊断表。故意不加外键：规则重排会把未触发的事件行删掉，
     * 而"这次闹钟为什么没注册上"的证据必须活得比事件行更久（基线 §24）。
     * 写成字面量拼接而不是 `trimIndent()`：上面的 CREATE 要引用它，必须是编译期常量。
     */
    const val HEALTH_TABLE =
        "CREATE TABLE scheduler_health (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "kind TEXT NOT NULL, " +
            "rule_id TEXT, " +
            "occurrence_id TEXT, " +
            "scheduled_at INTEGER, " +
            "reason TEXT NOT NULL, " +
            "detail TEXT, " +
            "recorded_at INTEGER NOT NULL)"

    const val HEALTH_INDEX = "CREATE INDEX idx_health_time ON scheduler_health(recorded_at DESC)"
}
