package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdrBitrateCeilingTest {
    private val max = 3_840_000
    private val big = 310 * 1024
    private val small = 100 * 1024
    private val c = IdrBitrateCeiling(max)

    @Test
    fun startsAtMaxAndSmallIdrDoNothing() {
        assertEquals(max, c.current)
        repeat(20) { assertNull(c.onKeyFrame(small)) }
        assertEquals(max, c.current)
    }

    @Test
    fun idrAboveLimitCutsBy30PercentEachTime() {
        assertEquals(2_688_000, c.onKeyFrame(big))
        assertEquals(1_881_600, c.onKeyFrame(big))
        assertEquals(1_317_120, c.onKeyFrame(big))
        assertEquals(1_000_000, c.onKeyFrame(big))              // пол
        assertNull(c.onKeyFrame(big))
        assertEquals(1_000_000, c.current)
    }

    @Test
    fun limitIsInclusive() {
        assertNull(c.onKeyFrame(IdrBitrateCeiling.LIMIT_BYTES))
        assertEquals(2_688_000, c.onKeyFrame(IdrBitrateCeiling.LIMIT_BYTES + 1))
    }

    @Test
    fun fiveCalmIdrInARowRaiseBy15PercentUpToMax() {
        c.onKeyFrame(big)
        c.onKeyFrame(big)                                       // 1 881 600
        repeat(4) { assertNull(c.onKeyFrame(small)) }
        assertEquals(2_163_840, c.onKeyFrame(small))
        repeat(4) { assertNull(c.onKeyFrame(small)) }
        assertEquals(2_488_416, c.onKeyFrame(small))
        var last = c.current
        repeat(60) { c.onKeyFrame(small).let { v -> if (v != null) last = v } }
        assertEquals(max, last)                                 // выше максимума не поднимается
        assertEquals(max, c.current)
    }

    @Test
    fun midSizeIdrBreaksCalmStreak() {
        c.onKeyFrame(big)
        repeat(4) { c.onKeyFrame(small) }
        assertNull(c.onKeyFrame(200 * 1024))                    // между 150 и 300 КБ: серия обнуляется
        repeat(4) { assertNull(c.onKeyFrame(small)) }
        assertEquals(2_688_000 * 115 / 100, c.onKeyFrame(small))
    }

    @Test
    fun bigIdrResetsCalmStreak() {
        c.onKeyFrame(big)                                       // 2 688 000
        repeat(4) { c.onKeyFrame(small) }
        assertEquals(1_881_600, c.onKeyFrame(big))
        repeat(4) { assertNull(c.onKeyFrame(small)) }
        assertEquals(2_163_840, c.onKeyFrame(small))
    }

    @Test
    fun stuckAtFloorCountsAndResets() {
        val f = IdrBitrateCeiling(1_200_000)
        assertEquals(1_000_000, f.onKeyFrame(big))
        assertEquals(0, f.stuckAtFloor)
        assertNull(f.onKeyFrame(big))
        assertEquals(1, f.stuckAtFloor)
        assertNull(f.onKeyFrame(big))
        assertEquals(2, f.stuckAtFloor)
        f.onKeyFrame(small)
        assertEquals(0, f.stuckAtFloor)
    }
}
