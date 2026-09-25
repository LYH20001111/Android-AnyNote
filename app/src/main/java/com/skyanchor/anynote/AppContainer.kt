package com.skyanchor.anynote

import android.app.Application
import android.content.Context
import com.skyanchor.anynote.data.AnyNoteRepository
import com.skyanchor.anynote.data.SettingsStore
import com.skyanchor.anynote.data.backup.DataBackupManager
import com.skyanchor.anynote.data.dao.AttachmentDao
import com.skyanchor.anynote.data.dao.FolderDao
import com.skyanchor.anynote.data.dao.NoteDao
import com.skyanchor.anynote.data.dao.ReminderDao
import com.skyanchor.anynote.data.db.AnyNoteDatabase
import com.skyanchor.anynote.data.entity.HealthKind
import com.skyanchor.anynote.reminder.NotificationHelper
import com.skyanchor.anynote.reminder.ReminderCoordinator
import com.skyanchor.anynote.reminder.ReminderScheduler

/**
 * 手写依赖容器。整棵对象图只有一个实例，且不需要注解处理器。
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database = AnyNoteDatabase(appContext)
    val settings = SettingsStore(appContext)

    private val folderDao = FolderDao(database)
    private val noteDao = NoteDao(database)
    private val reminderDao = ReminderDao(database)
    private val attachmentDao = AttachmentDao(database)

    val notifications = NotificationHelper(appContext, reminderDao, settings)
    val scheduler = ReminderScheduler(appContext, database, reminderDao, noteDao, settings, notifications)
    val coordinator = ReminderCoordinator(noteDao, reminderDao, folderDao, settings, scheduler, notifications)
    val repository = AnyNoteRepository(folderDao, noteDao, reminderDao, attachmentDao, settings, scheduler, coordinator)
    val dataBackup = DataBackupManager(appContext, database, scheduler)

    /**
     * 通知按钮动作与冷启动/看门狗重建共用锁，避免并发更新同一提醒导致状态被覆盖。
     */
    private val reminderMutationLock = Any()

    fun handleTrigger(occurrenceId: String) = synchronized(reminderMutationLock) {
        coordinator.trigger(occurrenceId)
    }

    fun handleNotificationAction(action: String?, occurrenceId: String, snoozeMinutes: Int) =
        synchronized(reminderMutationLock) {
            when (action) {
                com.skyanchor.anynote.reminder.ReminderIntents.ACTION_COMPLETE -> coordinator.complete(occurrenceId)
                com.skyanchor.anynote.reminder.ReminderIntents.ACTION_SNOOZE -> coordinator.snooze(occurrenceId, snoozeMinutes)
                com.skyanchor.anynote.reminder.ReminderIntents.ACTION_SKIP -> coordinator.skip(occurrenceId)
                else -> Unit
            }
        }

    /**
     * 冷启动、开机、权限恢复、系统时间变化后的全量重建。
     * 顺序是硬约束：补发必须抢在 [ReminderScheduler.syncAll] 的过期判定之前，
     * 否则那一次会被直接归档，用户连"错过"都不知道。
     */
    fun resync() = synchronized(reminderMutationLock) {
        repository.resyncAll()
        notifications.refreshBadge()
    }

    @Volatile
    private var lastSelfHealAt = 0L

    /** 看门狗闹钟触发的轻量自愈：只补发错过的、重挂前沿闹钟，不重排整棵规则树。 */
    fun selfHeal() {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastSelfHealAt < SELF_HEAL_MIN_INTERVAL_MS) return
            lastSelfHealAt = now
        }
        synchronized(reminderMutationLock) {
            coordinator.recoverMissed(now)
            scheduler.refreshFrontier()
        }
    }

    /**
     * 广播被系统冻结、SQLite 异常这类"到点什么都没发生"的现场，只在 logcat 里留痕等于没留痕——
     * 用户不会抓日志，所以必须同时落到设置页能读到的诊断表里。
     */
    fun recordInterrupted(tag: String, failure: Throwable) {
        reminderDao.recordHealth(
            HealthKind.RECEIVER_INTERRUPTED.storage,
            reason = "$tag：${failure.javaClass.simpleName}",
            detail = failure.message,
        )
    }

    /** 只做进程级轻量初始化；调度重建由 Activity、开机广播或看门狗显式发起。 */
    fun bootstrap() {
        notifications.ensureChannels()
    }

    private companion object {
        const val SELF_HEAL_MIN_INTERVAL_MS = 5L * 60 * 1000
    }
}

class AnyNoteApp : android.app.Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Thread(container::bootstrap).start()
    }
}
