package com.yegorkapomidorka.headswap.launcher

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusDateTest {
    private val day = LocalDateTime.of(2026, 11, 7, 9, 5)

    @Test fun dayMonthByDefault() {
        assertEquals("07.11", StatusReader.formatDate(day, mdy = false))
    }

    @Test fun monthDay() {
        assertEquals("11.07", StatusReader.formatDate(day, mdy = true))
    }
}
