package com.skyanchor.anynote.reminder

import com.skyanchor.anynote.data.entity.ReminderRule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max

/**
 * 提醒规则引擎（基线 §5 / §6 / §25）。
 *
 * 时间语义：重复规则按"用户当前本地墙钟时间"求值，因此跨时区旅行后"每天 09:00"
 * 仍是当地 09:00；一次性规则按创建时保存的时区求值，绑定用户选定的绝对时刻。
 */
object RecurrenceEngine {

    private const val MAX_DAYS = 20_000

    /** horizon：一次最多向前物化 400 天，避免无限注册。 */
    private const val DEFAULT_HORIZON_DAYS = 400L

    fun nextOccurrence(
        rule: ReminderRule,
        from: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Instant? = occurrences(rule, from, from.plusSeconds(DEFAULT_HORIZON_DAYS * 86_400), zone).firstOrNull()

    /** 返回 ([from], [until]] 区间内该规则的全部触发时刻，升序。 */
    fun occurrences(
        rule: ReminderRule,
        from: Instant,
        until: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Instant> {
        if (!rule.isEnabled) return emptyList()

        val evalZone = if (rule.once) ZoneId.of(rule.timezone) else zone
        val time = rule.startLocal.toLocalTime()
        val startDate = rule.startLocal.toLocalDate()

        if (rule.once) {
            val instant = resolve(startDate, time, evalZone)
            return if (instant.isAfter(from) && !instant.isAfter(until)) {
                listOf(instant)
            } else {
                emptyList()
            }
        }

        val untilDate = LocalDate.ofInstant(until, evalZone)
        var cursor = maxOf(startDate, LocalDate.ofInstant(from, evalZone))
        val result = ArrayList<Instant>()
        var guard = 0
        while (guard++ < MAX_DAYS) {
            if (cursor.isAfter(untilDate)) break
            val endDate = rule.endDate
            if (endDate != null && cursor.isAfter(endDate)) break

            if (matchesDay(rule, cursor)) {
                val instant = resolve(cursor, time, evalZone)
                if (instant.isAfter(from) && !instant.isAfter(until)) {
                    result += instant
                }
            }
            cursor = cursor.plusDays(1)
        }
        return result
    }

    /**
     * [until] 时刻（含）之前最近的一次触发点，用于"完成已耗尽备忘录"时补记该轮历史。
     * 与 [occurrences] 同一套求值语义：单次规则绑定创建时区，重复规则按本地墙钟日匹配。
     */
    fun lastOccurrence(
        rule: ReminderRule,
        until: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Instant? {
        if (!rule.isEnabled) return null

        val evalZone = if (rule.once) ZoneId.of(rule.timezone) else zone
        val time = rule.startLocal.toLocalTime()
        val startDate = rule.startLocal.toLocalDate()

        if (rule.once) {
            val instant = resolve(startDate, time, evalZone)
            return if (!instant.isAfter(until)) instant else null
        }

        val untilDate = LocalDate.ofInstant(until, evalZone)
        val endDate = rule.endDate
        var cursor = if (endDate != null && endDate.isBefore(untilDate)) endDate else untilDate
        var guard = 0
        while (guard++ < MAX_DAYS) {
            if (cursor.isBefore(startDate)) break
            if (matchesDay(rule, cursor)) {
                val instant = resolve(cursor, time, evalZone)
                if (!instant.isAfter(until)) return instant
            }
            cursor = cursor.minusDays(1)
        }
        return null
    }

    /** 该日历日是否是规则的生效日（不含时间判断）。 */
    fun matchesDay(
        rule: ReminderRule,
        date: LocalDate,
        startDate: LocalDate = rule.startLocal.toLocalDate(),
    ): Boolean {
        if (date.isBefore(startDate)) return false
        return when (rule.type) {
            RecurrenceType.ONCE -> date == startDate
            RecurrenceType.DAILY -> true
            RecurrenceType.WEEKLY -> date.dayOfWeek == startDate.dayOfWeek

            // 基线 §6.2：每月不存在日期 → 当月最后一天
            RecurrenceType.MONTHLY ->
                date.dayOfMonth == clampedDay(rule.dayOfMonth ?: startDate.dayOfMonth, date)

            // 基线 §6.3：不存在该日期的年份跳过该次
            RecurrenceType.YEARLY -> {
                val month = rule.monthOfYear ?: startDate.monthValue
                val day = rule.dayOfMonth ?: startDate.dayOfMonth
                date.monthValue == month && date.dayOfMonth == day &&
                    day <= YearMonth.of(date.year, month).lengthOfMonth()
            }

            RecurrenceType.WEEKDAYS ->
                date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY

            RecurrenceType.CUSTOM_WEEKDAYS -> rule.weekdays.contains(date.dayOfWeek.value)

            RecurrenceType.INTERVAL -> {
                val step = max(1, rule.intervalValue).toLong()
                when (rule.intervalUnit) {
                    IntervalUnit.DAY -> {
                        val days = ChronoUnit.DAYS.between(startDate, date)
                        days >= 0 && days % step == 0L
                    }

                    IntervalUnit.WEEK -> {
                        val days = ChronoUnit.DAYS.between(startDate, date)
                        days >= 0 && days % (step * 7) == 0L
                    }

                    IntervalUnit.MONTH -> {
                        val months = monthDiff(startDate, date)
                        months >= 0 && months % step == 0L &&
                            date.dayOfMonth == clampedDay(startDate.dayOfMonth, date)
                    }

                    IntervalUnit.YEAR -> {
                        val years = (date.year - startDate.year).toLong()
                        years >= 0 && years % step == 0L &&
                            date.monthValue == startDate.monthValue &&
                            date.dayOfMonth == startDate.dayOfMonth
                    }
                }
            }
        }
    }

    /** 墙钟时间 → 绝对时刻；夏令时导致该时刻不存在时由 ZonedDateTime 顺延。 */
    fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId): Instant =
        LocalDateTime.of(date, time).atZone(zone).toInstant()

    private fun clampedDay(day: Int, date: LocalDate): Int =
        minOf(day, YearMonth.from(date).lengthOfMonth())

    private fun monthDiff(from: LocalDate, to: LocalDate): Long =
        (to.year - from.year) * 12L + (to.monthValue - from.monthValue)

    /** 列表/详情页的规则摘要文案，例如"每周一 10:00"。 */
    fun describe(rule: ReminderRule): String {
        val start = rule.startLocal
        val time = start.toLocalTime().format(
            if (start.second == 0) DISPLAY_MINUTE else DISPLAY_SECOND
        )
        val prefix = when (rule.type) {
            RecurrenceType.ONCE -> start.format(DISPLAY_DATE)
            RecurrenceType.DAILY -> "每天"
            RecurrenceType.WEEKLY -> "每${weekdayLabel(start.dayOfWeek.value)}"
            RecurrenceType.MONTHLY -> "每月 ${rule.dayOfMonth ?: start.dayOfMonth} 日"
            RecurrenceType.YEARLY ->
                "每年 ${rule.monthOfYear ?: start.monthValue} 月 ${rule.dayOfMonth ?: start.dayOfMonth} 日"

            RecurrenceType.WEEKDAYS -> "周一至周五"
            RecurrenceType.CUSTOM_WEEKDAYS -> rule.weekdays.sorted().joinToString("、") { weekdayLabel(it) }
            RecurrenceType.INTERVAL -> {
                val base = "每 ${max(1, rule.intervalValue)} ${rule.intervalUnit.label}"
                if (rule.intervalUnit == IntervalUnit.WEEK) "$base，${weekdayLabel(start.dayOfWeek.value)}" else base
            }
        }
        return "$prefix $time"
    }

    fun weekdayLabel(iso: Int): String = when (iso) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }

    val DISPLAY_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val DISPLAY_SECOND: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    val DISPLAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")
}
