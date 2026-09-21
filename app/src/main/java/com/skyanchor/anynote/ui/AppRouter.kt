package com.skyanchor.anynote.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface Tab {
    data object Home : Tab
    data object Calendar : Tab
    data object History : Tab
    data object Mine : Tab
}

sealed interface Route {
    data class Editor(val noteId: String?, val folderId: String?) : Route
    data class Detail(val noteId: String) : Route
    data class RuleEditor(val noteId: String, val ruleId: String?) : Route
    data object Folders : Route
    data object Trash : Route
    data object NotificationSettings : Route
    data object DataManagement : Route
}

/**
 * 手写返回栈路由。页面数量固定且层级很浅，不值得引入导航库。
 */
class AppRouter(startTab: Tab = Tab.Home) {

    var tab by mutableStateOf<Tab>(startTab)
        private set

    /** 当前 Tab 上的堆叠页面，栈空表示停在 Tab 首页。 */
    var stack by mutableStateOf<List<Route>>(emptyList())
        private set

    val current: Route? get() = stack.lastOrNull()

    fun selectTab(target: Tab) {
        stack = emptyList()
        tab = target
    }

    fun push(route: Route) {
        stack = stack + route
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack = stack.dropLast(1)
        return true
    }

    fun replace(route: Route) {
        stack = if (stack.isEmpty()) listOf(route) else stack.dropLast(1) + route
    }

    fun clear() {
        stack = emptyList()
    }
}

@Composable
fun rememberAppRouter(): AppRouter = androidx.compose.runtime.remember { AppRouter() }

/** 数据变更后让各屏幕重新读库：把刷新计数器集中在一处，避免每屏各存一份。 */
class AppState {
    var refreshKey by mutableStateOf(0)
        private set

    fun invalidate() {
        refreshKey++
    }
}
