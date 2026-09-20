package com.skyanchor.anynote.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 逾期补发的通知标题靠 humanSpan 拼出"逾期多久"，说错时长比不说更糟。
 * 纯函数，不需要 Robolectric。
 */
class HumanSpanTest {

    @Test
    fun subMinuteFloorsToOneMinute() {
        assertEquals("1分钟", humanSpan(0L))
        assertEquals("1分钟", humanSpan(59_000L))
        assertEquals("1分钟", humanSpan(-10_000L))
    }

    @Test
    fun exactHourOmitsMinutes() {
        assertEquals("1小时", humanSpan(60L * 60_000L))
        assertEquals("2小时 5分", humanSpan(125L * 60_000L))
    }

    @Test
    fun wholeDaysOmitZeroHours() {
        assertEquals("3天", humanSpan(3L * 24 * 60 * 60_000L))
        assertEquals("3天 4小时", humanSpan(3L * 24 * 60 * 60_000L + 4 * 60 * 60_000L))
    }

    @Test
    fun minutesRoundDownWithinAnHour() {
        assertEquals("2小时 59分", humanSpan(179L * 60_000L))
    }
}
