package com.yegorkapomidorka.headswap.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NewYearLogicTest {
    private fun at(s: String) = NewYearMode.at(LocalDateTime.parse(s))

    @Test fun beforeCountdownIsNone() {
        assertEquals(NewYearMode.None, at("2026-12-31T22:59:59"))
        assertEquals(NewYearMode.None, at("2026-06-15T12:00:00"))
    }

    @Test fun countdownStartsAt2300() {
        val c = at("2026-12-31T23:00:00") as NewYearMode.Countdown
        assertEquals(3600L, c.remainingSec)
        assertEquals("60:00", c.text)
        assertEquals(0f, c.progress, 0f)
    }

    @Test fun countdownLastSecond() {
        val c = at("2026-12-31T23:59:59") as NewYearMode.Countdown
        assertEquals(1L, c.remainingSec)
        assertEquals("00:01", c.text)
        assertTrue(c.progress > 0.99f)
    }

    @Test fun greetingFromMidnightToJan5() {
        assertEquals(NewYearMode.Greeting(2027), at("2027-01-01T00:00:00"))
        assertEquals(NewYearMode.Greeting(2027), at("2027-01-05T23:59:59"))
    }

    @Test fun greetingEndsJan6() {
        assertEquals(NewYearMode.None, at("2027-01-06T00:00:00"))
    }

    @Test fun nextTick() {
        assertEquals(1000L, NewYearMode.nextTickMs(LocalDateTime.parse("2026-12-31T23:10:05")))
        assertEquals(60_000L - 5_000L, NewYearMode.nextTickMs(LocalDateTime.parse("2026-12-31T12:10:05")))
        // за секунду до окна ждём ровно до 23:00:00
        assertEquals(1000L, NewYearMode.nextTickMs(LocalDateTime.parse("2026-12-31T22:59:59")).coerceAtMost(1000L))
    }

    @Test fun timeWithSeconds() {
        val t = LocalDateTime.parse("2026-12-31T23:05:09").toLocalTime()
        assertEquals("23:05:09", NewYearMode.timeWithSeconds(t, false))
        assertEquals("11:05:09", NewYearMode.timeWithSeconds(t, true))
    }

    @Test fun sparksAreStableAndOffCenter() {
        val a = NewYearSparks.layout()
        assertEquals(NewYearSparks.COUNT, a.size)
        assertEquals(a, NewYearSparks.layout())
        assertTrue(a.none { kotlin.math.abs(it.x - 0.5f) < 0.3f && kotlin.math.abs(it.y - 0.5f) < 0.2f })
    }
}
