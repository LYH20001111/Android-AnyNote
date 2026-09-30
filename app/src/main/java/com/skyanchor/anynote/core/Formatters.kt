package com.skyanchor.anynote.core

import android.content.Context
import com.skyanchor.anynote.R
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

/** 星期几 → 本地化字符串资源 id（周一=1 … 周日=7，即 ISO 编号）。 */
fun weekdayRes(iso: Int): Int = when (iso) {
    1 -> R.string.weekday_mon
    2 -> R.string.weekday_tue
    3 -> R.string.weekday_wed
    4 -> R.string.weekday_thu
    5 -> R.string.weekday_fri
    6 -> R.string.weekday_sat
    else -> R.string.weekday_sun
}

fun toLocal(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

fun timeText(millis: Long): String {
    val dt = toLocal(millis)
    return if (dt.second == 0) dt.format(MINUTE) else dt.format(SECOND)
}

fun dateText(millis: Long): String = toLocal(millis).format(FULL_DATE)

fun dateTimeText(millis: Long): String = toLocal(millis).format(FULL_DATE_TIME)

/** 按系统语言显示的日期（中文仍为"yyyy年M月d日"）。 */
fun dateText(context: Context, millis: Long): String =
    toLocal(millis).format(DateTimeFormatter.ofPattern(context.getString(R.string.pattern_full_date)))

/** 按系统语言显示的日期时间。 */
fun dateTimeText(context: Context, millis: Long): String =
    toLocal(millis).format(DateTimeFormatter.ofPattern(context.getString(R.string.pattern_full_date_time)))

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

/** 按系统语言显示的列表时间标签（今天/明天/昨天用相对说法）。 */
fun listTimeText(context: Context, millis: Long): String {
    val dt = toLocal(millis)
    val today = LocalDate.now()
    val day = dt.toLocalDate()
    val prefix = when {
        day == today -> context.getString(R.string.rel_today)
        day == today.plusDays(1) -> context.getString(R.string.rel_tomorrow)
        day == today.minusDays(1) -> context.getString(R.string.rel_yesterday)
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern(context.getString(R.string.pattern_month_day)))
        else -> day.format(DateTimeFormatter.ofPattern(context.getString(R.string.pattern_full_date)))
    }
    return "$prefix ${dt.format(if (dt.second == 0) MINUTE else SECOND)}"
}

/** 首页分组（基线 §12）。 */
enum class TimeGroup(val label: String, val labelRes: Int) {
    OVERDUE("已逾期", R.string.time_group_overdue),
    TODAY("今天", R.string.time_group_today),
    TOMORROW("明天", R.string.time_group_tomorrow),
    LATER("更后面", R.string.time_group_later),
    NO_REMINDER("无提醒", R.string.time_group_no_reminder),
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

/** 把一段毫秒数说成人话（天/小时/分钟）。逾期多久与距现在多久共用。单位词可由调用方按语言传入。 */
fun humanSpan(
    ms: Long,
    dayUnit: String = "天",
    hourUnit: String = "小时",
    minuteUnit: String = "分",
    minuteUnitLong: String = "分钟",
): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
    val days = totalMinutes / (60 * 24)
    val hours = totalMinutes % (60 * 24) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "$days$dayUnit${if (hours > 0) " $hours$hourUnit" else ""}"
        hours > 0 -> "$hours$hourUnit${if (minutes > 0) " $minutes$minuteUnit" else ""}"
        else -> "${minutes.coerceAtLeast(1)}$minuteUnitLong"
    }
}

/** 按系统语言说时长（中文仍为"3天 4小时"）。 */
fun humanSpan(context: Context, ms: Long): String = humanSpan(
    ms,
    context.getString(R.string.span_day),
    context.getString(R.string.span_hour),
    context.getString(R.string.span_minute),
    context.getString(R.string.span_minutes),
)
