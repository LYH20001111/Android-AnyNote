package com.skyanchor.anynote.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val MINUTE = DateTimeFormatter.ofPattern("HH:mm")
private val SECOND = DateTimeFormatter.ofPattern("HH:mm:ss")
private val MONTH_DAY = DateTimeFormatter.ofPattern("M月d日")
private val FULL_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日")
private val FULL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
private val COMPACT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

val WEEKDAY_CN = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

fun dayOfWeekCn(day: DayOfWeek): String = WEEKDAY_CN[day.value - 1]

fun toLocal(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

fun timeText(millis: Long): String {
    val dt = toLocal(millis)
    return if (dt.second == 0) dt.format(MINUTE) else dt.format(SECOND)
}

fun dateText(millis: Long): String = toLocal(millis).format(FULL_DATE)

fun dateTimeText(millis: Long): String = toLocal(millis).format(FULL_DATE_TIME)

fun compactDateTimeText(dt: LocalDateTime): String = dt.format(COMPACT)

/**
 * 列表里的时间标签：今天/明天用相对说法，跨年补年份。
 */
fun listTimeText(millis: Long): String {
    val dt = toLocal(millis)
    val today = LocalDate.now()
    val day = dt.toLocalDate()
    val prefix = when {
        day == today -> "今天"
        day == today.plusDays(1) -> "明天"
        day == today.minusDays(1) -> "昨天"
        day.year == today.year -> day.format(MONTH_DAY)
        else -> day.format(FULL_DATE)
    }
    return "$prefix ${dt.format(if (dt.second == 0) MINUTE else SECOND)}"
}

/** 首页分组（基线 §12）。 */
enum class TimeGroup(val label: String) {
    OVERDUE("已逾期"),
    TODAY("今天"),
    TOMORROW("明天"),
    LATER("更后面"),
    NO_REMINDER("无提醒"),
}

fun groupOf(millis: Long?): TimeGroup {
    if (millis == null) return TimeGroup.NO_REMINDER
    val day = toLocal(millis).toLocalDate()
    val today = LocalDate.now()
    return when {
        millis < System.currentTimeMillis() && day.isBefore(today) -> TimeGroup.OVERDUE
        day == today -> TimeGroup.TODAY
        day == today.plusDays(1) -> TimeGroup.TOMORROW
        else -> TimeGroup.LATER
    }
}

fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)

fun timeOnlyText(time: LocalTime): String =
    if (time.second == 0) time.format(MINUTE) else time.format(SECOND)

/** 把一段毫秒数说成人话（天/小时/分钟）。逾期多久与距现在多久共用。 */
fun humanSpan(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
    val days = totalMinutes / (60 * 24)
    val hours = totalMinutes % (60 * 24) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "${days}天${if (hours > 0) " ${hours}小时" else ""}"
        hours > 0 -> "${hours}小时${if (minutes > 0) " ${minutes}分" else ""}"
        else -> "${minutes.coerceAtLeast(1)}分钟"
    }
}
