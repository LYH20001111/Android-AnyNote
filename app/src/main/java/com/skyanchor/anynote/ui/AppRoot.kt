package com.skyanchor.anynote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.skyanchor.anynote.ui.components.AppBackground
import com.skyanchor.anynote.ui.calendar.CalendarScreen
import com.skyanchor.anynote.ui.history.HistoryScreen
import com.skyanchor.anynote.ui.home.HomeScreen
import com.skyanchor.anynote.ui.mine.MineScreen
import com.skyanchor.anynote.ui.note.NoteDetailScreen
import com.skyanchor.anynote.ui.note.NoteEditorScreen
import com.skyanchor.anynote.ui.reminder.ReminderRuleEditorScreen
import com.skyanchor.anynote.ui.settings.FolderManageScreen
import com.skyanchor.anynote.ui.settings.NotificationSettingsScreen
import com.skyanchor.anynote.ui.settings.TrashScreen
import com.skyanchor.anynote.ui.splash.WelcomeScreen

@Composable
fun AppRoot(env: AppEnv) {
    val router = env.router
    val route = router.current

    // 持久化开关本身不是快照状态，写库不会触发重组，这里包一层供本作用域观察
    var welcomeSeen by remember { mutableStateOf(env.repository.settings.welcomeSeen) }

    if (!welcomeSeen) {
        WelcomeScreen(env) { welcomeSeen = true }
        return
    }

    BackHandler(enabled = route != null || router.tab != Tab.Home) {
        if (!router.pop()) router.selectTab(Tab.Home)
    }

    AppBackground {
        when (route) {
            null -> when (router.tab) {
                Tab.Home -> HomeScreen(env)
                Tab.Calendar -> CalendarScreen(env)
                Tab.History -> HistoryScreen(env)
                Tab.Mine -> MineScreen(env)
            }

            is Route.Editor -> NoteEditorScreen(env, route.noteId, route.folderId)
            is Route.Detail -> NoteDetailScreen(env, route.noteId)
            is Route.RuleEditor -> ReminderRuleEditorScreen(env, route.noteId, route.ruleId)
            Route.Folders -> FolderManageScreen(env)
            Route.Trash -> TrashScreen(env)
            Route.NotificationSettings -> NotificationSettingsScreen(env)
        }
    }
}
