package com.skyanchor.anynote.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * 固定浅色方案：产品视觉明确是"蓝白 + 玻璃质感"，
 * 因此不启用 Material Dynamic Color，避免厂商主题覆盖设计基线。
 */
private val LightColorScheme = lightColorScheme(
    primary = AppColors.Primary,
    onPrimary = AppColors.OnPrimary,
    primaryContainer = AppColors.PrimarySoft,
    onPrimaryContainer = AppColors.PrimaryDeep,
    secondary = AppColors.PrimaryDeep,
    background = AppColors.BgTop,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.GlassStrong,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.PrimarySoft,
    onSurfaceVariant = AppColors.TextSecondary,
    outline = AppColors.TextTertiary,
    outlineVariant = AppColors.Hairline,
    error = AppColors.Danger,
    onError = AppColors.OnPrimary,
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun AnyNoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content,
    )
}
