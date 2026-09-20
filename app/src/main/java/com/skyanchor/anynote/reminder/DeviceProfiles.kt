package com.skyanchor.anynote.reminder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 厂商省电与自启动管控的机型适配（基线 §24）。
 *
 * 为什么必须单独处理：AlarmManager 注册的闹钟在原生 Android 上确实不依赖 App 进程存活，
 * 但国产 ROM 会在系统层拦截"后台启动"，被拦下时闹钟到点、广播却不投递，而且不抛任何异常。
 * 这是"划掉后台就不提醒"最主要的机型相关成因，而 Android 没有提供读取该状态的 API。
 *
 * 所有组件名都是尽力而为：它们随 ROM 版本漂移，且多数私有页面没有 intent-filter。
 * 因此 [launch] 一律先校验存在性、失败退回应用详情页，并且调用方必须把"没能直达"告诉用户。
 */
enum class Oem(val label: String, val restricted: Boolean) {
    MIUI("小米 / 红米", true),
    EMUI("华为 / 荣耀", true),
    COLOROS("OPPO / 一加", true),
    REALME("realme", true),
    ORIGINOS("vivo / iQOO", true),
    SAMSUNG("三星", true),
    SMARTISAN("锤子", true),
    LETV("乐视", true),
    MEIZU("魅族", true),
    ZTE("中兴 / 努比亚", true),
    OTHER("标准 Android", false),
}

/** 一个可跳转的厂商设置页；[component] 为 null 表示该机型没有已知的直达页面。 */
data class SettingsTarget(val label: String, val component: ComponentName?)

object DeviceProfiles {

    /** 前缀按小写匹配。品牌优先于厂商：红米新机的 MANUFACTURER 仍写作 Xiaomi，但管控入口按品牌走。 */
    private val TABLE: List<Pair<List<String>, Oem>> = listOf(
        listOf("xiaomi", "redmi", "poco", "blackshark", "mint") to Oem.MIUI,
        listOf("huawei", "honor") to Oem.EMUI,
        listOf("oppo", "oneplus", "realme mobile") to Oem.COLOROS,
        listOf("realme") to Oem.REALME,
        listOf("vivo", "iqoo", "jovi") to Oem.ORIGINOS,
        listOf("samsung", "sec") to Oem.SAMSUNG,
        listOf("smartisan", "dingtalk") to Oem.SMARTISAN,
        listOf("letv", "lemax", "lemall", "esmart") to Oem.LETV,
        listOf("meizu", "mx") to Oem.MEIZU,
        listOf("zte", "nubia") to Oem.ZTE,
    )

    fun detect(manufacturer: String, brand: String): Oem {
        for (key in listOf(brand, manufacturer)) {
            val normalized = key.lowercase().trim()
            if (normalized.isEmpty()) continue
            val match = TABLE.firstOrNull { entry -> entry.first.any { normalized.startsWith(it) } }
            if (match != null) return match.second
        }
        return Oem.OTHER
    }

    fun current(): Oem = detect(Build.MANUFACTURER, Build.BRAND)

    /** 自启动 / 关联启动管理页。 */
    fun autostartTarget(oem: Oem = current()): SettingsTarget? = when (oem) {
        Oem.MIUI -> component("小米 · 自启动管理", "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity")
        Oem.EMUI -> component("华为 / 荣耀 · 应用启动管理", "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        Oem.COLOROS -> component("OPPO / 一加 · 自启动管理", "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupColorosSettingActivity")
        Oem.REALME -> component("realme · 自启动管理", "com.realme.securitycheck",
            "com.realme.securitycheck.StartupAppManageActivity")
        Oem.ORIGINOS -> component("vivo / iQOO · 后台高耗电", "com.vivo.settings",
            "com.vivo.settings.Settings\$HighPowerApplicationsActivity")
        Oem.SAMSUNG -> component("三星 · 始终允许运行", "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity")
        Oem.SMARTISAN -> component("锤子 · 自启动管理", "com.android.settings",
            "com.android.settings.SmartSafeModeAppPermission")
        Oem.LETV -> component("乐视 · 自启动管理", "com.letv.android.letvsafe",
            "com.letv.android.letvsafe.AutobootManageActivity")
        Oem.MEIZU -> component("魅族 · 安全中心", "com.meizu.safe",
            "com.meizu.safe.security.SHORTCUTMainActivity")
        Oem.ZTE -> component("中兴 · 后台管理", "com.zte.security",
            "com.zte.security.StartupManageActivity")
        Oem.OTHER -> null
    }

    /** 省电策略页：与自启动分开，因为用户经常只需要改其中一项。 */
    fun batteryTarget(oem: Oem = current()): SettingsTarget? = when (oem) {
        Oem.MIUI -> component("小米 · 省电策略", "com.miui.powerkeeper",
            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
        Oem.EMUI -> component("华为 / 荣耀 · 电量启动管理", "com.huawei.systemmanager",
            "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
        Oem.COLOROS -> component("OPPO / 一加 · 耗电保护", "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        Oem.ORIGINOS -> component("vivo / iQOO · 省电与后台", "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
        else -> null
    }

    /** 直达失败时给用户的手工路径说明。 */
    fun manualHint(oem: Oem = current()): String = when (oem) {
        Oem.MIUI -> "设置 → 应用设置 → 应用管理 → 随记 → 自启动，并把省电策略设为「无限制」"
        Oem.EMUI -> "设置 → 应用 → 应用启动管理 → 随记 → 改为「手动管理」，三项全部允许"
        Oem.COLOROS, Oem.REALME -> "设置 → 应用 → 自启动项管理 → 允许随记，并在耗电保护里关闭「智能后台管控」"
        Oem.ORIGINOS -> "i 管家 → 应用管理 → 权限管理 → 后台高耗电允许，并在电池里关闭后台冻结"
        Oem.SAMSUNG -> "设置 → 电池 → 将应用置于休眠状态 → 把随记移出休眠名单"
        Oem.OTHER -> "系统未提供读取接口；如仍出现漏响，请在系统设置里允许本应用自启动并保持后台运行"
        else -> "请在系统设置里允许本应用自启动，并关闭针对本应用的后台冻结"
    }

    /**
     * 跳转厂商设置页。**只有真正拉起厂商组件才返回 true**；
     * 没有已知页面、解析不到、或跳转抛异常时，一律退回应用详情页并返回 false，
     * 由调用方如实告诉用户"没能直达"——把降级说成成功，用户就会以为权限已经开好了。
     */
    fun launch(context: Context, target: SettingsTarget?): Boolean {
        val component = target?.component ?: return false
        if (!isResolvable(context, component)) return false
        val started = runCatching {
            context.startActivity(
                Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!started) openAppDetails(context)
        return started
    }

    fun openAppDetails(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    /**
     * 显式组件是否存在。用 `resolveActivity(MATCH_DEFAULT_ONLY)` 判断会出错：厂商的设置页多数
     * 没有带 DEFAULT 类别的 intent-filter，确实存在的组件也会被判定为不可达，于是每次跳转都无谓地
     * 退到应用详情页。`getActivityInfo` 才是在问"这个类在不在、能不能拉起"。
     *
     * Android 11+ 的包可见性同样适用：未登记在 manifest `<queries>` 里的第三方包一律查不到。
     */
    private fun isResolvable(context: Context, component: ComponentName): Boolean = runCatching {
        context.packageManager.getActivityInfo(component, 0)
        true
    }.getOrDefault(false)

    private fun component(label: String, pkg: String, cls: String): SettingsTarget =
        SettingsTarget(label, ComponentName.unflattenFromString("$pkg/$cls"))
}
