package com.skyanchor.anynote.ui.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.ui.AppEnv
import com.skyanchor.anynote.ui.components.AppIcons
import com.skyanchor.anynote.ui.components.AppBackground
import com.skyanchor.anynote.ui.components.PrimaryButton
import com.skyanchor.anynote.ui.theme.AppColors

@Composable
fun WelcomeScreen(env: AppEnv, onStarted: () -> Unit) {
    AppBackground {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(150.dp)
                    .clip(RoundedCornerShape(44.dp))
                    .background(
                        Brush.linearGradient(listOf(AppColors.GlassStrong, AppColors.PrimarySoft))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(AppColors.Primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Bell,
                        null,
                        tint = AppColors.OnPrimary,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
            Text(
                "随记",
                style = MaterialTheme.typography.displaySmall,
                color = AppColors.TextPrimary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "让重要的事，不再被遗忘",
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(56.dp))
            PrimaryButton(
                "开始使用",
                modifier = Modifier.fillMaxWidth(),
            ) {
                env.repository.settings.welcomeSeen = true
                onStarted()
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "提醒时间支持精确到秒，实际送达由系统通知能力决定",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
