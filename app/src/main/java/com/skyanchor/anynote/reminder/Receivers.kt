package com.skyanchor.anynote.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.skyanchor.anynote.AnyNoteApp
import com.skyanchor.anynote.AppContainer

private const val TAG = "AnyNoteReceiver"

/**
 * 广播执行的硬上限。goAsync() 只给约 10 秒，WakeLock 必须比它长、又必须有上限，
 * 否则一次失败的投递就会把 CPU 一直拽着不放。
 */
private const val WAKE_LOCK_MS = 60_000L

/**
 * 系统闹钟到点、以及通知上的"完成/稍后提醒/跳过本次"按钮都走这里。
 * 广播接收器不依赖 App 进程存活（基线 §22、§24）。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? AnyNoteApp)?.container
        if (container == null) {
            // 静默 return 会让"到点什么都没发生"永远查不到原因
            Log.w(TAG, "容器未就绪，丢弃广播 ${intent.action}")
            return
        }
        val action = intent.action
        if (action == ReminderIntents.ACTION_RESYNC) {
            guardedAsync(context, container, "anynote:selfheal") { container.selfHeal() }
            return
        }
        val occurrenceId = intent.getStringExtra(ReminderIntents.EXTRA_OCCURRENCE_ID)
        if (occurrenceId == null) {
            Log.w(TAG, "广播 $action 未携带 occurrence id，已丢弃")
            return
        }
        when (action) {
            ReminderIntents.ACTION_TRIGGER ->
                guardedAsync(context, container, "anynote:reminder") { container.coordinator.trigger(occurrenceId) }
            ReminderIntents.ACTION_COMPLETE ->
                guardedAsync(context, container, "anynote:reminder") { container.coordinator.complete(occurrenceId) }
            ReminderIntents.ACTION_SKIP ->
                guardedAsync(context, container, "anynote:reminder") { container.coordinator.skip(occurrenceId) }
            ReminderIntents.ACTION_SNOOZE -> {
                val minutes = intent.getIntExtra(ReminderIntents.EXTRA_SNOOZE_MINUTES, 10)
                guardedAsync(context, container, "anynote:reminder") { container.coordinator.snooze(occurrenceId, minutes) }
            }
            else -> Log.w(TAG, "未知广播动作 $action")
        }
    }
}

/**
 * 开机 / 应用更新 / 系统时间或时区变化后，从数据库重建全部调度。
 *
 * 这三者之后已注册的系统闹钟都不再可信：重启会清空闹钟，改时间或时区会让原时间点整体错位。
 * 至于设置里"强行停止"——它会连带清掉看门狗闹钟且 BOOT_COMPLETED 之后不再送达，
 * 那种情况只能等用户重新打开 App，Android 没有自愈通道，产品文案必须如实说明。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? AnyNoteApp)?.container
        if (container == null) {
            Log.w(TAG, "容器未就绪，丢弃广播 ${intent.action}")
            return
        }
        guardedAsync(context, container, "anynote:boot") { container.resync() }
    }
}

/**
 * goAsync() + 后台线程的完整包装：在 onReceive 返回前拿到异步句柄与 applicationContext，
 * 然后持 WakeLock 干活，超时自动释放，任何异常都留日志。
 *
 * 不持锁的后果不是理论问题——Doze 维护窗口里 CPU 随时可能重新入睡，
 * 而一次触达要做 SQLite 读写加 notify()，中途被冻结就等于这一次提醒彻底消失（基线 §24）。
 */
private fun BroadcastReceiver.guardedAsync(
    context: Context,
    container: AppContainer,
    tag: String,
    body: () -> Unit,
) {
    val app = context.applicationContext
    val pending = goAsync()
    val lock = runCatching {
        (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
            .also { it.acquire(WAKE_LOCK_MS) }
    }.getOrElse {
        // 缺 WAKE_LOCK 权限时照常干活，只是可能被 CPU 冻结——这个差异必须留在日志里
        Log.w(TAG, "$tag 未取得 WakeLock：${it.javaClass.simpleName}")
        null
    }
    Thread {
        try {
            body()
        } catch (failure: Throwable) {
            Log.e(TAG, "$tag 执行失败：${failure.javaClass.simpleName} ${failure.message}", failure)
            container.recordInterrupted(tag, failure)
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
            pending.finish()
        }
    }.start()
}
