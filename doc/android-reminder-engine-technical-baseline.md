# Android 提醒应用｜提醒引擎技术实现基线

> 版本：v0.1  
> 平台：Android Only  
> 文档性质：技术架构与可靠性基线  
> 核心目标：**用户设置提醒后，可以退出 App、App 进程被系统清理、设备锁屏/休眠时，仍由 Android 系统负责在计划时间触发提醒；App 本身不依赖常驻后台。**

---

## 1. 核心结论

本应用的提醒机制必须采用：

> **本地数据库 + 提醒规则引擎 + `AlarmManager` + `BroadcastReceiver` + `NotificationManager`**

而不能采用：

> `Service` 常驻后台 + `Timer` / `Handler` 持续等待。

Android 官方文档明确说明，`AlarmManager` 的 alarm 在应用生命周期之外运行，可以在 App 不运行、甚至设备处于休眠状态时触发事件；因此提醒不应该依赖 App 进程持续存在。

官方文档：
https://developer.android.com/develop/background-work/services/alarms

---

# 2. 目标场景

用户操作：

```text
打开 App
  ↓
创建备忘录
  ↓
设置：2026-10-20 18:30:00
  ↓
保存
  ↓
退出 App
  ↓
系统清理 App 后台进程
  ↓
手机锁屏
  ↓
18:30
  ↓
Android 系统触发 alarm
  ↓
BroadcastReceiver 被唤起
  ↓
发送系统 Notification
  ↓
用户看到提醒
```

**关键点：18:30 时不要求 App 进程原本一直存活。**

---

# 3. 总体架构

```text
┌──────────────────────────────┐
│            App UI            │
│ 创建 / 编辑 / 完成 / 跳过     │
│ 稍后提醒 / 删除 / 设置         │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│         Domain Layer         │
│ ReminderRule / Occurrence    │
│ Completion / Snooze / Skip   │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│        Local Database        │
│ Note / ReminderRule          │
│ ReminderOccurrence / File    │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│      ReminderScheduler       │
│ schedule / cancel / reschedule│
│ reconcile / rescheduleAll    │
└──────────────┬───────────────┘
               ↓
        Android System
               │
      ┌────────┴────────┐
      ↓                 ↓
AlarmManager       NotificationManager
      │                 │
      ↓                 ↓
BroadcastReceiver → System Notification
      │
      ↓
Occurrence 状态更新
```

---

# 4. 不允许的核心实现方式

## 4.1 不使用常驻 Service 等待提醒

禁止设计成：

```text
Foreground/Background Service
       ↓
while(true)
       ↓
检查当前时间
       ↓
到点发送通知
```

原因：

- 浪费电量；
- 后台进程可能被系统终止；
- 新版 Android 对缓存进程和后台行为有更严格的约束；
- 不符合本产品“App 不需要常驻”的目标。

Android 14 对进入 cached state 的 App 已加强后台资源使用约束，不能依赖 Activity 停止后的后台工作继续可靠执行。 

官方：
https://developer.android.com/about/versions/14/behavior-changes-all

## 4.2 不使用 WorkManager 作为“精确提醒”主机制

`WorkManager` 适合可延迟的后台工作，例如同步、上传、清理等；本产品的核心场景是用户指定明确时刻的提醒。

Android 官方明确把 exact alarm 用于依赖精确时间的用户功能，并区分 `AlarmManager` 的 exact / inexact 能力。

因此：

```text
提醒时间 → AlarmManager
后台业务任务 → WorkManager（如以后需要）
```

---

# 5. AlarmManager 的具体方案

## 5.1 一次性精确提醒

对于用户明确指定的具体时间，例如：

```text
2026-10-20 18:30:00
```

建议：

```text
AlarmManager.setExactAndAllowWhileIdle(...)
```

适用于需要较精确时间、同时希望设备进入 Doze/省电状态时仍尽量按计划执行的用户提醒。

Android 官方说明：

- `setExact()`：在接近指定时间触发；
- `setExactAndAllowWhileIdle()`：即使设备处于低功耗状态，也允许在接近指定时间触发；
- `setAlarmClock()`：系统将其视为高度关键、对用户可见的 alarm，但资源消耗更高。

官方：
https://developer.android.com/develop/background-work/services/alarms

### 本产品建议

默认采用：

```text
setExactAndAllowWhileIdle()
```

而不是 `setAlarmClock()`。

原因：

本产品是“备忘录提醒”，不是传统闹钟 App；没有必要把每一个提醒都升级为高可见度的系统 alarm-clock 类型。

---

# 6. 为什么不使用 Android 原生 repeating alarm

Android 4.4（API 19）及以上，repeat alarm 都是不精确的；因此不能依靠 `setRepeating()` 来实现你产品定义的复杂重复规则和秒级时间语义。

官方：
https://developer.android.com/develop/background-work/services/alarms

## 正确方案

不要：

```text
每周一 09:00
↓
系统 setRepeating()
```

而是：

```text
ReminderRule
每周一 09:00
       ↓
Rule Engine 计算下一次
       ↓
Occurrence
2026-10-26 09:00
       ↓
setExactAndAllowWhileIdle()
       ↓
下一次发生后，再计算下一次
```

也就是：

> **自定义规则由 App 计算；系统只负责执行下一次具体时间点。**

---

# 7. ReminderRule 与 ReminderOccurrence 必须分离

这是整个提醒系统最重要的数据设计。

## ReminderRule

描述：

> “什么时候应该发生？”

例如：

```text
每周一 09:00
```

## ReminderOccurrence

描述：

> “这一具体次提醒是什么时候发生？”

例如：

```text
2026-10-19 09:00
```

关系：

```text
ReminderRule
    │
    ├── Occurrence 2026-10-19 09:00
    ├── Occurrence 2026-10-26 09:00
    ├── Occurrence 2026-11-02 09:00
    └── ...
```

这样才能正确实现：

- 跳过本次；
- 稍后提醒；
- 完成本次；
- 完成后结束整个重复系列；
- 单次修改；
- 历史记录。

---

# 8. 系统 Alarm 只负责“下一次”

MVP 不建议一次把未来所有发生次数全部注册成 Alarm。

推荐：

```text
数据库保存完整 ReminderRule
             ↓
计算最近一次未来 Occurrence
             ↓
向 AlarmManager 注册一次
             ↓
Alarm 触发
             ↓
BroadcastReceiver 处理
             ↓
生成历史事件
             ↓
根据 Rule 计算下一次
             ↓
注册下一次 Alarm
```

这样可以降低调度数量，也更容易处理：

- 用户修改规则；
- 删除提醒；
- 跳过某次；
- Snooze；
- 时区变化；
- 系统重启；
- 权限变化。

---

# 9. Alarm 的唯一标识

每个系统 alarm 必须可以唯一定位到：

```text
noteId
reminderRuleId
occurrenceId
```

推荐 PendingIntent 使用稳定 requestCode / stable ID。

例如：

```text
alarmId = occurrenceId
```

Receiver 收到：

```text
EXTRA_OCCURRENCE_ID
```

然后：

```text
Receiver
  ↓
读取 occurrence
  ↓
检查 occurrence 当前状态
  ↓
如果仍有效 → 发通知
如果已完成/跳过/取消 → 不发
```

这样可以防止旧 alarm 在边界条件下错误弹出。

---

# 10. BroadcastReceiver 的职责

Receiver 只做轻量、快速的工作。

核心流程：

```text
AlarmManager
      ↓
AlarmReceiver.onReceive()
      ↓
读取 occurrenceId
      ↓
读取本地数据库
      ↓
验证状态
      ↓
创建 Notification
      ↓
NotificationManager.notify()
      ↓
更新 occurrence 状态
      ↓
计算下一次
      ↓
注册下一次 Alarm
```

如果未来需要比较重的工作，例如上传日志、同步云端，不要在 `onReceive()` 中长时间执行；可转交给 WorkManager。

---

# 11. 重要：不要假设 Receiver 触发后可以长时间运行

`BroadcastReceiver` 是“短生命周期”的系统组件。

所以 Receiver 的工作目标应该是：

> **尽快完成提醒投递和最小状态更新。**

例如：

```text
✅ 查询数据库
✅ 验证 occurrence
✅ 发布 Notification
✅ 写入必要状态
✅ 注册下一次 Alarm
```

不应该：

```text
❌ 下载大型文件
❌ 上传大量数据
❌ 长时间音频处理
❌ 长时间等待网络
❌ 持续执行复杂任务
```

---

# 12. Android 通知权限

Android 13（API 33）及以上，普通通知需要运行时权限：

```text
android.permission.POST_NOTIFICATIONS
```

如果用户拒绝，该 App 不能正常发送普通通知。

官方：
https://developer.android.com/develop/ui/compose/notifications/notification-permission

## 产品要求

创建第一条提醒之前，检查通知权限。

如果未授权：

```text
你的提醒需要通知权限

[允许通知]
```

如果用户拒绝：

```text
通知已关闭

已保存提醒，但系统可能无法显示通知。

[去系统设置开启]
```

不要让用户设置完大量提醒以后才第一次发现通知权限没有开启。

---

# 13. Notification Channel

Android 通知必须使用 Notification Channel。

建议第一版至少建立一个核心频道：

```text
提醒通知
```

建议固定 channelId：

```text
reminder_alerts
```

频道属性至少确定：

- 名称：提醒
- 描述：备忘录提醒通知
- 重要性：根据产品的提醒强度确定
- 声音：默认系统提醒音或产品自定义音
- 震动：由用户/系统控制

### 重要原则

Channel 一旦创建，部分关键行为由系统和用户设置共同控制；App 不能假设每次发送通知时都能动态修改 Channel 的所有属性。

因此通知渠道设计要在早期确定。

---

# 14. 通知内容

通知建议：

```text
标题：备忘录标题（如有）
正文：备忘录正文/内容摘要
```

例如：

```text
提交季度报告
今天 18:30 前完成财务数据核对，并发送给经理。
```

对于用户关闭通知正文预览的备忘录，可以：

```text
标题：提醒
正文：你有一个待处理提醒
```

注意：最终锁屏是否显示完整文本还受系统通知设置、锁屏隐私策略等影响。

---

# 15. 通知操作

建议通知支持：

```text
[完成]
[稍后提醒]
```

后续可以增加：

```text
[跳过本次]
```

通知动作不能假设一定可以把所有选项都同时显示在所有 Android 设备上；不同系统版本、厂商 UI 和通知布局可能有差异。

因此 App 内必须始终提供完整操作能力。

---

# 16. Snooze 实现

Snooze 不修改 ReminderRule。

例如：

```text
Rule：每周一 09:00
Occurrence：2026-10-19 09:00
```

用户点击：

```text
稍后提醒 30 分钟
```

变成：

```text
Occurrence
status = snoozed
snoozeUntil = 09:30
```

然后：

```text
09:30
↓
临时 Alarm
↓
再次通知
```

原来的规则仍然是：

```text
每周一 09:00
```

---

# 17. 自动重复提醒实现

例如：

```text
原始提醒：10:00
自动重复：每 10 分钟
```

不要修改长期 Rule。

只对当前 occurrence 建立临时链：

```text
Occurrence #100
10:00
 ↓
10:10
 ↓
10:20
 ↓
...
```

建议每个 occurrence 保存：

```text
autoRepeatEnabled
currentRepeatCount
maxRepeatCount（预留）
autoRepeatInterval
```

虽然第一版产品已经确定支持自动重复，建议代码结构预留最大次数/最长持续时间限制，以避免用户误设置造成无限通知和大量耗电。

---

# 18. 完成与跳过

## 完成一次

```text
Occurrence
status = completed
completedAt = 当前时间
```

然后计算下一次。

## 跳过一次

```text
Occurrence
status = skipped
skippedAt = 当前时间
```

然后直接根据原 Rule 计算下一次。

不能修改 Rule 本身。

---

# 19. 用户编辑提醒

任何涉及未来时间的修改，都必须执行：

```text
取消旧 Alarm
      ↓
更新数据库
      ↓
重新计算下一次 Occurrence
      ↓
注册新 Alarm
```

至少以下操作必须触发 reschedule：

- 修改提醒时间
- 修改重复规则
- 修改开始日期
- 修改结束日期
- 停用提醒
- 恢复提醒
- 删除提醒
- 恢复回收站中的提醒

---

# 20. 用户删除提醒

删除流程：

```text
用户删除
  ↓
ReminderRule / Note 标记为 trashed
  ↓
取消未来 Alarm
  ↓
未来 Occurrence 不再调度
```

如果从回收站恢复：

```text
恢复
 ↓
重新读取 Rule
 ↓
计算下一次未来 Occurrence
 ↓
重新注册 Alarm
```

---

# 21. 设备重启

设备重启后，必须有恢复调度策略。

推荐注册：

```text
RECEIVE_BOOT_COMPLETED
```

启动后：

```text
BOOT_COMPLETED
    ↓
BootReceiver
    ↓
读取所有 active ReminderRule
    ↓
找出未来需要提醒的规则
    ↓
计算下一次 Occurrence
    ↓
重新注册 Alarm
```

Android 官方文档将 `ACTION_BOOT_COMPLETED` 列为重新安排 alarm 的重要入口。

官方：
https://developer.android.com/develop/background-work/services/alarms

---

# 22. Android 15 Force Stop 必须特别处理

这是本产品需要明确告知开发团队的一条平台限制。

Android 15 调整了 package stopped state 行为：当用户对 App 执行 Force Stop 时，系统会取消该 App 的 pending intents；当用户再次通过直接或间接操作解除 stopped state 时，系统会向 App 发送 `ACTION_BOOT_COMPLETED`，给 App 一个重新注册 pending intents 的机会。

官方：
https://developer.android.com/about/versions/15/behavior-changes-all

因此：

```text
普通退出 / 进程被清理
        ↓
AlarmManager 负责调度
        ↓
提醒通常不依赖 App 常驻
```

但：

```text
用户主动 Force Stop
        ↓
Android 15
        ↓
pending intents 被取消
        ↓
在用户再次启动/解除 stopped state 前
不能保证原 Alarm 继续触发
```

### 结论

**不能向用户承诺“即使用户强制停止 App 后也一定提醒”。**

这是操作系统级限制，不是 App 后台技术可以绕过的事情。

---

# 23. Exact Alarm 权限

如果产品采用 exact alarm，则必须处理 Android 12+ 的 exact alarm 权限体系。

官方文档说明：

- Android 12+ 对 exact alarm 有专门权限要求；
- `SCHEDULE_EXACT_ALARM` 是用户授予的 special access；
- `USE_EXACT_ALARM` 为自动授予模式，但有更严格的受限使用场景与 Google Play 政策要求；
- `SCHEDULE_EXACT_ALARM` 可以被用户或系统撤销；
- 权限被撤销时，未来的 exact alarm 会被取消；
- 权限恢复后，App 应根据当前数据库状态重新 schedule。

官方：
https://developer.android.com/develop/background-work/services/alarms

---

# 24. Exact Alarm 权限处理流程

建议：

```text
用户创建精确提醒
        ↓
检查 canScheduleExactAlarms()
        ↓
 ┌───────────────┐
 │               │
已授权           未授权
 │               │
 ↓               ↓
正常调度      解释为什么需要权限
                 ↓
          引导系统“闹钟和提醒”
                 ↓
             用户授权
                 ↓
           重新 schedule
```

权限被撤销：

```text
检测到 revoked
   ↓
标记调度状态 unavailable
   ↓
停止尝试创建 exact alarm
   ↓
UI 显示风险
   ↓
用户恢复权限后重新 scheduleAll()
```

Android 官方还提供 `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` 广播，可用于在权限状态发生变化时重新安排需要的 exact alarms。

---

# 25. 为什么建议使用 SCHEDULE_EXACT_ALARM 作为产品设计基线

对于一个备忘录提醒 App，产品有很多“用户自定义时间”的功能：

```text
某年某月某日某时某分某秒
每天 09:00
每周一 18:30
每 3 天
每 2 周
每 3 月
```

需要根据规则在用户指定的时间点产生提醒。

因此技术基线可以优先围绕：

```text
SCHEDULE_EXACT_ALARM
+
setExactAndAllowWhileIdle()
```

进行设计。

最终发布到 Google Play 前，需要根据当时的 Play 政策重新确认 exact alarm permission 的申报与适用资格，不应在代码设计阶段假设某种权限永远不会受到政策变化影响。

---

# 26. 日期和时间计算必须独立成 Rule Engine

不能把以下逻辑分散在 UI、Receiver、Scheduler 各处。

应该建立：

```text
RecurrenceEngine
```

输入：

```text
ReminderRule
当前时间
时区
```

输出：

```text
NextOccurrence
```

---

# 27. Rule Engine 支持范围

根据当前产品基线，必须支持：

```text
一次性
每天
每周
每月
每年
工作日
自定义星期
每 N 天
每 N 周
每 N 月
每 N 年
```

边界：

```text
每月31日
↓
不存在31日的月份
↓
使用当月最后一天
```

例如：

```text
2月31日 → 2月28/29日
4月31日 → 4月30日
```

---

# 28. 时区处理

ReminderRule 必须保存明确的时间语义。

建议字段：

```text
localTime
zoneId
```

不要只保存一个 UTC timestamp 来表达所有重复规则。

例如：

```text
每天 09:00
America/Los_Angeles
```

在跨时区旅行时，需要根据产品定义重新计算下一个发生时间。

建议当前产品基线：

> **重复提醒默认跟随设备当前本地时区的“本地时间规则”。**

例如：

```text
每天09:00
```

用户旅行后仍理解成：

```text
当地每天09:00
```

这一规则必须统一应用于 Rule Engine 和 Scheduler。

---

# 29. 时间变化监听

需要考虑：

- 用户手动修改系统时间；
- 用户切换时区；
- 自动时区切换；
- 夏令时变化。

发生变化后：

```text
Time/Timezone changed
       ↓
重新读取 active rules
       ↓
取消受影响 Alarm
       ↓
重新计算 next occurrence
       ↓
重新 schedule
```

不能假设 Alarm 一旦创建就永远正确。

---

# 30. 省电 / Doze

Android 的低功耗机制会影响普通 inexact alarm。

本产品对于需要精确时间的提醒使用 exact alarm，并优先考虑：

```text
setExactAndAllowWhileIdle()
```

官方文档明确说明此 API 用于尽量在设备处于省电状态时也在接近指定时间触发。

但仍然不能把它理解为“任何硬件/系统状态下绝对零误差”。

---

# 31. 不要让“后台保活”成为产品依赖

本项目不应要求用户：

```text
锁定 App
开启自启动
常驻后台
关闭省电
开启某厂商特殊权限
```

才能得到基本提醒。

核心提醒应该首先依赖 Android 系统正式的 alarm/notification 机制。

但是在部分国产 ROM / 厂商系统上，后台策略可能额外影响组件行为，因此 QA 必须做 OEM 实机测试。

---

# 32. OEM 厂商兼容性

Android 设备不是单一系统环境。

至少应该测试：

```text
Google Pixel
Samsung
Xiaomi
OPPO
vivo
OnePlus
Huawei（如目标市场包含）
```

关注：

- Alarm 是否按时触发；
- 通知是否展示；
- 后台限制是否影响 Receiver；
- 电池优化是否产生额外行为；
- 锁屏通知是否展示正文；
- 声音是否正常；
- App 被用户从最近任务划掉后的行为。

不能把某一台 Pixel 测试通过，理解为“所有 Android 手机都已验证”。

---

# 33. App 被用户“划掉” vs Force Stop

QA 必须分成不同测试。

## A. 正常返回桌面

目标：

```text
App 进程随后可被杀
↓
Alarm 仍按系统机制存在
```

## B. 从最近任务列表划掉

需要在目标 Android 版本和目标 OEM 上实测。

## C. Settings → Force Stop

必须明确作为“系统强制停止”的特殊情况。

Android 15 会取消 pending intents，因此这里不能保证原有提醒继续工作，直到用户再次解除 stopped state。

官方：
https://developer.android.com/about/versions/15/behavior-changes-all

---

# 34. Reconciliation：提醒系统必须有“对账修复”

需要一个核心组件：

```text
ReminderReconciler
```

作用：

> **数据库中的应该有的提醒，与 Android 当前实际注册的 Alarm 进行对账。**

---

# 35. Reconciliation 触发场景

至少在以下场景执行：

```text
App 启动
手机重启
Exact Alarm 权限恢复
通知权限恢复
时区变化
系统时间变化
用户恢复回收站提醒
用户编辑提醒
用户批量修改提醒
App 更新后第一次启动
```

核心逻辑：

```text
DB active rules
       ↓
计算 expected alarms
       ↓
读取/确认当前 schedule 状态
       ↓
找差异
       ↓
取消过期 alarm
       ↓
补注册缺失 alarm
```

---

# 36. 数据库建议

## Note

```text
id
folderId
title
body
priority
status
notificationPreviewEnabled
createdAt
updatedAt
completedAt
deletedAt
```

## ReminderRule

```text
id
noteId
type
startAt
localTime
zoneId
weekdays
intervalValue
intervalUnit
dayOfMonth
endDate
completionMode
autoRepeatEnabled
autoRepeatInterval
autoRepeatMaxCount
enabled
createdAt
updatedAt
```

## ReminderOccurrence

```text
id
reminderRuleId
noteId
scheduledAt
triggeredAt
completedAt
snoozedUntil
skippedAt
status
notificationId
createdAt
updatedAt
```

---

# 37. 推荐的状态机

```text
                 ┌─────────────┐
                 │  SCHEDULED  │
                 └──────┬──────┘
                        ↓
                 ┌─────────────┐
                 │  TRIGGERED  │
                 └──────┬──────┘
                        │
             ┌──────────┼──────────┐
             ↓          ↓          ↓
        COMPLETED    SNOOZED     SKIPPED
                        │
                        ↓
                    TRIGGERED
```

如果用户一直不处理，可根据产品规则转为：

```text
EXPIRED
```

注意：

> Rule 的状态与 Occurrence 的状态不能混用。

---

# 38. 通知与历史记录的一致性

当 Alarm 触发：

```text
AlarmReceiver
      ↓
Occurrence.triggeredAt = now
      ↓
status = triggered
      ↓
发 Notification
```

用户点击完成：

```text
Notification Action
      ↓
Occurrence.completedAt = now
      ↓
status = completed
```

用户点击稍后：

```text
Notification Action
      ↓
status = snoozed
      ↓
snoozeUntil = xxx
      ↓
重新 schedule 临时 alarm
```

---

# 39. 通知点击后的 App 路由

通知 payload 至少包含：

```text
noteId
reminderRuleId
occurrenceId
action
```

例如：

```text
action = OPEN
action = COMPLETE
action = SNOOZE
```

这样可以实现：

```text
点击通知
 ↓
直接打开对应备忘录详情
```

而不是只能打开首页。

---

# 40. 多提醒规则

一个 Note 可以有多个 ReminderRule：

```text
Note #100
   │
   ├── Rule #1  2026/10/01 10:00
   ├── Rule #2  每周一 09:00
   └── Rule #3  每月15日 18:00
```

每个 Rule 有独立的：

- next occurrence；
- Alarm；
- Completion；
- Skip；
- Snooze；
- 历史事件。

不能因为一个 Rule 完成就把整个 Note 当成 completed，除非业务明确要求。

---

# 41. “完成”对 Note 的处理

当前产品定义：

> 完成后的备忘录从当前列表移走，进入历史。

但是对于多提醒 Note，需要额外明确：

### 推荐基线

```text
某个 occurrence 完成
        ↓
只完成这一 occurrence
        ↓
其他 active rules 不受影响
```

只有当：

```text
所有 active ReminderRule 都结束
```

或用户主动选择：

```text
完成整个备忘录
```

时，Note 才整体进入 completed。

如果未来需要完全不同的业务逻辑，可以修改这一层，而无需改变 Alarm 基础架构。

---

# 42. 精确到秒的实际边界

用户界面：

```text
2026-10-20 18:30:45
```

数据库：

```text
scheduledAt = 2026-10-20T18:30:45
```

Alarm：

```text
setExactAndAllowWhileIdle(...)
```

但产品文案必须避免：

> “保证绝对在 18:30:45.000000000 到达”。

应该定义为：

> **提醒时间支持精确到秒；实际交付由 Android 系统负责。**

Android 官方对 exact alarm 的描述也是“precise / nearly precise”，而不是无限理想化的硬实时机制。

---

# 43. Notification 通知内容长度

备忘录可能很长，但系统通知不适合直接承载无限正文。

建议：

```text
Notification Title
  ↓
最多显示合理长度的正文摘要
```

完整正文：

```text
点击通知 → App 详情页
```

如果产品要求“无需点进去就能看到提醒内容”，则应优先保障短文本完整显示，并允许系统自行折叠长文本。

---

# 44. 附件提醒

备忘录支持：

- 图片
- 文件
- 语音

但系统通知不要依赖这些附件来完成基础提醒。

基础提醒只需要：

```text
Title + Text
```

附件继续保存在本地数据库/文件存储中。

通知点击后进入详情页查看附件。

---

# 45. 本地存储

MVP 推荐：

```text
Room / SQLite
```

附件：

```text
App-specific storage
```

核心数据必须在本地持久化。

不能只把提醒放在内存中：

```text
❌ memory only
❌ SharedPreferences 保存完整提醒体系
```

建议：

```text
Room
├── notes
├── folders
├── reminder_rules
├── reminder_occurrences
└── attachments
```

---

# 46. 事务一致性

创建提醒时建议采用事务：

```text
DB transaction
  ↓
创建 Note
  ↓
创建 Rule
  ↓
创建/计算 Occurrence
  ↓
commit
```

提交成功后：

```text
Scheduler.schedule()
```

如果数据库提交失败，则不要留下系统 Alarm。

如果数据库成功但 schedule 失败，则启动 Reconciliation 负责补偿。

---

# 47. 调度失败处理

所有 schedule/cancel 操作都必须有错误处理。

例如：

```text
scheduleExactAlarm()
       ↓
成功 → scheduled
失败 → schedulerError
```

常见原因：

- Exact Alarm 权限没有授权；
- 通知权限没有授权；
- 时间已经过去；
- 参数异常；
- 系统状态变化；
- 数据库数据不一致。

UI 应能告诉用户：

```text
此提醒尚未成功启用
[检查提醒权限]
```

而不是静默失败。

---

# 48. 建议增加“提醒状态诊断”页面

开发阶段尤其重要，正式版本也可以考虑简化后保留。

例如：

```text
提醒健康状态

通知权限        ✓
精确闹钟权限    ✓
下一次提醒       2026/10/20 18:30
系统时区         America/Los_Angeles
调度状态         正常
```

这样用户发生“为什么没提醒”时，可以快速定位问题。

---

# 49. 测试矩阵

## 生命周期

必须测试：

```text
App前台
App进入后台
App进程被系统清理
从最近任务划掉
手机锁屏
设备休眠
设备重启
```

## 权限

```text
通知权限允许
通知权限拒绝
通知权限后来开启
Exact Alarm 已授权
Exact Alarm 被撤销
Exact Alarm 后来恢复
```

## 时间

```text
10秒后
1分钟后
精确到秒
跨天
跨月
跨年
2月28/29
每月31日
时区变化
夏令时
手动修改系统时间
```

## 重复

```text
每天
每周
每月
每年
工作日
自定义星期
每N天
每N周
每N月
每N年
```

## 用户动作

```text
完成
稍后10分钟
稍后30分钟
自定义Snooze
跳过本次
自动重复
编辑
删除
恢复
```

---

# 50. 关键自动化测试

Rule Engine 应建立纯逻辑单元测试，不依赖 Android 真实设备。

例如：

```text
Input:
每月31日 20:00
Current:
2026-02-01

Expected:
2026-02-28 20:00
```

再例如：

```text
每2周 周一 09:00
Current:
2026-10-05

Expected:
2026-10-19 09:00
```

所有规则计算都应该有大量确定性测试。

---

# 51. 真机测试比模拟器更重要

提醒类 App 不应该只依赖 Emulator 测试。

至少需要真实设备测试：

```text
Pixel
Samsung
小米
OPPO
vivo
OnePlus
```

特别测试：

```text
Doze
锁屏
省电模式
通知权限
后台限制
OEM ROM
重启
Force Stop
```

---

# 52. Google Play 发布前需要重新检查

在正式上架前，重新检查：

- target SDK 当前要求；
- exact alarm permission 的 Play 政策；
- POST_NOTIFICATIONS 权限；
- Notification Channel；
- 前台服务权限是否完全不需要；
- Data Safety 声明；
- 隐私政策；
- 附件访问权限；
- Android 最新版本行为变化。

Android 平台每个版本会继续发生行为变化，因此发布前不能只依据开发开始时的版本文档。

---

# 53. 推荐的 Android 项目模块划分

```text
app/

├── data/
│   ├── db/
│   ├── entity/
│   ├── dao/
│   └── repository/
│
├── domain/
│   ├── model/
│   ├── recurrence/
│   ├── reminder/
│   └── usecase/
│
├── scheduler/
│   ├── ReminderScheduler
│   ├── ReminderReconciler
│   ├── AlarmReceiver
│   ├── BootReceiver
│   └── PermissionMonitor
│
├── notification/
│   ├── NotificationFactory
│   ├── NotificationActionReceiver
│   └── NotificationChannels
│
└── ui/
    ├── home/
    ├── editor/
    ├── history/
    ├── category/
    ├── trash/
    └── settings/
```

---

# 54. 推荐关键组件职责

## ReminderRepository

负责：

```text
读写 Note
读写 Rule
读写 Occurrence
```

## RecurrenceEngine

负责：

```text
根据 Rule 计算下一次时间
```

## ReminderScheduler

负责：

```text
向 Android 注册/取消 Alarm
```

## ReminderReconciler

负责：

```text
DB 状态 ↔ 系统 Alarm 状态对账
```

## AlarmReceiver

负责：

```text
Alarm 到期 → 更新 occurrence + 发布通知
```

## NotificationActionReceiver

负责：

```text
完成 / Snooze / Skip / Open
```

## BootReceiver

负责：

```text
设备启动 → scheduleAll/reconcile
```

---

# 55. 推荐核心接口

```kotlin
interface ReminderScheduler {
    fun schedule(occurrenceId: String)
    fun cancel(occurrenceId: String)
    fun reschedule(occurrenceId: String)
    fun scheduleNext(ruleId: String)
    fun rescheduleAll()
}
```

```kotlin
interface RecurrenceEngine {
    fun nextOccurrence(
        rule: ReminderRule,
        after: Instant,
        zoneId: ZoneId
    ): ZonedDateTime?
}
```

```kotlin
interface ReminderReconciler {
    fun reconcile()
}
```

实际实现可根据项目架构调整，但这三个职责建议保持分离。

---

# 56. 创建提醒时的最终流程

```text
用户填写提醒
      ↓
验证内容
      ↓
验证提醒时间
      ↓
保存 DB
      ↓
创建 ReminderRule
      ↓
RecurrenceEngine
计算 next occurrence
      ↓
ReminderScheduler.schedule()
      ↓
成功？
 ┌──────┴──────┐
 ↓             ↓
是             否
 ↓             ↓
显示已开启     显示调度异常
```

---

# 57. App 启动时的最终流程

```text
App Start
   ↓
读取数据库
   ↓
检查通知权限
   ↓
检查 Exact Alarm 权限
   ↓
加载 active ReminderRule
   ↓
ReminderReconciler
   ↓
补齐缺失 Alarm
   ↓
清理已经失效 Alarm
   ↓
首页
```

---

# 58. Alarm 触发后的最终流程

```text
AlarmManager
    ↓
AlarmReceiver
    ↓
occurrenceId
    ↓
查询数据库
    ↓
状态是否仍有效？
 ┌───────────────┐
 ↓               ↓
有效             无效
 ↓               ↓
更新 triggered    不发送通知
 ↓
NotificationManager
 ↓
系统通知
 ↓
根据 Rule 计算下一次
 ↓
注册下一次 Alarm
```

---

# 59. 用户点击“完成”

```text
Notification Action
       ↓
occurrenceId
       ↓
查询 DB
       ↓
status = completed
completedAt = now
       ↓
取消当前相关 Alarm
       ↓
如果还有长期 Rule
       ↓
计算下一次
       ↓
schedule next
```

---

# 60. 用户点击“稍后提醒”

```text
Notification Action
       ↓
occurrenceId
       ↓
status = snoozed
snoozeUntil = xxx
       ↓
schedule temporary alarm
       ↓
原 ReminderRule 不改变
```

---

# 61. 用户点击“跳过本次”

```text
Notification Action
       ↓
occurrenceId
       ↓
status = skipped
       ↓
计算 Rule 的下一次 occurrence
       ↓
schedule next
```

---

# 62. 最终可靠性模型

把整个系统理解成：

```text
               ┌──────────────────┐
               │    本地数据库     │
               │ Source of Truth   │
               └────────┬─────────┘
                        ↓
                 ReminderRule
                        ↓
                 RecurrenceEngine
                        ↓
                 NextOccurrence
                        ↓
                 AlarmManager
                        ↓
                Android OS Timer
                        ↓
                BroadcastReceiver
                        ↓
                 NotificationManager
                        ↓
                    用户
```

其中：

> **数据库是真实状态。**

> **Alarm 是派生调度状态。**

> **Notification 是最终用户展示状态。**

三者不能混为一谈。

---

# 63. 核心验收标准

## 必须做到

- App 不在前台时可以提醒；
- App 进程被系统清理后，普通情况下不依赖进程常驻；
- 锁屏/设备休眠场景可以按系统能力触发；
- 用户指定的精确时间使用 exact alarm 路径；
- Android 13+ 通知权限正确处理；
- Android 12+ exact alarm 权限正确处理；
- 手机重启后恢复未来提醒；
- 修改提醒会取消旧 Alarm 并注册新 Alarm；
- 删除提醒会取消未来 Alarm；
- Snooze 不修改长期 Rule；
- Skip 只影响当前 occurrence；
- 重复规则自动计算下一次；
- 历史记录与提醒事件状态一致；
- 系统时间/时区变化后能够重新调度；
- 权限恢复后能够自动或引导重新调度；
- App 再次启动时执行 Reconciliation。

## 明确不承诺

- 用户对 App 执行 Force Stop 后，在 Android 15 上仍保证原 Alarm 持续生效；
- 所有厂商 ROM 的行为 100% 一致；
- 任何系统状态下绝对“0 秒误差”；
- 用户关闭通知后仍能展示普通通知。

---

# 64. 当前推荐技术基线

```text
语言：Kotlin
UI：Jetpack Compose
架构：现代 Android 分层架构
数据库：Room / SQLite
提醒：AlarmManager
精确提醒：setExactAndAllowWhileIdle()
系统回调：BroadcastReceiver
开机恢复：BOOT_COMPLETED
通知：NotificationManager + NotificationChannel
通知权限：POST_NOTIFICATIONS
精确闹钟权限：SCHEDULE_EXACT_ALARM（发布前再次按 Play 政策确认）
后台可延迟任务：WorkManager（非核心提醒）
状态管理：ReminderRule + ReminderOccurrence
调度恢复：ReminderReconciler
```

---

# 65. 最终结论

这个 App 的 Android 提醒能力不应该被理解成：

> “App 即使退出，也要想办法偷偷在后台跑起来。”

而应该理解成：

> **“App 在用户设置提醒时，把一个未来的任务交给 Android 系统；到时间由 Android 系统唤起必要的组件并显示通知。”**

因此核心架构是：

```text
本地数据库
    ↓
提醒规则
    ↓
下一次提醒事件
    ↓
AlarmManager
    ↓
BroadcastReceiver
    ↓
NotificationManager
    ↓
用户
```

对于当前产品需求，**AlarmManager + exact alarm + 本地 Notification 是主链路；Room 是数据真相；RecurrenceEngine 是规则计算核心；ReminderReconciler 是可靠性保险层。**

同时，Android 15 的 Force Stop 行为意味着：**系统主动强制停止 App 是必须明确作为平台例外处理的场景，不能通过普通后台技术绕过。**

---

# 66. 官方参考资料

1. Android Developers — Schedule alarms  
   https://developer.android.com/develop/background-work/services/alarms

2. Android Developers — Notification runtime permission  
   https://developer.android.com/develop/ui/compose/notifications/notification-permission

3. Android Developers — Android 14 behavior changes  
   https://developer.android.com/about/versions/14/behavior-changes-all

4. Android Developers — Android 15 behavior changes  
   https://developer.android.com/about/versions/15/behavior-changes-all

> 发布前应重新检查 Android 最新版本行为变化与 Google Play 对 exact alarm 等权限的最新政策要求。
