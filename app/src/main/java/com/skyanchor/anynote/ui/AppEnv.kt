package com.skyanchor.anynote.ui

import com.skyanchor.anynote.data.AnyNoteRepository
import com.skyanchor.anynote.reminder.NotificationHelper

/** 屏幕之间共享的运行环境：数据入口 + 路由 + 反馈通道。 */
class AppEnv(
    val repository: AnyNoteRepository,
    val router: AppRouter,
    val state: AppState,
    val notifications: NotificationHelper,
    val resyncAll: () -> Unit,
    val toast: (String) -> Unit,
)
