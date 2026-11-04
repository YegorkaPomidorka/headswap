package com.yegorkapomidorka.headswap.carapp

import org.junit.Assert.*
import org.junit.Test

class CarUiTest {
    private val ru = CarUnits("м", "км", "фт", "ми", "ярд", "мин", "ч")

    @Test fun distanceKeepsTheAppUnitAndDecimalComma() {
        assertEquals("50 м", CarFormat.distance(50.0, CarTrip.UNIT_METERS, ru))
        assertEquals("4,3 км", CarFormat.distance(4.3, CarTrip.UNIT_KILOMETERS_P1, ru))
        assertEquals("12 км", CarFormat.distance(12.4, CarTrip.UNIT_KILOMETERS, ru))
        assertEquals("2 км", CarFormat.distance(2.0, CarTrip.UNIT_KILOMETERS_P1, ru))
        assertEquals("0,5 ми", CarFormat.distance(0.5, CarTrip.UNIT_MILES_P1, ru))
    }

    @Test fun durationInMinutesAndHours() {
        assertEquals("10 мин", CarFormat.duration(600, ru))
        assertEquals("1 мин", CarFormat.duration(20, ru))
        assertEquals("1 мин", CarFormat.duration(0, ru))
        assertEquals("1 ч 5 мин", CarFormat.duration(3900, ru))
        assertEquals("2 ч", CarFormat.duration(7200, ru))
        assertEquals("", CarFormat.duration(-1, ru))
    }

    @Test fun arrivalClockInTheAppZone() {
        // 2026-11-04 11:13 UTC, Калининград UTC+2 -> 13:13
        val utc = java.time.ZonedDateTime.of(2026, 11, 4, 11, 13, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("13:13", CarFormat.clock(utc, 7200))
        assertEquals("23:59", CarFormat.clock(59 * 60_000L, -60 * 60))
    }

    @Test fun endIsTheTitleOnlyActionOfTheStrip() {
        data class A(val title: String, val icon: Boolean)
        val overview = A("", true); val search = A("", true); val settings = A("", true); val end = A("Завершить", false)
        val (rest, found) = CarActions.splitEnd(listOf(overview, search, settings, end), { it.icon }, { it.title })
        assertSame(end, found)
        assertEquals(listOf(overview, search, settings), rest)
        val (all, none) = CarActions.splitEnd(listOf(overview, A("", false)), { it.icon }, { it.title })
        assertNull(none)
        assertEquals(2, all.size)
    }

    @Test fun panButtonIsHidden() {
        assertEquals(listOf(1, 1), CarActions.visibleMapActions(listOf(1, CarActions.TYPE_PAN, 1)) { it })
    }
}
