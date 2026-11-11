package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopVolumeGainTest {
    @Test fun endsOfScale() {
        assertEquals(1f, DesktopVolumeGain.forIndex(15, 15))
        assertEquals(0f, DesktopVolumeGain.forIndex(0, 15))
        assertEquals(1f, DesktopVolumeGain.forIndex(3, 0)) // нет данных - не трогаем
    }

    @Test fun monotonicAndLowestStepAudible() {
        var prev = 0f
        for (i in 1..15) {
            val g = DesktopVolumeGain.forIndex(i, 15)
            assertTrue("$i: $g > $prev", g > prev)
            prev = g
        }
        assertTrue(DesktopVolumeGain.forIndex(1, 15) > 0.005f)
    }

    private fun pcm(vararg s: Int) = ByteArray(s.size * 2).also { b ->
        s.forEachIndexed { i, v -> b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
    }

    @Test fun fullGainKeepsBytes() {
        val p = pcm(32767, -32768, 1000, -1)
        val copy = p.copyOf()
        DesktopVolumeGain.apply(p, 1f)
        assertArrayEquals(copy, p)
    }

    @Test fun halfGainScalesSigned() {
        val p = pcm(32767, -32768, 1000, -1000)
        DesktopVolumeGain.apply(p, 0.5f)
        assertArrayEquals(pcm(16383, -16384, 500, -500), p)
    }

    @Test fun zeroGainIsSilence() {
        val p = pcm(32767, -32768, 1234)
        DesktopVolumeGain.apply(p, 0f)
        assertArrayEquals(ByteArray(p.size), p)
    }
}
