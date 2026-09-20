# AnyNote 随记 · 开发计划

> 输入：`doc/memo-reminder-app-design-development-baseline.md`（产品与业务基线，权威）
> 输入：`doc/AnyNote_UI.png`（UI 视觉与页面结构参考）
> 状态：本文件是执行计划，业务规则以基线文档为准；两者冲突时以基线为准并在本文标注。

---

## 1. 基线与 UI 的差异处理

| 差异点 | 基线 | UI 图 | 本计划采用 |
|---|---|---|---|
| 底部 Tab | 提醒 / 历史 / 设置 | 首页 / 日历 / 历史 / 我的 | 按 UI 四 Tab，设置收进"我的" |
| 日历视图 | 未列 | 有（月历 + 当日日程） | 实现（P1，纯本地投影，不做系统日历同步） |
| 回收站清理 | 不自动清空 | "保留30天，之后自动删除" | **按基线：不自动删除**，文案改为手动清理说明 |
| 首页分组 | 今天/明天/更后面/逾期 | 混合列表 + 分类 chips | 两者结合：搜索 + 分类 chips + 时间分组 |

---

## 2. 技术选型与约束

**平台**：Android 原生（仓库已是 Android Studio + Gradle 9.5 + AGP 9.3.3 + Kotlin 2.2.10 + Compose 工程）。
基线 §30 建议 iOS+Android 用 Flutter，但当前工程是 Android；因此第一版落地 Android，并把**提醒规则引擎与通知调度抽象成与 UI/平台无关的独立层**，为后续 iOS 复用留出边界。

**关键决定：零新增依赖。**
不引入 Room/KSP、Navigation-Compose、DataStore、WorkManager、Coil。理由：
- AGP 9.3.3 + Kotlin 2.2.10 + KSP 版本三角匹配是当前最大的构建风险，而基线 §22/§38 的核心风险在"时间"，不在 ORM。
- 全部能力框架自带即可：`SQLiteOpenHelper`（数据）、`AlarmManager`（精确闹钟）、`NotificationManager`（通知/动作/角标）、`SharedPreferences`（设置）、Compose 状态驱动路由。

**minSdk 24 → 26**：`java.time` 在 API 26+ 原生可用。基线 §25/§31 把时区、夏令时、月末、闰年列为一级风险，用 `ZonedDateTime` 实现远比 `Calendar` 可靠，而核心库脱糖（desugar_jdk_libs）会破坏"零新增依赖"。API 26（Android 8.0）覆盖 2026 年绝大多数设备。

---

## 3. 架构分层

```
UI (Compose screens)            ← 只展示与收集意图
   ↓
ViewModel / State               ← 投影首页/日历/历史所需视图模型
   ↓
ReminderCoordinator             ← 唯一状态流转入口（trigger/complete/snooze/skip/auto-repeat）
   ↓
ReminderScheduler               ← 数据库 → 系统闹钟（取消/注册，永不只写库）
RecurrenceEngine (pure kotlin)  ← 规则 → 未来触发时间点
   ↓
Repository → DAO → SQLite       ← 唯一事实来源
   ↓
NotificationHelper              ← 渠道/正文预览/动作/角标
```

不可破坏的规则（基线 §38）全部落在 `ReminderCoordinator` + `ReminderScheduler` 这一层：
1. Note / ReminderRule / ReminderOccurrence 三表分离，一条 Note 多条 Rule。
2. Snooze 只改当前 Occurrence 的 `snoozed_until`，不触碰 Rule。
3. Skip 只把当前 Occurrence 置 `skipped`，后续 occurrence 不受影响。
4. "完成本次" vs "结束整个系列" 是两个动作，由 `rule.completion_mode` 决定。
5. 任何 Rule/Note 变更：`cancelAlarms → 写库 → recompute → registerAlarms`，禁止只写库。
6. 删除 → 取消全部未来通知；恢复 → 重新调度。
7. 开机 / 权限恢复 / App 启动 → `resyncAll()` 从数据库重建闹钟。

---

## 4. 数据模型（SQLite）

`folders` · `notes` · `attachments` · `reminder_rules` · `reminder_occurrences` · `meta`
字段直接对应基线 §3.1–§3.4，全部使用 TEXT 稳定 UUID 主键（预留云同步 §29），时间为 epoch millis + IANA 时区字符串。
`notes.status` = active/completed/trashed；`reminder_occurrences.status` = scheduled/triggered/snoozed/completed/skipped/expired/cancelled（基线 §3.4 状态机 §35-D）。

---

## 5. 提醒规则引擎

`RecurrenceEngine.nextOccurrences(rule, fromInstant, zone, horizon)` 返回按时间升序的触发点。
- 类型：once / daily / weekly / monthly / yearly / weekdays / custom_weekdays / interval(day|week|month|year)
- 月末不存在日期 → 当月最后一天（§6.2），2/29 → 不存在该日期的年份跳过（§6.3）
- `end_date` 到达即停止产出（§6.1）
- 时间语义 = 用户本地墙钟时间（§25），跨时区仍按当地 09:00
- 精度到秒，UI 默认按分钟展示（§5.2）
- 纯 JVM，可单测；单测覆盖基线 §31 时间/重复矩阵

## 6. 调度与通知

- 全库最早的一次用 `setAlarmClock`（Doze 下唯一不被合并延后），其余用 `setExactAndAllowWhileIdle`；无 `SCHEDULE_EXACT_ALARM` 时降级 `setWindow`（§21.2、§24.1）
- 每条规则物化 12 个未来 occurrence，但只注册最早 3 个；6 小时看门狗闹钟负责推进前沿、补发错过的事件、清理诊断记录
- 每一次注册/投递失败都写入 `scheduler_health`，设置页「提醒自检」据此定位到具体机型
- 渠道 `reminders`：横幅/锁屏/通知中心/声音/角标（§10.2），不做全屏（§21.3）
- 正文预览按 `note.notification_preview_enabled` 关闭时显示"你有一个新的提醒"（§10.3）
- Actionable：完成 / 稍后提醒（默认 10 分钟）/ 跳过本次；App 内提供 10/30/60 分钟与自定义（§9、§11）
- 自动重复：仅对当前 occurrence 追加闹钟，不推进长期规则（§8）
- 通知总开关关闭 = 真正撤下全部闹钟（`cancelAll`），不再出现"暂停提醒却仍按点唤醒、状态栏仍挂闹钟图标"；关闭期间看门狗仍续命，重新打开时由冷启动全量重建恢复
- 看门狗自愈顺带做一次"闹钟被清掉"检测：库里已到点未兑现 + 系统侧 `nextAlarmClock` 为空 → 写 `alarm_cleared` 诊断。这是"划掉后台后不提醒"唯一能机读的证据（仅在开启最高优先级闹钟时判定，精确闹钟不进这个读数）
- 自检提醒到点后可回填「收到 / 没收到」，落成 `receipt_ok` / `receipt_missing`。通知被厂商静默丢弃时进程内不可观测，用户这一次点击是唯一的证据来源

---

## 7. 页面清单（对照 UI 图）

1. 欢迎页 `SplashWelcome` — 开始使用 + 通知权限引导
2. 首页 `Home` — 搜索、分类 chips、今天/明天/更后面/逾期分组、FAB 新建、底部 Tab
3. 日历 `Calendar` — 月历 + 选中日日程
4. 新增/编辑备忘录 `NoteEditor` — 内容、可选标题、分类、提醒列表、更多设置（优先级/完成状态/正文预览/附件）
5. 提醒规则编辑器 `ReminderRuleEditor` — 单次/重复、时间、重复规则、结束条件、完成方式、自动重复
6. 备忘录详情 `NoteDetail` — 完成 / 稍后提醒 / 跳过本次 / 编辑 / 删除
7. 分类管理 `FolderManage` — 默认分类、新建、重命名、删除（删除→移至"其他"）
8. 历史记录 `History` — 已完成 / 已归档、搜索与筛选
9. 回收站 `Trash` — 恢复 / 永久删除 / 清空
10. 我的 `Mine` + 通知设置 `NotificationSettings` — 权限状态、默认行为、数据、关于

---

## 8. 里程碑

| 里程碑 | 内容 | 验收 |
|---|---|---|
| M0 | 计划文档 + 构建配置调整 | Gradle 配置通过 |
| M1 | 设计系统与组件库 | 主题渲染正确 |
| M2 | 数据层 | 建表/CRUD/软删除可用 |
| M3 | 规则引擎 + 单测 | 时间矩阵全绿 |
| M4 | 调度 + 通知 + 协调器 | 后台/重启可触达，修改无残留通知 |
| M5 | 全部页面 | 基线 §34 验收项逐条可走通 |
| M6 | 构建验证 | `assembleDebug` 产出 APK，单测通过 |

## 9. 已知取舍

- 附件：MVP 存路径与元数据，图片用 `BitmapFactory` 本地解码显示，不引入图片库；语音/文件走系统选择器与播放器。
- iOS：本计划不覆盖，但引擎与调度接口按可移植边界设计。
- 导出/备份：基线列为 MVP 后期/预留，本版不实现。
- 数据库版本回退：`onDowngrade` 只回写 `user_version` 不清库，回退后会出现"列还在、版本号旧"的不一致。清库会连带删掉用户数据，代价远大于不一致。
- 设置里"强行停止"：闹钟与广播注册一并被清，且之后 `BOOT_COMPLETED` 不再送达，平台没有自愈通道。只能等用户重新打开 App，UI 文案如实说明。
- 冷启动 `bootstrap()` 全量重建与到点广播可能并发写库：WAL + busy 重试已吸收，串行化写库需要重排整棵依赖图，收益不抵改动面。
- 逾期判定留在 `reminder_occurrences` 的 SQL 条件里，不另抽纯 Kotlin 函数：多一份实现只会多一处和数据库漂移的可能。
