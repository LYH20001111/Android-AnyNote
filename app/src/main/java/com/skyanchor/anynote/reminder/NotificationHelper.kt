package com.skyanchor.anynote.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.skyanchor.anynote.MainActivity
import com.skyanchor.anynote.R
import com.skyanchor.anynote.core.humanSpan
import com.skyanchor.anynote.data.SettingsStore
import com.skyanchor.anynote.data.dao.ReminderDao
import com.skyanchor.anynote.data.entity.Note
import com.skyanchor.anynote.data.entity.ReminderOccurrence

object ReminderIntents {
    const val ACTION_TRIGGER = "com.skyanchor.anynote.action.TRIGGER"
    const val ACTION_COMPLETE = "com.skyanchor.anynote.action.COMPLETE"
    const val ACTION_SNOOZE = "com.skyanchor.anynote.action.SNOOZE"
    const val ACTION_SKIP = "com.skyanchor.anynote.action.SKIP"

    /** 只重建调度、不产生任何通知：看门狗闹钟专用。 */
    const val ACTION_RESYNC = "com.skyanchor.anynote.action.RESYNC"
    const val EXTRA_OCCURRENCE_ID = "occurrence_id"
    const val EXTRA_NOTE_ID = "note_id"
    const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
}

/**
 * 一次通知投递的结果。提醒事件只有在 [ShowOutcome.Posted] 时才允许被判定为"已触达"，
 * 否则通知没弹出去、事件却已被消费，用户就再也收不到这一次提醒（基线 §24）。
 */
sealed interface ShowOutcome {
    object Posted : ShowOutcome
    object NoPermission : ShowOutcome
    data class Error(val cause: String) : ShowOutcome
}

/**
 * 通知展示层（基线 §10）。系统通知只是执行层，本地数据库才是事实来源。
 */
class NotificationHelper(
    context: Context,
    private val dao: ReminderDao,
    private val settings: SettingsStore,
) {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    /** small icon 在状态栏只会剩下单色剪影，彩色 Logo 只能由 largeIcon 承载。 */
    private val appLogo: Bitmap? by lazy {
        // 取本 APK 自带的 ic_launcher，而不是 PackageManager 的已安装副本：后者会吃到系统图标缓存。
        val drawable = ContextCompat.getDrawable(appContext, R.mipmap.ic_launcher) ?: return@lazy null
        val size = (128 * appContext.resources.displayMetrics.density).toInt()
        runCatching {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(bitmap))
            }
        }.getOrNull()
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val name = appContext.getString(R.string.channel_reminders_name)
        val desc = appContext.getString(R.string.channel_reminders_desc)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SOUND, "$name（声音）", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = desc
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
                NotificationChannel(CHANNEL_SILENT, "$name（静音）", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = desc
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
        )
    }

    val permissionGranted: Boolean get() = manager.areNotificationsEnabled()

    /** Android 12+ 精确闹钟需要用户授权，未授权时降级为时间窗口（基线 §21.2）。 */
    val exactAlarmAllowed: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } else {
            true
        }

    fun openSystemNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${appContext.packageName}"))
        }
        runCatching { appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            appContext.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${appContext.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 是否已免除电池优化。未免除时 Doze 会把闹钟合并延后。 */
    val ignoresBatteryOptimizations: Boolean
        get() = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) true else powerManager
            .isIgnoringBatteryOptimizations(appContext.packageName)

    fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (ignoresBatteryOptimizations) {
            openAppSettings()
            return
        }
        // 部分定制 ROM 不受理带包名的直达 Intent，退回到系统的优化列表页。
        runCatching {
            appContext.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${appContext.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                appContext.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** 国产 ROM 的自启动/后台管控藏在应用详情页或厂商私有页里，只能把用户送到门口。 */
    fun openAppSettings() = runCatching {
        appContext.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${appContext.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private val powerManager: android.os.PowerManager
        get() = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager

    fun show(
        occurrence: ReminderOccurrence,
        note: Note,
        folderName: String?,
        pendingCount: Int,
        overdueMs: Long = 0L,
    ): ShowOutcome {
        if (!permissionGranted) return ShowOutcome.NoPermission
        val preview = note.notificationPreviewEnabled
        val title = note.title?.trim().takeUnless { it.isNullOrEmpty() }
            ?: appContext.getString(R.string.notification_fallback_title)
        val body = if (preview && note.body.isNotBlank()) {
            note.body
        } else {
            appContext.getString(R.string.notification_fallback_body)
        }
        val id = occurrence.notificationId

        val builder = NotificationCompat.Builder(appContext, channelFor())
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(appLogo)
            .setContentTitle(
                // 逾期标记只作前缀：标题本身仍要能认出是哪条备忘录
                if (overdueMs > 0) {
                    appContext.getString(R.string.notification_overdue_title, humanSpan(overdueMs), title)
                } else {
                    title
                }
            )
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(if (preview) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_SECRET)
            .setContentIntent(contentIntent(note.id, occurrence.id))
            .setAutoCancel(true)
            .setWhen(occurrence.scheduledAt)

        if (settings.badgeEnabled) builder.setNumber(pendingCount.coerceAtLeast(1))
        folderName?.let { builder.setSubText(it) }

        if (note.completionEnabled) {
            builder.addAction(0, appContext.getString(R.string.action_complete), actionIntent(ReminderIntents.ACTION_COMPLETE, note.id, occurrence.id, slot(id, 1)))
            builder.addAction(
                0,
                appContext.getString(R.string.action_snooze),
                actionIntent(ReminderIntents.ACTION_SNOOZE, note.id, occurrence.id, slot(id, 2), settings.defaultSnoozeMinutes)
            )
            builder.addAction(0, appContext.getString(R.string.action_skip), actionIntent(ReminderIntents.ACTION_SKIP, note.id, occurrence.id, slot(id, 3)))
        } else {
            // 不启用完成状态的备忘录：划掉通知即代表本次事件结束（基线 §7.1）
            builder.setDeleteIntent(actionIntent(ReminderIntents.ACTION_COMPLETE, note.id, occurrence.id, slot(id, 4)))
        }

        return runCatching { manager.notify(id, builder.build()) }
            .fold({ ShowOutcome.Posted }, { ShowOutcome.Error(it.javaClass.simpleName) })
    }

    fun cancel(notificationId: Int) = runCatching { manager.cancel(notificationId) }

    /** 角标计数：Android 没有公开 API，只能靠一条最低优先级的常驻通知承载。 */
    fun refreshBadge() {
        if (!settings.badgeEnabled || !permissionGranted) return
        val pending = dao.pendingActive().size
        runCatching { manager.notify(BADGE_ID, badgeNotification(pending)) }
    }

    private fun badgeNotification(count: Int): Notification =
        NotificationCompat.Builder(appContext, CHANNEL_SILENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setNumber(count)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentTitle(appContext.getString(R.string.notification_fallback_title))
            .setContentText(if (count > 0) "$count 条待处理" else "")
            .build()

    private fun channelFor(): String =
        if (settings.notificationsEnabled && settings.soundEnabled) CHANNEL_SOUND else CHANNEL_SILENT

    private fun contentIntent(noteId: String, occurrenceId: String): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            noteId.hashCode(),
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_NOTE_ID, noteId)
                putExtra(MainActivity.EXTRA_OCCURRENCE_ID, occurrenceId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun actionIntent(
        action: String,
        noteId: String,
        occurrenceId: String,
        requestCode: Int,
        snoozeMinutes: Int = 0,
    ): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        requestCode,
        // 必须显式指向 ReminderReceiver：它在 manifest 里没有 <intent-filter>，
        // 只带 action + package 的隐式广播解析不到任何接收器，按钮点了永远没有响应。
        Intent(appContext, ReminderReceiver::class.java).apply {
            setAction(action)
            data = Uri.parse("anynote://reminder/$occurrenceId")
            putExtra(ReminderIntents.EXTRA_OCCURRENCE_ID, occurrenceId)
            putExtra(ReminderIntents.EXTRA_NOTE_ID, noteId)
            if (snoozeMinutes > 0) putExtra(ReminderIntents.EXTRA_SNOOZE_MINUTES, snoozeMinutes)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun slot(notificationId: Int, index: Int): Int = notificationId * 8 + index

    companion object {
        const val CHANNEL_SOUND = "anynote_reminders_sound"
        const val CHANNEL_SILENT = "anynote_reminders_silent"
        const val BADGE_ID = -1
    }
}
