// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.*
import org.junit.Test

class DesktopVideoSelectionTest {
    @Test fun autoLocksHuNativeGeometryUntilANewTransportSession() {
        val first = DesktopVideoSelection(DesktopResolution.AUTO, 0)
        val mode = first.resolve(1024, 768)
        assertEquals(1024 to 768, mode.outW to mode.outH)
        assertSame(mode, first.resolve(1600, 900)) // repeated INIT / software reconnect
        assertEquals(137, first.densityFor(mode))
        val next = DesktopVideoSelection(DesktopResolution.AUTO, 0).resolve(1280, 720)
        assertEquals(1280 to 720, next.outW to next.outH)
    }
    @Test fun invalidOrMissingInitLocksSafe720FallbackEvenIfValidInitArrivesLater() {
        for ((w, h) in listOf(0 to 0, -2 to 720, 2560 to 1440, 1281 to 720, 720 to 1280, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val selection = DesktopVideoSelection(DesktopResolution.AUTO, 0)
            val fallback = selection.resolve(w, h)
            assertEquals(1280 to 720, fallback.outW to fallback.outH)
            assertSame(fallback, selection.resolve(1024, 768))
        }
    }
    @Test fun fixedPresetsIgnoreHuRequestsAndRemainNativeWithoutCroppingOrTouchScaling() {
        for (preset in DesktopResolution.entries.filter { it != DesktopResolution.AUTO && it != DesktopResolution.CUSTOM }) {
            val selection = DesktopVideoSelection(preset, 200)
            val mode = selection.resolve(800, 480)
            assertEquals(preset.width to preset.height, mode.outW to mode.outH)
            assertEquals(mode.outW to mode.outH, mode.aaW to mode.aaH)
            assertEquals(mode.outW - 1 to mode.outH - 1, mode.mapTouch(mode.outW - 1, mode.outH - 1))
            assertTrue(mode.crop.isNone)
            assertTrue(DesktopVideoGeometry.matchesNative(mode))
            assertEquals(200, selection.densityFor(mode))
        }
    }
    @Test fun customFullHdSnapshotPreservesEveryPixelAndRequestedDensity() {
        val selection = DesktopVideoSelection(DesktopResolution.CUSTOM, 640, 1920, 1080)
        val mode = selection.resolve(1280, 720)
        assertEquals(1920 to 1080, mode.outW to mode.outH)
        assertEquals(mode.outW to mode.outH, mode.aaW to mode.aaH)
        assertEquals(1919 to 1079, mode.mapTouch(1919, 1079))
        assertEquals(640, selection.densityFor(mode))
        assertSame(mode, selection.resolve(800, 480))
    }
    @Test fun displaySizeFollowsTheAaVideoSetting() {
        for ((video, size) in listOf(VideoMode.R1080 to (1920 to 1080), VideoMode.R720 to (1280 to 720), VideoMode.R800 to (800 to 480),
            VideoMode.R1024 to (1024 to 600), VideoMode.WIDE_HD to (1920 to 720), VideoMode.WIDE_836 to (1920 to 836))) {
            val mode = DesktopVideoSelection.forAaVideo(video, 0).resolve(1024, 768) // запрос магнитолы игнорируется
            assertEquals(video.name, size, mode.outW to mode.outH)
        }
        val custom = DesktopVideoSelection.forAaVideo(CustomVideoMode(1600, 700), 0).resolve()
        assertEquals(1600 to 700, custom.outW to custom.outH)
    }

    @Test fun aaVideoOutsideTheDisplayLimitsFallsBackToAuto() {
        // 2240×976 (WIDE_976 с перекодированием) шире 1920: как просит магнитола, без запроса - 1280×720
        val wide = DesktopVideoSelection.forAaVideo(VideoMode.WIDE_976, 0)
        assertEquals(1024 to 768, wide.resolve(1024, 768).let { it.outW to it.outH })
        val noInit = DesktopVideoSelection.forAaVideo(VideoMode.WIDE_976, 0).resolve()
        assertEquals(1280 to 720, noInit.outW to noInit.outH)
        // без перекодирования WIDE_976 превращается в WIDE_836, и он помещается
        val passthrough = DesktopVideoSelection.forAaVideo(VideoMode.WIDE_976.effective(false), 200).resolve()
        assertEquals(1920 to 836, passthrough.outW to passthrough.outH)
    }

    @Test fun invalidCustomInputsAreRejectedRatherThanRoundedOrClamped() {
        for ((w, h) in listOf(1921 to 1080, 1920 to 1081, 639 to 480, 640 to 479, 800 to 900)) {
            assertThrows(IllegalArgumentException::class.java) { DesktopVideoSelection(DesktopResolution.CUSTOM, 160, w, h) }
        }
        for (dpi in listOf(-1, 95, 641, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { DesktopVideoSelection(DesktopResolution.CUSTOM, dpi, 1920, 1080) }
        }
    }
    @Test fun proportionalDpiAndBoundedParentPolicyDoNotAdoptLargeChildBuffers() {
        assertEquals(128, DesktopGeometryPolicy.density(720))
        assertEquals(137, DesktopGeometryPolicy.density(768))
        assertEquals(160, DesktopGeometryPolicy.density(900))
        assertTrue(DesktopGeometryPolicy.accepts(640, 480))
        assertTrue(DesktopGeometryPolicy.accepts(1600, 900))
        assertTrue(DesktopGeometryPolicy.accepts(1920, 1080))
        assertFalse(DesktopGeometryPolicy.accepts(3200, 1800))
        assertFalse(DesktopGeometryPolicy.accepts(1023, 768))
        assertFalse(DesktopGeometryPolicy.accepts(800, 902))
        assertFalse(DesktopGeometryPolicy.accepts(480, 640))
        assertEquals(DesktopResolution.R1600_900, DesktopResolution.restore(null))
        assertEquals(DesktopResolution.R1600_900, DesktopResolution.restore("corrupt"))
    }
}
