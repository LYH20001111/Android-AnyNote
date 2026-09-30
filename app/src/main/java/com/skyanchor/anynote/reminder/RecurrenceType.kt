package com.skyanchor.anynote.reminder

import com.skyanchor.anynote.R

/** 基线 §5.3 支持的全部重复类型 */
enum class RecurrenceType(val storage: String, val label: String, val labelRes: Int) {
    ONCE("once", "单次提醒", R.string.type_once),
    DAILY("daily", "每天", R.string.type_daily),
    WEEKLY("weekly", "每周", R.string.type_weekly),
    MONTHLY("monthly", "每月", R.string.type_monthly),
    YEARLY("yearly", "每年", R.string.type_yearly),
    WEEKDAYS("weekdays", "工作日", R.string.type_weekdays),
    CUSTOM_WEEKDAYS("custom_weekdays", "自定义星期", R.string.type_custom_weekdays),
    INTERVAL("interval", "固定间隔", R.string.type_interval);

    val recurring: Boolean get() = this != ONCE

    companion object {
        fun from(value: String?): RecurrenceType =
            entries.firstOrNull { it.storage == value } ?: ONCE
    }
}

enum class IntervalUnit(val storage: String, val label: String, val labelRes: Int) {
    DAY("day", "天", R.string.interval_unit_day),
    WEEK("week", "周", R.string.interval_unit_week),
    MONTH("month", "月", R.string.interval_unit_month),
    YEAR("year", "年", R.string.interval_unit_year);

    companion object {
        fun from(value: String?): IntervalUnit =
            entries.firstOrNull { it.storage == value } ?: DAY
    }
}
