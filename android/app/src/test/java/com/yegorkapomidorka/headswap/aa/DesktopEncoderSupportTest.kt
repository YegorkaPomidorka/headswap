// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.*
import org.junit.Test

class DesktopEncoderSupportTest {
    @Test fun fullHdSixtySelectsOnlyAnEncoderSupportingAllRequestedParameters() {
        val calls = mutableListOf<List<Int>>()
        val result = DesktopEncoderSupport.select(listOf(
            DesktopEncoderSupport.Candidate("30fps-only") { _, _, fps, _ -> fps <= 30 },
            DesktopEncoderSupport.Candidate("full-hd-60") { w, h, fps, bps ->
                calls += listOf(w, h, fps, bps)
                w <= 1920 && h <= 1080 && fps <= 60 && bps <= 8_000_000
            }), 1920, 1080, 60, 8_000_000)
        assertEquals("full-hd-60", result)
        assertEquals(listOf(listOf(1920, 1080, 60, 8_000_000)), calls)
    }
    @Test fun unsupportedRateAndAlignmentAreRejectedWithoutChangingTheRequestedGeometry() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val encoder = DesktopEncoderSupport.Candidate("aligned") { w, h, fps, _ ->
            calls += w to h
            w % 16 == 0 && h % 16 == 0 && fps <= 30
        }
        assertThrows(IllegalArgumentException::class.java) {
            DesktopEncoderSupport.select(listOf(encoder), 1920, 1080, 60, 8_000_000)
        }
        assertEquals(listOf(1920 to 1080), calls)
        assertThrows(IllegalArgumentException::class.java) {
            DesktopEncoderSupport.select(listOf(encoder), 1282, 720, 30, 8_000_000)
        }
        assertEquals(listOf(1920 to 1080, 1282 to 720), calls)
    }
    @Test fun brokenVendorCapabilityDoesNotPreventTryingAnotherDeclaredEncoder() {
        val candidates = listOf(DesktopEncoderSupport.Candidate("broken") { _, _, _, _ -> error("vendor") },
            DesktopEncoderSupport.Candidate("good") { _, _, _, _ -> true })
        assertEquals("good", DesktopEncoderSupport.select(candidates, 1280, 720, 60, 8_000_000))
        assertThrows(IllegalArgumentException::class.java) { DesktopEncoderSupport.select(emptyList(), 1280, 720, 60, 8_000_000) }
    }
    @Test fun invalidRequestsNeverReachCodecCapabilityQueries() {
        val candidates = listOf(DesktopEncoderSupport.Candidate("spy") { _, _, _, _ -> fail("Invalid input queried codec"); true })
        for ((w, h) in listOf(1921 to 1080, 3840 to 2160, 720 to 1280)) {
            assertThrows(IllegalArgumentException::class.java) { DesktopEncoderSupport.select(candidates, w, h, 60, 8_000_000) }
        }
        for (fps in listOf(-1, 0, 61)) assertThrows(IllegalArgumentException::class.java) {
            DesktopEncoderSupport.select(candidates, 1280, 720, fps, 8_000_000)
        }
    }
}
