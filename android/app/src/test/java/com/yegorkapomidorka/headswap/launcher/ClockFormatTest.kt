package com.yegorkapomidorka.headswap.launcher

import com.yegorkapomidorka.headswap.carapp.CarFormat
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockFormatTest {
    @Test fun twentyFourHour() {
        assertEquals("00:07", ClockFormat.format(LocalTime.of(0, 7), false))
        assertEquals("14:05", ClockFormat.format(LocalTime.of(14, 5), false))
    }

    @Test fun twelveHourWithoutAmPm() {
        assertEquals("12:07", ClockFormat.format(LocalTime.of(0, 7), true))
        assertEquals("9:30", ClockFormat.format(LocalTime.of(9, 30), true))
        assertEquals("12:00", ClockFormat.format(LocalTime.of(12, 0), true))
        assertEquals("11:59", ClockFormat.format(LocalTime.of(23, 59), true))
    }

    @Test fun hourMinuteMatchesFormatter() {
        for (h in 0..23) for (m in listOf(0, 9, 59)) for (twelve in listOf(false, true))
            assertEquals(ClockFormat.format(LocalTime.of(h, m), twelve), ClockFormat.format(h, m, twelve))
    }

    @Test fun arrivalClockTwelveHour() {
        // 13:13 UTC + 2 ч = 15:13
        val ms = (13 * 3600 + 13 * 60) * 1000L
        assertEquals("15:13", CarFormat.clock(ms, 7200))
        assertEquals("3:13", CarFormat.clock(ms, 7200, twelveHour = true))
    }
}
