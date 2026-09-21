package com.skyanchor.anynote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.data.entity.Priority
import com.skyanchor.anynote.ui.theme.AppColors

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val bg = if (enabled) AppColors.Primary else AppColors.TextTertiary.copy(alpha = 0.4f)
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .then(if (enabled) Modifier.noRippleClickable(onClick) else Modifier)
            .padding(vertical = 15.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            icon?.let {
                Icon(it, null, tint = AppColors.OnPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = AppColors.OnPrimary,
            )
        }
    }
}

@Composable
fun TonalButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = AppColors.Primary,
    container: Color = AppColors.PrimarySoft,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .clip(shape)
            .background(container)
            .noRippleClickable(onClick)
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
fun TextAction(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppColors.Primary,
    onClick: () -> Unit,
) {
    Text(
        text,
        modifier.noRippleClickable(onClick).padding(6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = color,
    )
}

@Composable
fun FloatingActionButton(
    modifier: Modifier = Modifier,
    icon: ImageVector = AppIcons.Add,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .shadow(14.dp, CircleShape, clip = false, ambientColor = AppColors.Primary.copy(alpha = 0.42f), spotColor = AppColors.Primary.copy(alpha = 0.42f))
            .size(58.dp)
            .clip(CircleShape)
            .background(AppColors.Primary)
            .noRippleClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, "新建", tint = AppColors.OnPrimary, modifier = Modifier.size(26.dp))
    }
}

@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = AppColors.Primary,
    container: Color = AppColors.PrimarySoft,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val typography = MaterialTheme.typography
    Box(
        modifier
            .clip(shape)
            .background(if (selected) container else container.copy(alpha = 0.55f))
            .then(if (selected) Modifier.background(accent.copy(alpha = 0.13f)) else Modifier)
            .border(1.2.dp, if (selected) accent else AppColors.GlassBorder, shape)
            .noRippleClickable(onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = if (selected) {
                typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
            } else {
                typography.labelMedium
            },
            color = accent,
        )
    }
}

@Composable
fun <T> SegmentedTabs(
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .clip(shape)
            .background(AppColors.Glass)
            .border(1.dp, AppColors.GlassBorder, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (active) AppColors.Primary else Color.Transparent)
                    .noRippleClickable { onSelect(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) AppColors.OnPrimary else AppColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
fun SearchField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val textStyle = MaterialTheme.typography.bodyMedium
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .shadow(8.dp, shape, clip = false, ambientColor = AppColors.Shadow, spotColor = AppColors.Shadow)
            .clip(shape)
            .background(AppColors.Glass)
            .border(1.dp, AppColors.GlassBorder, shape)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Search, null, tint = AppColors.TextTertiary, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty() && !focused) {
                Text(
                    placeholder,
                    style = textStyle,
                    color = AppColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
                singleLine = true,
                textStyle = textStyle.copy(color = AppColors.TextPrimary),
                cursorBrush = SolidColor(AppColors.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            IconCircleButton(AppIcons.Close, "清空", size = 30, tint = AppColors.TextTertiary) { onValueChange("") }
        }
    }
}

/** 设置页通用行：图标 + 标题 +（副标题 / 右侧值 / 开关 / 箭头）。 */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    tint: Color = AppColors.Primary,
    showChevron: Boolean = true,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    var row = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)
    if (onClick != null) row = row.noRippleClickable(onClick)
    Row(row, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(AppColors.PrimarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextTertiary)
            }
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
            Spacer(Modifier.width(6.dp))
        }
        if (checked != null && onCheckedChange != null) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = AppColors.Primary,
                    checkedThumbColor = AppColors.OnPrimary,
                    uncheckedTrackColor = Color(0xFFE2E8F3),
                    uncheckedThumbColor = AppColors.OnPrimary,
                    uncheckedBorderColor = Color.Transparent,
                )
            )
        } else if (showChevron && onClick != null) {
            Icon(AppIcons.ChevronRight, null, tint = AppColors.TextTertiary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun OptionRow(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().noRippleClickable(onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(1.6.dp, if (selected) AppColors.Primary else AppColors.TextTertiary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(11.dp).clip(CircleShape).background(AppColors.Primary))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextTertiary)
            }
        }
    }
}

@Composable
fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedTrackColor = AppColors.Primary,
            checkedThumbColor = AppColors.OnPrimary,
            uncheckedTrackColor = Color(0xFFE2E8F3),
            uncheckedThumbColor = AppColors.OnPrimary,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
fun TagPill(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = AppColors.Primary,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PriorityDot(priority: Priority, modifier: Modifier = Modifier) {
    val color = when (priority) {
        Priority.HIGH -> AppColors.PriorityHigh
        Priority.MEDIUM -> AppColors.PriorityMedium
        Priority.LOW -> AppColors.PriorityLow
    }
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
            .border(2.dp, color.copy(alpha = 0.25f), CircleShape)
    )
}

@Composable
fun SpacerHeight(dp: Int) = Spacer(Modifier.height(dp.dp))

@Composable
fun SpacerWidth(dp: Int) = Spacer(Modifier.width(dp.dp))
