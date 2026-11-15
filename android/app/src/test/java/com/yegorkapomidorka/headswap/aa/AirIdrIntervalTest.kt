package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Test

class AirIdrIntervalTest {
    @Test
    fun longGopOnlyForVw20082120OverAir() {
        assertEquals(10, AirIdrInterval.seconds(vw20082120 = true, air = true))
        assertEquals("по кабелю как было", 1, AirIdrInterval.seconds(vw20082120 = true, air = false))
        assertEquals("другие ГУ по воздуху как было", 1, AirIdrInterval.seconds(vw20082120 = false, air = true))
        assertEquals(1, AirIdrInterval.seconds(vw20082120 = false, air = false))
    }
}
