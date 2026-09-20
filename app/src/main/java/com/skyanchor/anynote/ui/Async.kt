package com.skyanchor.anynote.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地 SQLite 读取放到 IO 线程，结果变化用 key 触发重新加载。
 * MVP 数据量小，不需要完整的响应式流。
 */
@Composable
fun <T> loadAsync(key: Any?, initial: T, block: () -> T): State<T> =
    produceState(initial, key) {
        value = withContext(Dispatchers.IO) { block() }
    }

@Composable
fun <T> loadAsync(key: Any?, block: () -> T): State<T?> =
    produceState<T?>(null, key) {
        value = withContext(Dispatchers.IO) { block() }
    }

/**
 * 每次界面回到前台自增。权限、精确闹钟授权这类系统状态不在应用数据里，
 * 用户从系统设置页返回时没有任何数据变更会触发重组，只能用这个计数器重读。
 */
@Composable
fun rememberForegroundKey(): Int {
    val context = LocalContext.current
    var foregrounds by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val owner = context.lifecycleOwner() ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) foregrounds++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return foregrounds
}

private fun Context.lifecycleOwner(): LifecycleOwner? {
    var current: Context? = this
    while (current != null) {
        if (current is LifecycleOwner) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}
