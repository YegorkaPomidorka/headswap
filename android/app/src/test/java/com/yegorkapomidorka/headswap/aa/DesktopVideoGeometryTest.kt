// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.*
import org.junit.Test

class DesktopVideoGeometryTest {
    @Test fun nativeDesktopUsesTheSameInputOutputAndTouchCoordinates() {
        val mode = DesktopVideoGeometry
        assertEquals(1600 to 900, mode.outW to mode.outH)
        assertEquals(mode.outW to mode.outH, mode.aaW to mode.aaH)
        assertTrue(mode.crop.isNone)
        assertTrue(mode.cropFor(true).isNone)
        assertFalse(mode.spsCropable)
        assertEquals(1599 to 899, mode.mapTouch(1599, 899))
        assertSame(mode, mode.effective(true))
        assertSame(mode, mode.effective(false))
        assertTrue(DesktopVideoGeometry.matchesNative(mode))
        assertEquals(8_000_000, mode.bitrate)
    }

    @Test fun anAaCanvasOrOld720PresetIsNotANative900Surface() {
        val aaCanvas = CustomVideoMode(1600, 900)
        assertEquals(1920 to 1080, aaCanvas.aaW to aaCanvas.aaH)
        assertFalse(DesktopVideoGeometry.matchesNative(aaCanvas))
        assertFalse(DesktopVideoGeometry.matchesNative(VideoMode.R720))
        assertEquals(1280 to 720, VideoMode.R720.outW to VideoMode.R720.outH)
        assertEquals(4_000_000, VideoMode.R720.bitrate)
    }
}
