package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.aa.IdrSizeGuard.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdrSizeGuardTest {
    private val g = IdrSizeGuard(580_000)

    @Test
    fun fittingIdrAndPFramesPass() {
        assertEquals(Verdict.SEND, g.decide(580_000, true))
        assertEquals(Verdict.SEND, g.decide(40_000, false))
        assertFalse(g.justRecovered)
    }

    @Test
    fun oversizeIdrDropsPUntilFittingIdr() {
        assertEquals(Verdict.DROP_REQUEST_IDR, g.decide(601_527, true))
        assertEquals(Verdict.DROP, g.decide(10_000, false))
        assertEquals(Verdict.DROP, g.decide(10_000, false))
        assertEquals(Verdict.DROP_REQUEST_IDR, g.decide(620_877, true))
        assertEquals(Verdict.SEND, g.decide(515_268, true))
        assertTrue(g.justRecovered)
        assertEquals(2, g.lastDroppedP)
        assertEquals(Verdict.SEND, g.decide(10_000, false))
        assertFalse(g.justRecovered)
    }
}
