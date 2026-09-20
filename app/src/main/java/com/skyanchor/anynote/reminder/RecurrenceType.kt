package com.skyanchor.anynote.reminder

/** 基线 §5.3 支持的全部重复类型 */
enum class RecurrenceType(val storage: String, val label: String) {
    ONCE("once", "单次提醒"),
    DAILY("daily", "每天"),
    WEEKLY("weekly", "每周"),
    MONTHLY("monthly", "每月"),
    YEARLY("yearly", "每年"),
    WEEKDAYS("weekdays", "工作日"),
    CUSTOM_WEEKDAYS("custom_weekdays", "自定义星期"),
    INTERVAL("interval", "固定间隔");

    val recurring: Boolean get() = this != ONCE

    companion object {
        fun from(value: String?): RecurrenceType =
            entries.firstOrNull { it.storage == value } ?: ONCE
    }
}

enum class IntervalUnit(val storage: String, val label: String) {
    DAY("day", "天"),
    WEEK("week", "周"),
    MONTH("month", "月"),
    YEAR("year", "年");

    companion object {
        fun from(value: String?): IntervalUnit =
            entries.firstOrNull { it.storage == value } ?: DAY
    }
}
