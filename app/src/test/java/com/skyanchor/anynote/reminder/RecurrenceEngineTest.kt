package com.skyanchor.anynote.reminder

import com.skyanchor.anynote.data.entity.CompletionMode
import com.skyanchor.anynote.data.entity.ReminderRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 基线 §31 要求的最小边界集：跨天/跨月/跨年、闰年与月末收敛、每 N 间隔、
 * 工作日与自定义星期、结束日期，以及 §25 的墙钟/绝对时刻与夏令时语义。
 *
 * 断言默认按"求值时区下的墙钟时间"比较，只有需要证明绝对时刻时才换算到 UTC。
 */
class RecurrenceEngineTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val kolkata: ZoneId = ZoneId.of("Asia/Kolkata")

    @Test
    fun dailyCrossesDayMonthAndYearBoundaries() {
        val daily = rule(RecurrenceType.DAILY, at("2026-12-31T23:30"))

        assertEquals(
            listOf(at("2026-12-31T23:30"), at("2027-01-01T23:30"), at("2027-01-02T23:30")),
            wallClock(daily, at("2026-12-31T00:00"), at("2027-01-03T00:00")),
        )
    }

    @Test
    fun weeklyKeepsTheStartWeekday() {
        // 2026-09-25 是周五
        val weekly = rule(RecurrenceType.WEEKLY, at("2026-09-25T10:00"))
        val result = wallClock(weekly, at("2026-09-26T00:00"), at("2026-11-01T00:00"))

        assertEquals(
            listOf(
                at("2026-10-02T10:00"), at("2026-10-09T10:00"), at("2026-10-16T10:00"),
                at("2026-10-23T10:00"), at("2026-10-30T10:00"),
            ),
            result,
        )
        result.forEach { assertEquals(DayOfWeek.FRIDAY, it.dayOfWeek) }
    }

    @Test
    fun monthlyClampsMissingDayToMonthEnd() {
        val monthly = rule(RecurrenceType.MONTHLY, at("2026-01-31T09:00"), dayOfMonth = 31)

        assertEquals(
            listOf(
                at("2026-01-31T09:00"), at("2026-02-28T09:00"), at("2026-03-31T09:00"),
                at("2026-04-30T09:00"), at("2026-05-31T09:00"), at("2026-06-30T09:00"),
            ),
            wallClock(monthly, at("2026-01-01T00:00"), at("2026-06-30T23:59")),
        )
    }

    @Test
    fun monthlyClampsToLeapFebruary() {
        val monthly = rule(RecurrenceType.MONTHLY, at("2028-01-31T09:00"), dayOfMonth = 31)

        assertEquals(
            listOf(at("2028-02-29T09:00")),
            wallClock(monthly, at("2028-02-01T00:00"), at("2028-03-01T00:00")),
        )
    }

    @Test
    fun yearlyOnLeapDaySkipsNonLeapYears() {
        val yearly = rule(
            RecurrenceType.YEARLY,
            at("2024-02-29T09:00"),
            monthOfYear = 2,
            dayOfMonth = 29,
        )

        assertEquals(
            listOf(at("2028-02-29T09:00")),
            wallClock(yearly, at("2024-03-01T00:00"), at("2029-06-01T00:00")),
        )
    }

    @Test
    fun yearlyOnFixedDateKeepsMonthAndDay() {
        val yearly = rule(
            RecurrenceType.YEARLY,
            at("2026-08-08T20:00"),
            monthOfYear = 8,
            dayOfMonth = 8,
        )

        assertEquals(
            listOf(at("2026-08-08T20:00"), at("2027-08-08T20:00"), at("2028-08-08T20:00")),
            wallClock(yearly, at("2026-01-01T00:00"), at("2029-01-01T00:00")),
        )
    }

    @Test
    fun weekdaysSkipSaturdayAndSunday() {
        val weekdays = rule(RecurrenceType.WEEKDAYS, at("2026-09-25T08:00"))

        assertEquals(
            listOf(
                at("2026-09-25T08:00"), at("2026-09-28T08:00"), at("2026-09-29T08:00"),
                at("2026-09-30T08:00"), at("2026-10-01T08:00"), at("2026-10-02T08:00"),
            ),
            wallClock(weekdays, at("2026-09-25T00:00"), at("2026-10-05T00:00")),
        )
    }

    @Test
    fun customWeekdaysFireOnlyOnSelectedDays() {
        val custom = rule(
            RecurrenceType.CUSTOM_WEEKDAYS,
            at("2026-09-25T07:30"),
            weekdays = setOf(1, 4, 7),
        )

        assertEquals(
            listOf(
                at("2026-09-27T07:30"), at("2026-09-28T07:30"), at("2026-10-01T07:30"),
                at("2026-10-04T07:30"), at("2026-10-05T07:30"),
            ),
            wallClock(custom, at("2026-09-26T00:00"), at("2026-10-06T00:00")),
        )
    }

    @Test
    fun intervalEveryNDaysIsAnchoredToStartDate() {
        val every3Days = rule(
            RecurrenceType.INTERVAL,
            at("2026-09-01T06:00"),
            intervalValue = 3,
            intervalUnit = IntervalUnit.DAY,
        )

        assertEquals(
            listOf(
                at("2026-09-01T06:00"), at("2026-09-04T06:00"), at("2026-09-07T06:00"),
                at("2026-09-10T06:00"), at("2026-09-13T06:00"), at("2026-09-16T06:00"),
                at("2026-09-19T06:00"),
            ),
            wallClock(every3Days, at("2026-09-01T00:00"), at("2026-09-20T00:00")),
        )
    }

    @Test
    fun intervalEveryNWeeksKeepsWeekday() {
        val every2Weeks = rule(
            RecurrenceType.INTERVAL,
            at("2026-09-02T06:00"),
            intervalValue = 2,
            intervalUnit = IntervalUnit.WEEK,
        )
        val result = wallClock(every2Weeks, at("2026-09-01T00:00"), at("2026-10-05T00:00"))

        assertEquals(
            listOf(at("2026-09-02T06:00"), at("2026-09-16T06:00"), at("2026-09-30T06:00")),
            result,
        )
        result.forEach { assertEquals(DayOfWeek.WEDNESDAY, it.dayOfWeek) }
    }

    @Test
    fun intervalEveryNMonthsClampsToMonthEnd() {
        val every2Months = rule(
            RecurrenceType.INTERVAL,
            at("2026-01-31T06:00"),
            intervalValue = 2,
            intervalUnit = IntervalUnit.MONTH,
        )

        assertEquals(
            listOf(
                at("2026-01-31T06:00"), at("2026-03-31T06:00"), at("2026-05-31T06:00"),
                at("2026-07-31T06:00"), at("2026-09-30T06:00"), at("2026-11-30T06:00"),
            ),
            wallClock(every2Months, at("2026-01-01T00:00"), at("2026-12-31T23:00")),
        )
    }

    @Test
    fun intervalEveryNYearsKeepsMonthAndDay() {
        val every2Years = rule(
            RecurrenceType.INTERVAL,
            at("2025-03-15T06:00"),
            intervalValue = 2,
            intervalUnit = IntervalUnit.YEAR,
        )

        assertEquals(
            listOf(at("2025-03-15T06:00"), at("2027-03-15T06:00"), at("2029-03-15T06:00")),
            wallClock(every2Years, at("2025-01-01T00:00"), at("2030-01-01T00:00")),
        )
    }

    @Test
    fun endDateIsTheLastFiringDay() {
        val daily = rule(RecurrenceType.DAILY, at("2026-09-01T09:00"), endDate = LocalDate.of(2026, 9, 5))

        assertEquals(
            listOf(
                at("2026-09-01T09:00"), at("2026-09-02T09:00"), at("2026-09-03T09:00"),
                at("2026-09-04T09:00"), at("2026-09-05T09:00"),
            ),
            wallClock(daily, at("2026-09-01T00:00"), at("2026-09-30T00:00")),
        )
    }

    @Test
    fun disabledRuleProducesNothing() {
        val daily = rule(RecurrenceType.DAILY, at("2026-09-01T09:00"), isEnabled = false)

        assertEquals(
            emptyList<LocalDateTime>(),
            wallClock(daily, at("2026-08-01T00:00"), at("2026-12-01T00:00")),
        )
        assertNull(RecurrenceEngine.nextOccurrence(daily, instant(at("2026-08-01T00:00")), shanghai))
    }

    @Test
    fun onceFiresOnlyWhileItsAbsoluteTimeIsFuture() {
        val once = rule(RecurrenceType.ONCE, at("2026-09-20T15:30"))

        assertEquals(
            listOf(at("2026-09-20T15:30")),
            wallClock(once, at("2026-09-01T00:00"), at("2026-10-01T00:00")),
        )
        assertEquals(
            emptyList<LocalDateTime>(),
            wallClock(once, at("2026-09-21T00:00"), at("2026-10-01T00:00")),
        )
    }

    /** 基线 §25：一次性规则绑定创建时区的绝对时刻，不受设备当前时区影响。 */
    @Test
    fun onceIgnoresTheEvaluatingZone() {
        val once = rule(RecurrenceType.ONCE, at("2026-09-20T15:30"), timezone = "Asia/Shanghai")

        assertEquals(
            listOf(Instant.parse("2026-09-20T07:30:00Z")),
            instants(once, at("2026-09-01T00:00"), at("2026-10-01T00:00"), newYork),
        )
    }

    /** 基线 §25：重复规则按求值时的当地墙钟重算，跨时区旅行后"每天 09:00"仍是当地 09:00。 */
    @Test
    fun recurringFollowsWallClockOfTheEvaluatingZone() {
        val daily = rule(RecurrenceType.DAILY, at("2026-06-01T09:00"), timezone = "Asia/Shanghai")

        assertEquals(
            listOf(at("2026-06-10T09:00")),
            wallClock(daily, at("2026-06-10T00:00"), at("2026-06-10T12:00"), newYork),
        )
        assertEquals(
            listOf(Instant.parse("2026-06-10T13:00:00Z")),
            instants(daily, at("2026-06-10T00:00"), at("2026-06-11T00:00"), newYork),
        )
        assertEquals(
            listOf(Instant.parse("2026-06-10T03:30:00Z")),
            instants(daily, at("2026-06-10T00:00"), at("2026-06-11T00:00"), kolkata),
        )
    }

    /** 2026-03-08 02:00 纽约拨快一小时，02:30 这个墙钟时刻不存在，顺延到 03:30。 */
    @Test
    fun springForwardGapShiftsMissingWallClock() {
        val daily = rule(RecurrenceType.DAILY, at("2026-03-07T02:30"), timezone = "America/New_York")

        assertEquals(
            listOf(
                Instant.parse("2026-03-07T07:30:00Z"),
                Instant.parse("2026-03-08T07:30:00Z"),
                Instant.parse("2026-03-09T06:30:00Z"),
            ),
            instants(daily, at("2026-03-07T00:00"), at("2026-03-10T00:00"), newYork),
        )
        assertEquals(
            listOf(at("2026-03-07T02:30"), at("2026-03-08T03:30"), at("2026-03-09T02:30")),
            wallClock(daily, at("2026-03-07T00:00"), at("2026-03-10T00:00"), newYork),
        )
    }

    /** 区间语义是左开右闭：恰好等于 from 的时刻已经错过，恰好等于 until 的仍然计入。 */
    @Test
    fun windowIsExclusiveAtStartAndInclusiveAtEnd() {
        val daily = rule(RecurrenceType.DAILY, at("2026-09-01T09:00"))

        assertEquals(
            listOf(at("2026-09-02T09:00")),
            wallClock(daily, at("2026-09-01T09:00"), at("2026-09-03T00:00")),
        )
        assertEquals(
            listOf(at("2026-09-02T09:00")),
            wallClock(daily, at("2026-09-01T12:00"), at("2026-09-02T09:00")),
        )
    }

    @Test
    fun nextOccurrenceRespectsTheMaterializationHorizon() {
        val yearly = rule(
            RecurrenceType.YEARLY,
            at("2026-01-01T08:00"),
            monthOfYear = 1,
            dayOfMonth = 1,
        )
        val next = RecurrenceEngine.nextOccurrence(yearly, instant(at("2026-06-01T00:00")), shanghai)
        assertEquals(at("2027-01-01T08:00"), next?.let { LocalDateTime.ofInstant(it, shanghai) })

        val every3Years = rule(
            RecurrenceType.INTERVAL,
            at("2026-01-01T08:00"),
            intervalValue = 3,
            intervalUnit = IntervalUnit.YEAR,
        )
        assertNull(RecurrenceEngine.nextOccurrence(every3Years, instant(at("2026-06-01T00:00")), shanghai))
    }

    @Test
    fun describeReadsLikeTheUiCopy() {
        assertEquals("每周五 10:00", RecurrenceEngine.describe(rule(RecurrenceType.WEEKLY, at("2026-09-25T10:00"))))
        assertEquals(
            "每月 15 日 09:00",
            RecurrenceEngine.describe(rule(RecurrenceType.MONTHLY, at("2026-09-01T09:00"), dayOfMonth = 15)),
        )
        assertEquals(
            "每年 2 月 29 日 09:00",
            RecurrenceEngine.describe(
                rule(RecurrenceType.YEARLY, at("2024-02-29T09:00"), monthOfYear = 2, dayOfMonth = 29),
            ),
        )
        assertEquals("周一至周五 08:00", RecurrenceEngine.describe(rule(RecurrenceType.WEEKDAYS, at("2026-09-25T08:00"))))
        assertEquals(
            "周一、周四、周日 07:30",
            RecurrenceEngine.describe(
                rule(RecurrenceType.CUSTOM_WEEKDAYS, at("2026-09-25T07:30"), weekdays = setOf(1, 4, 7)),
            ),
        )
        assertEquals(
            "每 2 周，周三 06:00",
            RecurrenceEngine.describe(
                rule(
                    RecurrenceType.INTERVAL, at("2026-09-02T06:00"),
                    intervalValue = 2, intervalUnit = IntervalUnit.WEEK,
                ),
            ),
        )
        // 单次提醒的摘要只写日期，时间由 describe 统一追加
        assertEquals("2026年9月20日 15:30", RecurrenceEngine.describe(rule(RecurrenceType.ONCE, at("2026-09-20T15:30"))))
        assertEquals(
            "2026年9月20日 15:30:45",
            RecurrenceEngine.describe(rule(RecurrenceType.ONCE, LocalDateTime.parse("2026-09-20T15:30:45"))),
        )
    }

    private fun rule(
        type: RecurrenceType,
        startLocal: LocalDateTime,
        timezone: String = "Asia/Shanghai",
        intervalValue: Int = 1,
        intervalUnit: IntervalUnit = IntervalUnit.DAY,
        weekdays: Set<Int> = setOf(1, 2, 3, 4, 5),
        dayOfMonth: Int? = null,
        monthOfYear: Int? = null,
        endDate: LocalDate? = null,
        isEnabled: Boolean = true,
    ): ReminderRule = ReminderRule(
        id = "rule-test",
        noteId = "note-test",
        type = type,
        startLocal = startLocal,
        timezone = timezone,
        intervalValue = intervalValue,
        intervalUnit = intervalUnit,
        weekdays = weekdays,
        dayOfMonth = dayOfMonth,
        monthOfYear = monthOfYear,
        endDate = endDate,
        completionMode = CompletionMode.CONTINUE,
        autoRepeatEnabled = false,
        autoRepeatIntervalMinutes = 10,
        autoRepeatLimit = 3,
        isEnabled = isEnabled,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun wallClock(
        rule: ReminderRule,
        from: LocalDateTime,
        until: LocalDateTime,
        zone: ZoneId = shanghai,
    ): List<LocalDateTime> = instants(rule, from, until, zone).map { LocalDateTime.ofInstant(it, zone) }

    private fun instants(
        rule: ReminderRule,
        from: LocalDateTime,
        until: LocalDateTime,
        zone: ZoneId = shanghai,
    ): List<Instant> = RecurrenceEngine.occurrences(rule, instant(from, zone), instant(until, zone), zone)

    private fun instant(local: LocalDateTime, zone: ZoneId = shanghai): Instant = local.atZone(zone).toInstant()

    private fun at(text: String): LocalDateTime = LocalDateTime.parse(text)
}
