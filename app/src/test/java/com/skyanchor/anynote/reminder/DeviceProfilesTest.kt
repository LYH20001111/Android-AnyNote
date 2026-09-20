package com.skyanchor.anynote.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 机型判定的映射表。它是整个"按机型定位漏响"的入口：判错机型，自检面板就会把用户
 * 领到另一个厂商的设置页里，比没有跳转更糟。
 *
 * 只覆盖不触碰 Android 运行时的纯映射部分——组件名的正确性只能在真机验证。
 */
class DeviceProfilesTest {

    @Test
    fun brandBeatsManufacturerSoRedmiFollowsMiui() {
        assertEquals(Oem.MIUI, DeviceProfiles.detect("Xiaomi", "Redmi"))
        assertEquals(Oem.MIUI, DeviceProfiles.detect("Xiaomi", "POCO"))
    }

    @Test
    fun realmeIsNotFoldedIntoColorOs() {
        assertEquals(Oem.REALME, DeviceProfiles.detect("realme", "realme"))
        // 早期 realme 机型 MANUFACTURER 写作 "realme mobile"，用的是 ColorOS 的管控页面
        assertEquals(Oem.COLOROS, DeviceProfiles.detect("realme mobile", "RMX"))
    }

    @Test
    fun onePlusFollowsColorOs() {
        assertEquals(Oem.COLOROS, DeviceProfiles.detect("Oppo", "OnePlus"))
    }

    @Test
    fun detectionIgnoresCaseAndSurroundingSpaces() {
        assertEquals(Oem.MIUI, DeviceProfiles.detect("  XIAOMI ", "Redmi"))
        assertEquals(Oem.EMUI, DeviceProfiles.detect("HUAWEI", ""))
        assertEquals(Oem.ORIGINOS, DeviceProfiles.detect("", "iQOO"))
    }

    @Test
    fun unknownVendorsFallBackToStockAndroid() {
        assertEquals(Oem.OTHER, DeviceProfiles.detect("Google", "Pixel 8"))
        assertEquals(Oem.OTHER, DeviceProfiles.detect("", ""))
        assertEquals(Oem.OTHER, DeviceProfiles.detect("SONY", "XQ-DE54"))
    }

    @Test
    fun everyRestrictedOemHasAManualHint() {
        Oem.entries.filter { it.restricted }.forEach { oem ->
            assertTrue("$oem 缺少手工路径说明", DeviceProfiles.manualHint(oem).isNotBlank())
        }
    }
}
