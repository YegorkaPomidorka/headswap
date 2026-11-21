package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Test

class AvcLevelForTest {
    private fun name(w: Int, h: Int, fps: Int) = AaTranscoder.avcLevelFor(w, h, fps).second

    @Test
    fun desktopSizes() {
        assertEquals("3.0", name(720, 480, 30))
        assertEquals("3.1", name(800, 480, 30))
        assertEquals("3.1", name(1024, 600, 30))   // DS03H + Xiaomi 220333QNY
        assertEquals("3.1", name(1280, 720, 30))
        assertEquals("3.2", name(1280, 720, 60))
        assertEquals("4.0", name(1920, 1080, 30))
        assertEquals("4.2", name(1920, 1080, 60))
        assertEquals("5.1", name(2560, 1600, 30))
    }
}
