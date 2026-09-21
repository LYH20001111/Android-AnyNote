package com.skyanchor.anynote.ui.theme

import androidx.compose.ui.graphics.Color

/** 蓝白主色、轻渐变、玻璃质感（对齐 UI 参考图）。 */
object AppColors {
    val BgTop = Color(0xFFF6F9FF)
    val BgBottom = Color(0xFFE4EEFD)
    val BlobBlue = Color(0x59A5CBFF)
    val BlobCyan = Color(0x40BFE3FF)
    val BlobWhite = Color(0x80FFFFFF)

    val Primary = Color(0xFF2F7BFF)
    val PrimaryDeep = Color(0xFF1C5FE0)
    val PrimarySoft = Color(0xFFE8F1FF)
    val OnPrimary = Color(0xFFFFFFFF)

    val TextPrimary = Color(0xFF16213C)
    val TextSecondary = Color(0xFF6B7A99)
    val TextTertiary = Color(0xFF9AA7BF)

    val Glass = Color(0xCCFFFFFF)
    val GlassStrong = Color(0xF5FFFFFF)
    val GlassBorder = Color(0x40FFFFFF)
    val Hairline = Color(0x1416213C)
    val Shadow = Color(0x241B3A6B)

    val Danger = Color(0xFFF2555A)
    val DangerSoft = Color(0xFFFFECEE)
    val Success = Color(0xFF1FC47B)
    val Warning = Color(0xFFF5A623)

    val PriorityHigh = Color(0xFFF2555A)
    val PriorityMedium = Color(0xFFF5A623)
    val PriorityLow = Color(0xFFB4C0D6)
}

/** 分类色：按 colorKey 取，用于图标底色、chip 与标签文字。 */
object CategoryPalette {
    private val palette = mapOf(
        "work" to (Color(0xFF2F7BFF) to Color(0xFFE8F1FF)),
        "life" to (Color(0xFF1FC47B) to Color(0xFFE6F9F0)),
        "family" to (Color(0xFFFF9500) to Color(0xFFFFF3E0)),
        "study" to (Color(0xFF8B5CF6) to Color(0xFFF1EAFF)),
        "custom" to (Color(0xFF0E9F9F) to Color(0xFFE3F6F6)),
        "other" to (Color(0xFF7A879F) to Color(0xFFEEF1F6)),
    )

    fun accent(key: String?): Color = palette[key]?.first ?: palette.getValue("other").first

    fun container(key: String?): Color = palette[key]?.second ?: palette.getValue("other").second
}
