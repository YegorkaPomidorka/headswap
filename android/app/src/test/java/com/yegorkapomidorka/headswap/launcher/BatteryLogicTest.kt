package com.yegorkapomidorka.headswap.launcher

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryLogicTest {
    @Test fun percentFromLevelAndScale() {
        assertEquals(87, BatteryLogic.percent(87, 100))
        assertEquals(50, BatteryLogic.percent(127, 254))
        assertEquals(0, BatteryLogic.percent(0, 100))
        assertEquals(100, BatteryLogic.percent(100, 100))
        // округление, а не отбрасывание дроби
        assertEquals(67, BatteryLogic.percent(2, 3))
    }

    @Test fun percentUnknownWhenNoData() {
        assertEquals(-1, BatteryLogic.percent(-1, 100))
        assertEquals(-1, BatteryLogic.percent(50, 0))
        assertEquals(-1, BatteryLogic.percent(-1, -1))
    }

    @Test fun percentIsClamped() {
        assertEquals(100, BatteryLogic.percent(120, 100))
    }

    @Test fun chargingOnChargeOrFullWhilePlugged() {
        assertTrue(BatteryLogic.charging(BatteryManager.BATTERY_STATUS_CHARGING, 1))
        assertTrue(BatteryLogic.charging(BatteryManager.BATTERY_STATUS_CHARGING, 0))
        assertTrue(BatteryLogic.charging(BatteryManager.BATTERY_STATUS_FULL, BatteryManager.BATTERY_PLUGGED_AC))
        assertFalse(BatteryLogic.charging(BatteryManager.BATTERY_STATUS_FULL, 0))
        assertFalse(BatteryLogic.charging(BatteryManager.BATTERY_STATUS_DISCHARGING, 0))
        assertFalse(BatteryLogic.charging(BatteryManager.BATTERY_STATUS_NOT_CHARGING, BatteryManager.BATTERY_PLUGGED_USB))
        assertFalse(BatteryLogic.charging(BatteryManager.BATTERY_STATUS_UNKNOWN, 0))
    }

    @Test fun fillSteps() {
        assertEquals(0f, BatteryLogic.fill(-1), 0f)
        assertEquals(0f, BatteryLogic.fill(0), 0f)
        // почти пустой всё равно виден: минимум одно деление
        assertEquals(1 / 8f, BatteryLogic.fill(1), 0f)
        assertEquals(1 / 8f, BatteryLogic.fill(6), 0f)
        assertEquals(0.5f, BatteryLogic.fill(50), 0f)
        assertEquals(7 / 8f, BatteryLogic.fill(88), 0f)
        assertEquals(1f, BatteryLogic.fill(100), 0f)
        assertEquals(1f, BatteryLogic.fill(130), 0f)
    }
}
