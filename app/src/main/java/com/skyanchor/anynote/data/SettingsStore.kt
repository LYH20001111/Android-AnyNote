package com.skyanchor.anynote.data

import android.content.Context
import android.content.SharedPreferences
import com.skyanchor.anynote.data.entity.CompletionMode
import com.skyanchor.anynote.data.entity.Priority

/** 设置页的默认行为与通知偏好（基线 §20）。 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("anynote_settings", Context.MODE_PRIVATE)

    var welcomeSeen: Boolean
        get() = prefs.getBoolean(KEY_WELCOME, false)
        set(value) = prefs.edit().putBoolean(KEY_WELCOME, value).apply()

    /** 通知总开关：关闭后不再注册任何系统闹钟（基线 §20）。 */
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND, value).apply()

    var badgeEnabled: Boolean
        get() = prefs.getBoolean(KEY_BADGE, true)
        set(value) = prefs.edit().putBoolean(KEY_BADGE, value).apply()

    /** 新建备忘录时是否默认在通知中展示正文（基线 §10.3）。 */
    var defaultPreviewEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEFAULT_PREVIEW, true)
        set(value) = prefs.edit().putBoolean(KEY_DEFAULT_PREVIEW, value).apply()

    var defaultPriority: Priority
        get() = Priority.from(prefs.getString(KEY_DEFAULT_PRIORITY, Priority.MEDIUM.storage))
        set(value) = prefs.edit().putString(KEY_DEFAULT_PRIORITY, value.storage).apply()

    var defaultFolderId: String?
        get() = prefs.getString(KEY_DEFAULT_FOLDER, null)
        set(value) = prefs.edit().putString(KEY_DEFAULT_FOLDER, value).apply()

    var defaultCompletionEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEFAULT_COMPLETION, true)
        set(value) = prefs.edit().putBoolean(KEY_DEFAULT_COMPLETION, value).apply()

    var defaultCompletionMode: CompletionMode
        get() = CompletionMode.from(prefs.getString(KEY_DEFAULT_COMPLETION_MODE, CompletionMode.CONTINUE.storage))
        set(value) = prefs.edit().putString(KEY_DEFAULT_COMPLETION_MODE, value.storage).apply()

    var defaultSnoozeMinutes: Int
        get() = prefs.getInt(KEY_DEFAULT_SNOOZE, 10)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_SNOOZE, value).apply()

    /**
     * 最近一次提醒是否用 `setAlarmClock` 注册。它是唯一不受 Doze 合并、厂商省电冻结影响最弱的
     * API，代价是状态栏会出现一个闹钟图标，所以留给用户关掉的权利。
     */
    var preferAlarmClock: Boolean
        get() = prefs.getBoolean(KEY_PREFER_ALARM_CLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_PREFER_ALARM_CLOCK, value).apply()

    /** 错过多久之内仍然补发"逾期提醒"；超出即按 expired 归档，避免陈旧提醒轰炸。 */
    var missedGraceHours: Int
        get() = prefs.getInt(KEY_MISSED_GRACE, 24)
        set(value) = prefs.edit().putInt(KEY_MISSED_GRACE, value).apply()

    companion object {
        val SNOOZE_PRESETS = listOf(10, 30, 60)
        val GRACE_PRESETS = listOf(1, 6, 12, 24, 48)

        private const val KEY_WELCOME = "welcome_seen"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_BADGE = "badge_enabled"
        private const val KEY_DEFAULT_PREVIEW = "default_preview"
        private const val KEY_DEFAULT_PRIORITY = "default_priority"
        private const val KEY_DEFAULT_FOLDER = "default_folder"
        private const val KEY_DEFAULT_COMPLETION = "default_completion_enabled"
        private const val KEY_DEFAULT_COMPLETION_MODE = "default_completion_mode"
        private const val KEY_DEFAULT_SNOOZE = "default_snooze_minutes"
        private const val KEY_PREFER_ALARM_CLOCK = "prefer_alarm_clock"
        private const val KEY_MISSED_GRACE = "missed_grace_hours"
    }
}
