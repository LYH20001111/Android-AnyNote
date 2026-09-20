package com.skyanchor.anynote.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.skyanchor.anynote.core.humanSpan
import com.skyanchor.anynote.core.listTimeText
import com.skyanchor.anynote.core.timeText
import com.skyanchor.anynote.data.SettingsStore
import com.skyanchor.anynote.data.entity.HealthEvent
import com.skyanchor.anynote.data.entity.HealthKind
import com.skyanchor.anynote.reminder.DeviceProfiles
import com.skyanchor.anynote.reminder.NotificationHelper
import com.skyanchor.anynote.ui.AppEnv
import com.skyanchor.anynote.ui.components.AppIcons
import com.skyanchor.anynote.ui.components.GlassCard
import com.skyanchor.anynote.ui.components.Hairline
import com.skyanchor.anynote.ui.components.InfoBanner
import com.skyanchor.anynote.ui.components.ScreenScaffold
import com.skyanchor.anynote.ui.components.SectionHeader
import com.skyanchor.anynote.ui.components.SectionSpacer
import com.skyanchor.anynote.ui.components.SettingRow
import com.skyanchor.anynote.ui.components.SpacerHeight
import com.skyanchor.anynote.ui.components.TagPill
import com.skyanchor.anynote.ui.components.noRippleClickable
import com.skyanchor.anynote.ui.loadAsync
import com.skyanchor.anynote.ui.rememberForegroundKey
import com.skyanchor.anynote.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通知与精确闹钟授权页（基线 §10、§20、§21.2、§24）。
 *
 * 权限状态来自系统而非本地数据库，所以用 rememberForegroundKey 在用户从系统设置页返回后重读。
 */
@Composable
fun NotificationSettingsScreen(env: AppEnv) {
    val settings = env.repository.settings
    val foreground = rememberForegroundKey()

    val notificationsGranted = remember(foreground) { env.notifications.permissionGranted }
    val exactAlarmAllowed = remember(foreground) { env.notifications.exactAlarmAllowed }
    val batteryExempt = remember(foreground) { env.notifications.ignoresBatteryOptimizations }
    val needsExactAlarmGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    var sound by remember { mutableStateOf(settings.soundEnabled) }
    var badge by remember { mutableStateOf(settings.badgeEnabled) }
    var preview by remember { mutableStateOf(settings.defaultPreviewEnabled) }
    var snooze by remember { mutableStateOf(settings.defaultSnoozeMinutes) }
    var snoozePicker by remember { mutableStateOf(false) }

    ScreenScaffold(title = "通知与精确闹钟", onBack = { env.router.pop() }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("系统权限")
            SectionSpacer(6)
            GlassCard(Modifier.fillMaxWidth(), corner = 20, contentPadding = PaddingValues(vertical = 4.dp)) {
                PermissionRow(
                    icon = AppIcons.Bell,
                    title = "通知权限",
                    granted = notificationsGranted,
                    grantedText = "已允许，到点会推送提醒",
                    deniedText = "未允许，所有提醒都不会送达",
                    actionText = "去开启",
                    onAction = { env.notifications.openSystemNotificationSettings() },
                )
                Hairline(Modifier.padding(horizontal = 16.dp))
                PermissionRow(
                    icon = AppIcons.Clock,
                    title = "精确闹钟",
                    granted = exactAlarmAllowed,
                    grantedText = if (needsExactAlarmGrant) "已授权，提醒按设定时间触发" else "系统版本无需额外授权",
                    deniedText = "未授权，提醒可能被合并延后数分钟",
                    actionText = "去授权",
                    onAction = { env.notifications.openExactAlarmSettings() },
                )
                Hairline(Modifier.padding(horizontal = 16.dp))
                PermissionRow(
                    icon = AppIcons.Shield,
                    title = "电池优化",
                    granted = batteryExempt,
                    grantedText = "已免除，设备休眠时闹钟照常唤醒",
                    deniedText = "未免除，休眠后提醒可能延后数分钟",
                    actionText = if (batteryExempt) "去应用设置" else "去设置",
                    onAction = { env.notifications.openBatteryOptimizationSettings() },
                )
            }
            SectionSpacer(10)
            if (!notificationsGranted) {
                InfoBanner(
                    "通知被拒绝时提醒事件仍会照常完成状态流转，只是不再弹出通知。" +
                        "重新允许后请回到「我的」页，调度会自动重建。",
                    tint = AppColors.Danger,
                )
            } else if (needsExactAlarmGrant && !exactAlarmAllowed) {
                InfoBanner(
                    "未授权精确闹钟时，系统只保证提醒落在一个时间窗口内送达，秒级精度会失效。",
                    tint = AppColors.Warning,
                )
            }
            SectionSpacer(18)
            ReminderSelfCheckSection(env, foreground)

            SectionSpacer(18)
            SectionHeader("提醒表现")
            SectionSpacer(6)
            GlassCard(Modifier.fillMaxWidth(), corner = 20, contentPadding = PaddingValues(vertical = 4.dp)) {
                SettingRow(
                    AppIcons.Tune,
                    "提醒声音",
                    subtitle = if (sound) "高优先级渠道，带声音与震动" else "静音渠道，只弹出不打扰",
                    checked = sound,
                    showChevron = false,
                    onCheckedChange = {
                        sound = it
                        settings.soundEnabled = it
                    },
                )
                Hairline(Modifier.padding(horizontal = 16.dp))
                SettingRow(
                    AppIcons.Circle,
                    "应用角标",
                    subtitle = "以一条常驻静默通知承载待处理数量",
                    checked = badge,
                    showChevron = false,
                    onCheckedChange = {
                        badge = it
                        settings.badgeEnabled = it
                        if (!it) env.notifications.cancel(NotificationHelper.BADGE_ID) else env.notifications.refreshBadge()
                    },
                )
                Hairline(Modifier.padding(horizontal = 16.dp))
                SettingRow(
                    AppIcons.Lock,
                    "锁屏显示正文",
                    subtitle = if (preview) "新建备忘录默认展示全文" else "新建备忘录默认只提示「有一条新提醒」",
                    checked = preview,
                    showChevron = false,
                    onCheckedChange = {
                        preview = it
                        settings.defaultPreviewEnabled = it
                    },
                )
            }

            SectionSpacer(18)
            SectionHeader("默认值")
            SectionSpacer(6)
            GlassCard(Modifier.fillMaxWidth(), corner = 20, contentPadding = PaddingValues(vertical = 4.dp)) {
                SettingRow(
                    AppIcons.Restore,
                    "默认稍后提醒",
                    subtitle = "通知上的「稍后」按钮与详情页共用",
                    value = "$snooze 分钟",
                    onClick = { snoozePicker = true },
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (snoozePicker) {
        ChoiceDialog(
            title = "默认稍后提醒",
            text = "只推迟当前这一条提醒事件，不会改动重复规则。",
            options = SettingsStore.SNOOZE_PRESETS.map { it to "$it 分钟后" },
            selected = snooze,
            onDismiss = { snoozePicker = false },
            onPick = {
                snooze = it
                settings.defaultSnoozeMinutes = it
            },
        )
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    granted: Boolean,
    grantedText: String,
    deniedText: String,
    actionText: String,
    onAction: () -> Unit,
) {
    val tint = if (granted) AppColors.Success else AppColors.Warning
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColors.PrimarySoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(11.dp))
                Text(title, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            }
            SpacerHeight(5)
            Text(
                if (granted) grantedText else deniedText,
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) AppColors.TextTertiary else tint,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (granted) {
            TagPill("已就绪", tint = AppColors.Success)
        } else {
            val shape = RoundedCornerShape(11.dp)
            Text(
                actionText,
                style = MaterialTheme.typography.labelLarge,
                color = AppColors.OnPrimary,
                modifier = Modifier
                    .clip(shape)
                    .background(AppColors.Primary)
                    .noRippleClickable(onAction)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 「提醒自检」：把"到点没响"从一句主观抱怨变成一条能定位的证据链（基线 §24）。
 *
 * 三条硬约束：
 * - 只报系统真正提供的读数。自启动与厂商后台管控没有任何公开读取接口，一律标"无法自动检测"，
 *   绝不用"已就绪"骗用户；
 * - 测试提醒走仓库的 `scheduleSelfTest`，即用户设真实提醒的同一条链路，
 *   否则测的是另一套代码，绿灯毫无意义；
 * - 文案不承诺秒级必达（基线 §32）。
 */
@Composable
private fun ReminderSelfCheckSection(env: AppEnv, foreground: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = env.repository.settings

    var reload by remember { mutableIntStateOf(0) }
    var testAt by remember { mutableLongStateOf(0L) }
    var testReported by remember { mutableStateOf(false) }
    var tickAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var busy by remember { mutableStateOf(false) }
    var gracePicker by remember { mutableStateOf(false) }
    var alarmFirst by remember { mutableStateOf(settings.preferAlarmClock) }
    var graceHours by remember { mutableStateOf(settings.missedGraceHours) }

    val panel = loadAsync(foreground + reload) {
        val status = env.repository.schedulerStatus()
        val events = env.repository.reminders.recentHealth(HEALTH_FEED_SIZE)
        SelfCheckPanel(
            plannedNextAt = status.plannedNextAt,
            systemNextAlarmAt = status.systemNextAlarmAt,
            alarmClockInUse = status.alarmClockInUse,
            pendingCount = env.repository.reminders.pendingActive().size,
            lastHealAt = events.firstOrNull { it.kind == HealthKind.SELF_HEAL.storage }?.recordedAt,
            events = events,
        )
    }.value

    val oem = remember { DeviceProfiles.current() }
    val autostart = remember(oem) { DeviceProfiles.autostartTarget(oem) }
    val battery = remember(oem) { DeviceProfiles.batteryTarget(oem) }
    val events = panel?.events.orEmpty()
    // 进行中的这一轮用倒计时刷新出的 tickAt；否则回退到实时，不能让 tickAt 停在排期前的旧值上
    val nowAt = if (testAt > 0L) tickAt else System.currentTimeMillis()
    val receiptDueAt = when {
        testAt > 0L -> testAt
        // 本轮没在路上：回看诊断记录里最近一次已到点、且用户还没回填的自检，提示行因此能跨重开 App 留在原位
        events.none { it.kind == HealthKind.RECEIPT_OK.storage || it.kind == HealthKind.RECEIPT_MISSING.storage } ->
            events.firstOrNull { it.kind == HealthKind.SELF_TEST.storage }?.scheduledAt
                ?.takeIf { it <= nowAt && nowAt - it <= RECEIPT_REPORT_WINDOW_MS }
        else -> null
    }
    val needReceiptReport = receiptDueAt != null && !testReported
    val countdown = if (testAt <= 0L) 0L else (testAt - tickAt).coerceAtLeast(0L)

    if (testAt > 0L) {
        LaunchedEffect(testAt) {
            testReported = false
            var left = testAt - System.currentTimeMillis()
            while (left > 0L) {
                tickAt = System.currentTimeMillis()
                delay(1_000L)
                left = testAt - System.currentTimeMillis()
            }
            // 自检窗口一过就重读：这一分钟里出现的失败必须当场摊到用户眼前
            reload++
        }
    }

    fun rebuildThen(message: String) {
        if (busy) return
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { env.repository.resyncAll() }
            busy = false
            env.state.invalidate()
            reload++
            env.toast(message)
        }
    }

    /**
     * 通知到底有没有弹出去，App 进程内是观测不到的——广播被厂商拦截时不抛任何异常。
     * 所以这一问不是形式主义：它是"划掉后台就不提醒"唯一能落到诊断表里的证据。
     */
    fun report(received: Boolean) {
        if (testReported) return
        testReported = true
        scope.launch {
            withContext(Dispatchers.IO) { env.repository.confirmSelfTest(received) }
            reload++
            env.toast(
                if (received) "已记录：本次到点正常到达"
                else "已记录为未到达。请优先检查「自启动与后台运行」与「省电策略」：${DeviceProfiles.manualHint(oem)}"
            )
        }
    }

    fun runSelfTest() {
        if (busy) return
        busy = true
        scope.launch {
            val planned = withContext(Dispatchers.IO) { env.repository.scheduleSelfTest() }
            busy = false
            if (planned == null) {
                env.toast("还没有可用分类，自检提醒没能创建")
                return@launch
            }
            testAt = planned
            env.state.invalidate()
            reload++
            env.toast("已排定。请现在把随记从后台划掉并熄屏等待")
        }
    }

    SectionHeader("提醒自检")
    SectionSpacer(6)
    GlassCard(Modifier.fillMaxWidth(), corner = 20, contentPadding = PaddingValues(vertical = 4.dp)) {
        SettingRow(
            icon = AppIcons.Shield,
            title = "设备识别",
            subtitle = "${Build.MANUFACTURER} / ${Build.BRAND} · ${Build.MODEL}",
            value = oem.label,
            showChevron = false,
        )
        Hairline(Modifier.padding(horizontal = 16.dp))
        SettingRow(
            icon = AppIcons.Play,
            title = "自启动与后台运行",
            subtitle = if (oem.restricted) {
                "系统没有读取接口，无法自动判断。" + DeviceProfiles.manualHint(oem)
            } else {
                "标准 Android 不需要额外授权；划掉后台后系统闹钟仍会投递"
            },
            value = if (autostart?.component != null) "去开启" else "应用信息",
            onClick = {
                val hasVendorPage = autostart?.component != null
                val jumped = hasVendorPage && DeviceProfiles.launch(context, autostart)
                env.toast(
                    when {
                        !hasVendorPage -> {
                            DeviceProfiles.openAppDetails(context)
                            "已打开应用信息页；标准 Android 不需要额外的自启动授权"
                        }
                        jumped -> "已跳到厂商设置页，请确认随记被允许自启动"
                        else -> "未能直达厂商页面，已退到应用信息页，请手动查找"
                    }
                )
            },
        )
        if (battery != null) {
            Hairline(Modifier.padding(horizontal = 16.dp))
            SettingRow(
                icon = AppIcons.Warning,
                title = "省电策略",
                subtitle = "后台被冻结时闹钟到点、通知却可能不投递",
                value = "去设置",
                onClick = {
                    val jumped = DeviceProfiles.launch(context, battery)
                    env.toast(
                        if (jumped) "已跳到${battery.label}，请把随记设为无限制"
                        else "未能直达，请手动查找省电策略设置"
                    )
                },
            )
        }
    }

    SectionSpacer(10)
    GlassCard(Modifier.fillMaxWidth(), corner = 20, contentPadding = PaddingValues(vertical = 12.dp)) {
        if (panel == null) {
            Text(
                "正在读取调度状态…",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            val planned = panel.plannedNextAt
            val alarm = systemAlarmReading(panel)
            ReadoutRow(
                "下一次提醒",
                when {
                    planned == null -> "暂无待办"
                    planned <= System.currentTimeMillis() -> "正在投递"
                    else -> "${listTimeText(planned)}（还有 ${humanSpan(planned - System.currentTimeMillis())}）"
                },
            )
            SpacerHeight(9)
            ReadoutRow("系统闹钟", alarm.value, alarm.tint)
            ReadoutHint(alarm.hint)
            SpacerHeight(9)
            ReadoutRow("待办事件", "${panel.pendingCount} 条")
            SpacerHeight(9)
            ReadoutRow("上次重建调度", panel.lastHealAt?.let { timeText(it) } ?: "尚无记录")
        }
    }

    SectionSpacer(10)
    GlassCard(Modifier.fillMaxWidth(), corner = 20, contentPadding = PaddingValues(vertical = 4.dp)) {
        SettingRow(
            icon = AppIcons.Bell,
            title = if (countdown > 0L) "自检提醒倒计时" else "发送测试提醒",
            subtitle = when {
                countdown > 0L -> "请现在把随记从后台划掉并保持熄屏，不要重新打开"
                testAt > 0L -> "预定时间已过。请在下方如实回填这一轮的结果"
                else -> "按你设置真实提醒的同一条路径投递，约 35 秒后到点"
            },
            value = if (countdown > 0L) "${countdown / 1000} 秒" else null,
            onClick = { if (countdown == 0L) runSelfTest() },
        )
        if (needReceiptReport) {
            Hairline(Modifier.padding(horizontal = 16.dp))
            SettingRow(
                icon = AppIcons.DoneAll,
                title = "这次自检提醒，收到了吗",
                subtitle = "App 无法自己观测通知是否弹出——被厂商拦截时不报任何错。这一步决定「划掉后台后不提醒」能否留下证据。",
                value = null,
                onClick = { report(true) },
            )
            Hairline(Modifier.padding(horizontal = 16.dp))
            SettingRow(
                icon = AppIcons.Warning,
                title = "没收到",
                subtitle = "记为未到达，并给出本机型需要检查的自启动与省电策略路径",
                value = null,
                tint = AppColors.Warning,
                onClick = { report(false) },
            )
        }
        Hairline(Modifier.padding(horizontal = 16.dp))
        SettingRow(
            icon = AppIcons.Restore,
            title = "立即重建全部调度",
            subtitle = "以数据库为准重算并重新注册，错过的提醒会在这一步补发",
            value = if (busy) "进行中" else null,
            onClick = { rebuildThen("已按数据库重建调度") },
        )
        Hairline(Modifier.padding(horizontal = 16.dp))
        SettingRow(
            icon = AppIcons.History,
            title = "逾期补发宽限期",
            subtitle = "错过多久之内仍补发「逾期提醒」，超出即归档为已过期",
            value = "$graceHours 小时",
            onClick = { gracePicker = true },
        )
        Hairline(Modifier.padding(horizontal = 16.dp))
        SettingRow(
            icon = AppIcons.Clock,
            title = "最高优先级闹钟",
            subtitle = "开启后状态栏会常驻一个闹钟图标，换取最近一次提醒不被合并延后",
            checked = alarmFirst,
            showChevron = false,
            onCheckedChange = {
                alarmFirst = it
                settings.preferAlarmClock = it
                rebuildThen(if (it) "已切换为最高优先级闹钟" else "已关闭闹钟图标，改用精确闹钟")
            },
        )
    }

    SectionSpacer(10)
    SectionHeader("失败记录")
    SectionSpacer(6)
    if (events.isEmpty()) {
        InfoBanner(
            if (panel == null) "读取中…" else "暂无记录。说明近期每次闹钟注册与投递都成功，或被正常处理。",
            tint = if (panel == null) AppColors.Success else AppColors.Primary,
        )
    } else {
        GlassCard(Modifier.fillMaxWidth(), corner = 20, contentPadding = PaddingValues(vertical = 6.dp)) {
            events.forEach { HealthRow(it) }
        }
    }
    SpacerHeight(4)
    Text(
        "以上记录只保留最近若干条，用于回答「闹钟到底注册上了没有」。" +
            "出现「系统侧闹钟已消失」说明到点的那一次被系统或厂商清理；" +
            "一条记录都没有、提醒却没响，说明系统连广播都没送达，需要确认自启动与后台运行权限。",
        style = MaterialTheme.typography.bodySmall,
        color = AppColors.TextTertiary,
    )

    if (gracePicker) {
        ChoiceDialog(
            title = "逾期补发宽限期",
            text = "重启、关机或厂商冻结导致的错过，会在下次重建时补发；超过这个时长就不再打扰。",
            options = SettingsStore.GRACE_PRESETS.map { it to "$it 小时" },
            selected = graceHours,
            onDismiss = { gracePicker = false },
            onPick = {
                graceHours = it
                settings.missedGraceHours = it
            },
        )
    }
}

/** 自检面板的一次性读数。查库和查系统闹钟都不允许发生在主线程。 */
private data class SelfCheckPanel(
    val plannedNextAt: Long?,
    val systemNextAlarmAt: Long?,
    val alarmClockInUse: Boolean,
    val pendingCount: Int,
    val lastHealAt: Long?,
    val events: List<HealthEvent>,
)

private data class Readout(val value: String, val tint: Color, val hint: String)

/**
 * 系统闹钟读数是**整机**的，不是本应用私有的：其它应用的闹钟同样会出现在这里。
 * 因此它只能作为"我们的注册到底在不在系统里"的证据，不能当成对账数字，措辞必须留有余地。
 */
private fun systemAlarmReading(panel: SelfCheckPanel): Readout {
    val planned = panel.plannedNextAt
    val system = panel.systemNextAlarmAt
    if (planned == null) {
        return Readout(
            if (system == null) "无" else "整机 ${timeText(system)}",
            AppColors.TextTertiary,
            "当前没有待触发事件，所以这一行不说明问题。",
        )
    }
    if (system == null) {
        return if (panel.alarmClockInUse) {
            Readout(
                "缺失",
                AppColors.Danger,
                "已按最高优先级请求注册，系统侧却没有任何闹钟登记：这一次注册失败了。",
            )
        } else {
            Readout(
                "不适用",
                AppColors.TextTertiary,
                "精确闹钟不出现在系统的闹钟读数里，这一行只在开启「最高优先级闹钟」时才有意义。",
            )
        }
    }
    if (!panel.alarmClockInUse) {
        return Readout(
            "整机 ${timeText(system)}",
            AppColors.TextTertiary,
            "该读数来自整机，可能属于其它应用，不能据此判断本应用的注册结果。",
        )
    }
    return if (kotlin.math.abs(system - planned) <= ALARM_MATCH_MS) {
        Readout("在 ${timeText(system)}", AppColors.Success, "与库里计划一致，闹钟确实挂在系统侧。")
    } else {
        Readout(
            "整机 ${timeText(system)}",
            AppColors.Warning,
            "与库里计划的下一次相差 ${humanSpan(kotlin.math.abs(system - planned))}" +
                "，可能是其它应用的闹钟占了读数，也可能这一次没有注册上。",
        )
    }
}

@Composable
private fun ReadoutRow(label: String, value: String, tint: Color = AppColors.TextPrimary) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
private fun ReadoutHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = AppColors.TextTertiary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 3.dp),
    )
}

@Composable
private fun HealthRow(event: HealthEvent) {
    val failed = event.kind in FAILURE_KINDS
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            timeText(event.recordedAt),
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextTertiary,
            modifier = Modifier.width(48.dp),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                event.kindLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) AppColors.Danger else AppColors.TextSecondary,
            )
            Text(event.reason, style = MaterialTheme.typography.labelSmall, color = AppColors.TextTertiary)
        }
    }
}

private val FAILURE_KINDS = setOf(
    HealthKind.ARM_FAILED.storage,
    HealthKind.DELIVERY_BLOCKED.storage,
    HealthKind.RECEIVER_INTERRUPTED.storage,
    HealthKind.MISSED_DROPPED.storage,
    HealthKind.ALARM_CLEARED.storage,
    HealthKind.RECEIPT_MISSING.storage,
)

/** 与仓库里回填自检结论的追溯窗口保持一致，太窄会让"上一轮未回填"的提示读不到计划记录。 */
private const val HEALTH_FEED_SIZE = 40

/** 到点后允许回填的时长：再晚用户就已经想不起这一轮到底响没响。 */
private const val RECEIPT_REPORT_WINDOW_MS = 30L * 60 * 1000

/** 允许的时间差：闹钟注册与读数之间总有秒级延迟，太严格会把正常状态报成异常。 */
private const val ALARM_MATCH_MS = 90_000L
