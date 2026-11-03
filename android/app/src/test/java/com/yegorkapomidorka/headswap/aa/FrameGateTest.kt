package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FrameGateTest {
    /** Сколько кадров пропустит гейт за seconds секунд входа с частотой inFps (джиттер +-jitterMs). */
    private fun passed(gate: FrameGate, inFps: Int, seconds: Int, jitterMs: Int = 0): Int {
        val rnd = Random(42)
        val periodNs = 1_000_000_000L / inFps
        var n = 0
        for (i in 0 until inFps * seconds) {
            val j = if (jitterMs > 0) (rnd.nextInt(2 * jitterMs + 1) - jitterMs) * 1_000_000L else 0L
            if (gate.shouldEmit(1_000_000_000L + i * periodNs + j)) n++
        }
        return n
    }

    @Test
    fun input30Target15GivesAbout15() {
        assertEquals(150.0, passed(FrameGate(15), 30, 10).toDouble(), 3.0)
        assertEquals(150.0, passed(FrameGate(15), 30, 10, jitterMs = 3).toDouble(), 8.0)
    }

    @Test
    fun input30Target30LosesNothing() {
        assertEquals(300, passed(FrameGate(30), 30, 10))
        // джиттер входа +-5 мс: цель 30 пропускает всё
        assertEquals(300, passed(FrameGate(30), 30, 10, jitterMs = 5))
    }

    @Test
    fun lowInputPassesEverything() {
        assertEquals(100, passed(FrameGate(30), 10, 10))
        assertEquals(100, passed(FrameGate(15), 10, 10))
        assertEquals(100, passed(FrameGate(15), 10, 10, jitterMs = 5))
    }

    @Test
    fun slotsKeepRateWhenInputIsFasterThanTarget() {
        // 30 -> 20 (слоты по 50 мс на входе 33.3 мс): средняя частота выхода 20
        assertEquals(200.0, passed(FrameGate(20), 30, 10).toDouble(), 3.0)
        assertEquals(200.0, passed(FrameGate(20), 30, 10, jitterMs = 4).toDouble(), 8.0)
    }

    @Test
    fun unlimitedAndFirstFrame() {
        assertEquals(300, passed(FrameGate(0), 30, 10))
        val g = FrameGate(15)
        assertTrue(g.shouldEmit(5L))                    // первый кадр - сразу, даже при нулевом времени
        assertFalse(g.shouldEmit(5L + 10_000_000L))
        assertFalse(g.shouldEmit(5L + 30_000_000L))     // слот 66.7 мс, берём с 33.3 мс
        assertTrue(g.shouldEmit(5L + 40_000_000L))
        g.reset()
        assertTrue(g.shouldEmit(5L + 61_000_000L))
    }

    @Test
    fun lagBeyondPeriodRestartsGrid() {
        val g = FrameGate(15)
        assertTrue(g.shouldEmit(0L))
        assertTrue(g.shouldEmit(300_000_000L))          // пауза входа: кадр берётся, сетка с нового места
        assertFalse(g.shouldEmit(310_000_000L))         // без серии догоняющих кадров
        assertFalse(g.shouldEmit(330_000_000L))
        assertTrue(g.shouldEmit(370_000_000L))
    }

    @Test
    fun targetChangesOnTheFly() {
        val g = FrameGate(30)
        assertTrue(g.shouldEmit(0L))
        assertTrue(g.shouldEmit(33_000_000L))
        g.targetFps = 10
        assertEquals(100_000_000L, g.periodNs())
        assertTrue(g.shouldEmit(66_000_000L))           // после перехода с пропуска всего сетка начинается с первого кадра
        assertFalse(g.shouldEmit(99_000_000L))
        assertTrue(g.shouldEmit(66_000_000L + 100_000_000L))
        g.targetFps = 30
        assertTrue(g.shouldEmit(170_000_000L))
        assertTrue(g.shouldEmit(171_000_000L))
    }
    @Test fun desktop120HzIsActuallyLimitedTo30WithoutChangingLegacyAa30() {
        assertEquals(300.0, passed(FrameGate(30, limitHighFps = true), 120, 10).toDouble(), 1.0)
        assertEquals(300.0, passed(FrameGate(30, limitHighFps = true), 60, 10).toDouble(), 1.0)
        assertEquals(150.0, passed(FrameGate(15, limitHighFps = true), 120, 10).toDouble(), 1.0)
        assertEquals(1200, passed(FrameGate(30), 120, 10))
        assertEquals(100, passed(FrameGate(30, limitHighFps = true), 10, 10))
    }
    @Test fun directFrameGateDoesNotBurstAfterIdleAndStillCapsAfterTargetChange() {
        val gate = FrameGate(30, limitHighFps = true)
        assertTrue(gate.shouldEmit(0))
        assertFalse(gate.shouldEmit(1_000_000))
        assertTrue(gate.shouldEmit(2_000_000_000))
        assertFalse(gate.shouldEmit(2_001_000_000))
        gate.targetFps = 15
        assertFalse(gate.shouldEmit(2_001_000_001))
        gate.reset()
        assertTrue(gate.shouldEmit(2_001_000_002))
    }

    @Test fun desktopSixtyTargetIsNotSilentlyReducedToThirty() {
        assertEquals(600.0, passed(FrameGate(60, limitHighFps = true), 120, 10).toDouble(), 1.0)
        assertEquals(600, passed(FrameGate(60, limitHighFps = true), 60, 10))
        assertEquals(100, passed(FrameGate(60, limitHighFps = true), 10, 10))
    }
}
