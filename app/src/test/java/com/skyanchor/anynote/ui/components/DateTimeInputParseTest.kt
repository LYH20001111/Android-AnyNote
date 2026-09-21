package com.skyanchor.anynote.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/**
 * 提醒时间手输框走的是宽松解析：允许只写日期、只写时间或省略年份，
 * 所以"改时间不该顺手把日期填回去"这类细节必须钉住，否则用户会输错提醒点。
 * 纯函数，不需要 Robolectric。
 */
class DateTimeInputParseTest {

    private val fallback = LocalDateTime.of(2026, 9, 21, 14, 35, 20)

    private fun parse(text: String): LocalDateTime? = parseFlexible(text, fallback, true)

    @Test
    fun fullDateTimeOverridesEveryField() {
        assertEquals(LocalDateTime.of(2027, 3, 5, 9, 30, 15), parse("2027-03-05 09:30:15"))
    }

    @Test
    fun timeOnlyKeepsCurrentDate() {
        assertEquals(LocalDateTime.of(2026, 9, 21, 9, 30), parse("9:30"))
        assertEquals(LocalDateTime.of(2026, 9, 21, 23, 59, 59), parse("23:59:59"))
    }

    @Test
    fun dateOnlyKeepsCurrentTime() {
        assertEquals(LocalDateTime.of(2027, 1, 8, 14, 35, 20), parse("2027-1-8"))
    }

    @Test
    fun partialDateFillsMissingSegmentFromFallback() {
        assertEquals(LocalDateTime.of(2026, 11, 21, 14, 35, 20), parse("11-21"))
        assertEquals(LocalDateTime.of(2027, 5, 21, 14, 35, 20), parse("2027/5"))
        assertEquals(LocalDateTime.of(2026, 9, 21, 14, 35, 20), parse("20260921"))
    }

    @Test
    fun twoDigitYearMeansThisCentury() {
        assertEquals(LocalDateTime.of(2027, 6, 1, 8, 0), parse("27-6-1 8:00"))
    }

    @Test
    fun chineseUnitsAndFullWidthPunctuationAreAccepted() {
        assertEquals(LocalDateTime.of(2026, 9, 25, 14, 5), parse("2026年9月25日 14点5分"))
        assertEquals(LocalDateTime.of(2026, 9, 21, 18, 30), parse("18：30"))
    }

    @Test
    fun timeBeforeDateAlsoWorks() {
        assertEquals(LocalDateTime.of(2027, 2, 3, 7, 45), parse("07:45 2027-02-03"))
    }

    @Test
    fun rejectsImpossibleValues() {
        assertNull(parse("2026-02-30 09:00"))
        assertNull(parse("25:00"))
        assertNull(parse("2026-13-01"))
        assertNull(parse("09:30 10:00"))
        assertNull(parse("abc"))
        assertNull(parse("   "))
        assertNull(parse(""))
    }

    @Test
    fun dateOnlyModeRefusesTime() {
        assertNull(parseFlexible("2026-09-25 09:00", fallback, false))
        assertEquals(LocalDateTime.of(2026, 9, 25, 14, 35, 20), parseFlexible("9-25", fallback, false))
    }

    @Test
    fun displayDropsSecondsUntilTheyMatter() {
        assertEquals("2026-09-21 14:35", formatPicked(fallback.withSecond(0), true))
        assertEquals("2026-09-21 14:35:20", formatPicked(fallback, true))
        assertEquals("2026-09-21", formatPicked(fallback, false))
    }

    @Test
    fun formattedValueParsesBackToItself() {
        listOf(
            LocalDateTime.of(2026, 1, 1, 0, 0),
            LocalDateTime.of(2031, 12, 31, 23, 59, 59),
            LocalDateTime.of(2028, 2, 29, 6, 7, 8),
        ).forEach {
            assertEquals(it, parseFlexible(formatPicked(it, true), fallback, true))
        }
    }
}
