package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Test

class AirIdrIntervalTest {
    @Test
    fun longGopOnlyForVwOverAir() {
        assertEquals(10, AirIdrInterval.seconds(vwAirLongGop = true, air = true))
        assertEquals("по кабелю как было", 1, AirIdrInterval.seconds(vwAirLongGop = true, air = false))
        assertEquals("другие ГУ по воздуху как было", 1, AirIdrInterval.seconds(vwAirLongGop = false, air = true))
        assertEquals(1, AirIdrInterval.seconds(vwAirLongGop = false, air = false))
    }
}
