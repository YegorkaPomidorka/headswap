package com.yegorkapomidorka.headswap.link.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WlPolicyTest {
    @Test
    fun aclGoesToBoxOnlyInBoxMode() {
        assertEquals(AclCmd.BOX_AUTO, WlPolicy.aclCmd(WlMode.BOX, connected = true))
        assertNull(WlPolicy.aclCmd(WlMode.BOX, connected = false))      // отбоя по ACL у бокса нет
        for (m in WlMode.entries.filter { it != WlMode.BOX }) {
            assertEquals(AclCmd.CAR_AUTO, WlPolicy.aclCmd(m, connected = true))
            assertEquals(AclCmd.CAR_AUTO_OFF, WlPolicy.aclCmd(m, connected = false))
        }
    }

    @Test
    fun deviceForgottenOnlyAcrossBox() {
        assertTrue(WlPolicy.forgetOnSwitch(WlMode.NONE, WlMode.BOX))
        assertTrue(WlPolicy.forgetOnSwitch(WlMode.BOX, WlMode.P2P))
        assertFalse(WlPolicy.forgetOnSwitch(WlMode.P2P, WlMode.CAR_WIFI))
        assertFalse(WlPolicy.forgetOnSwitch(WlMode.NONE, WlMode.HOTSPOT))
        assertFalse(WlPolicy.forgetOnSwitch(WlMode.BOX, WlMode.BOX))
    }

    @Test
    fun carWifiOnlyInCarWifiOrUnset() {
        assertTrue(WlPolicy.useCarWifi(WlMode.NONE))
        assertTrue(WlPolicy.useCarWifi(WlMode.CAR_WIFI))
        assertFalse(WlPolicy.useCarWifi(WlMode.P2P))
        assertFalse(WlPolicy.useCarWifi(WlMode.HOTSPOT))
        assertFalse(WlPolicy.useCarWifi(WlMode.BOX))
    }

    @Test
    fun boxRearmDelays() {
        assertEquals(BOX_REARM_AFTER_SESSION_MS, WlPolicy.boxRearmMs(WlMode.BOX, wanted = true, afterSession = true, fails = 0))
        // первые три неудачи подряд - 4 с, дальше 10 с
        for (f in 1..3) assertEquals(BOX_REARM_AFTER_FAIL_MS, WlPolicy.boxRearmMs(WlMode.BOX, wanted = true, afterSession = false, fails = f))
        for (f in 4 until BOX_MAX_FAILS) assertEquals(BOX_REARM_SLOW_MS, WlPolicy.boxRearmMs(WlMode.BOX, wanted = true, afterSession = false, fails = f))
    }

    @Test
    fun boxNoRearmAfterMaxFails() {
        assertNull(WlPolicy.boxRearmMs(WlMode.BOX, wanted = true, afterSession = false, fails = BOX_MAX_FAILS))
        assertNull(WlPolicy.boxRearmMs(WlMode.BOX, wanted = true, afterSession = false, fails = BOX_MAX_FAILS + 1))
    }

    @Test
    fun boxNoRearmWhenStoppedOrOtherMode() {
        assertNull(WlPolicy.boxRearmMs(WlMode.BOX, wanted = false, afterSession = true, fails = 0))      // Стоп человеком
        assertNull(WlPolicy.boxRearmMs(WlMode.NONE, wanted = true, afterSession = true, fails = 0))      // старая кнопка: одноразово
        assertNull(WlPolicy.boxRearmMs(WlMode.P2P, wanted = true, afterSession = false, fails = 1))
    }

    @Test
    fun boxMuteHoldsWhileAclEventsAreFrequent() {
        val now = 10_000_000L
        assertTrue(WlPolicy.boxAutoStart(muted = false, prevSeenMs = now - 1_000, nowMs = now))      // «Стопа» не было
        assertFalse(WlPolicy.boxAutoStart(muted = true, prevSeenMs = now - 3_000, nowMs = now))      // тот же заход: глушим
        assertFalse(WlPolicy.boxAutoStart(muted = true, prevSeenMs = now - (BOX_MUTE_GAP_MS - 1), nowMs = now))
        assertTrue(WlPolicy.boxAutoStart(muted = true, prevSeenMs = now - BOX_MUTE_GAP_MS, nowMs = now))      // новая поездка
        assertTrue(WlPolicy.boxAutoStart(muted = true, prevSeenMs = 0L, nowMs = now))      // событий ещё не было
    }
}
