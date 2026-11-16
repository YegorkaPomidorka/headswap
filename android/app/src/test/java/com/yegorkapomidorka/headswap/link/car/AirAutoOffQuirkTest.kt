package com.yegorkapomidorka.headswap.link.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirAutoOffQuirkTest {
    @Test
    fun remembersOnlyAirSessionsWithChannel() {
        assertTrue(AirAutoOffQuirk.remember(prev = false, air = true, channel = "20082120"))
        assertFalse("другая ГУ по воздуху сбрасывает", AirAutoOffQuirk.remember(prev = true, air = true, channel = "20082122"))
        assertEquals("кабель не трогает", true, AirAutoOffQuirk.remember(prev = true, air = false, channel = "20832125"))
        assertEquals("без STATISTIC_INFO не трогает", false, AirAutoOffQuirk.remember(prev = false, air = true, channel = null))
    }

    @Test
    fun ignoresBtAutoOffOnlyForVw20082120WhileWaitingAir() {
        assertTrue(AirAutoOffQuirk.ignoreBtAutoOff(lastAirHuVw20082120 = true, wifiBMode = true, autoStarted = true))
        assertFalse(AirAutoOffQuirk.ignoreBtAutoOff(lastAirHuVw20082120 = false, wifiBMode = true, autoStarted = true))
        assertFalse("не ждём по воздуху", AirAutoOffQuirk.ignoreBtAutoOff(lastAirHuVw20082120 = true, wifiBMode = false, autoStarted = true))
        assertFalse("запущено руками", AirAutoOffQuirk.ignoreBtAutoOff(lastAirHuVw20082120 = true, wifiBMode = true, autoStarted = false))
    }
}
