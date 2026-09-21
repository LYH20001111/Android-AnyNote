package com.skyanchor.anynote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.ui.theme.AppColors

@Composable
fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return clickable(interactionSource = source, indication = null, onClick = onClick)
}

/** 轻渐变 + 柔光斑背景，整屏共用。 */
@Composable
fun AppBackground(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(AppColors.BgTop, AppColors.BgBottom)))
    ) {
        Box(
            Modifier
                .size(300.dp)
                .align(Alignment.TopEnd)
                .blur(70.dp)
                .background(AppColors.BlobBlue, CircleShape)
        )
        Box(
            Modifier
                .size(240.dp)
                .align(Alignment.BottomStart)
                .blur(80.dp)
                .background(AppColors.BlobCyan, CircleShape)
        )
        content()
    }
}

/** 大圆角 + 半透明白 + 细描边，参考图的玻璃卡片。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Int = 22,
    color: Color = AppColors.Glass,
    brush: Brush? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner.dp)
    val fill = if (brush != null) Modifier.background(brush) else Modifier.background(color)
    var base = modifier
        .shadow(10.dp, shape, clip = false, ambientColor = AppColors.Shadow, spotColor = AppColors.Shadow)
        .clip(shape)
        .then(fill)
        .border(1.dp, AppColors.GlassBorder, shape)
    if (onClick != null) base = base.noRippleClickable(onClick)
    Column(base.padding(contentPadding), verticalArrangement = verticalArrangement, content = content)
}

@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    corner: Int = 18,
    color: Color = AppColors.Glass,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner.dp)
    var base = modifier
        .clip(shape)
        .background(color)
        .border(1.dp, AppColors.GlassBorder, shape)
    if (onClick != null) base = base.noRippleClickable(onClick)
    Box(base, content = content)
}

/** 页面骨架：标题栏 + 内容 + 悬浮按钮 + 底部 Tab。 */
@Composable
fun ScreenScaffold(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    content: @Composable (PaddingValues) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(54.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconCircleButton(AppIcons.Back, "返回", onClick = onBack)
                Spacer(Modifier.size(8.dp))
            }
            Text(
                title.orEmpty(),
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        Box(Modifier.weight(1f)) {
            content(contentPadding)
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { floatingActionButton() }
        }
        bottomBar()
    }
}

@Composable
fun IconCircleButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Int = 40,
    tint: Color = AppColors.TextPrimary,
    background: Color = Color.Transparent,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(background)
            .noRippleClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size((size * 0.5f).dp))
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(AppColors.PrimarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = AppColors.Primary, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().height(0.8.dp).background(AppColors.Hairline))
}
