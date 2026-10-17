package com.yegorkapomidorka.headswap

import org.junit.Assert.assertEquals
import org.junit.Test

class LogThrottleTest {
    @Test
    fun firstThenEveryNth() {
        val t = LogThrottle(first = 3, every = 200)
        val logged = (1..1000).map { t.next(7) }.filter { it != 0L }
        assertEquals(listOf(1L, 2L, 3L, 200L, 400L, 600L, 800L, 1000L), logged)
        assertEquals(1000L, t.count(7))
    }

    @Test
    fun keysAreIndependent() {
        val t = LogThrottle(first = 1, every = 10)
        assertEquals(1L, t.next(1))
        assertEquals(0L, t.next(1))
        assertEquals(1L, t.next(2))
        assertEquals(0L, t.count(3))
        t.reset()
        assertEquals(1L, t.next(1))
    }
}
