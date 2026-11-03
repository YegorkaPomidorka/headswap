// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopVideoQualityTest {
    @Test
    fun fastUsbKeepsEightMegabitsWithoutChangingLegacy720() {
        val policy = DesktopVideoQuality.bitratePolicy(BitrateProfile.USB)
        assertEquals(8_000_000, policy.startBps)
        for (now in 0L..60_000L step 100) policy.update(now, 0.0, 2_000_000.0)
        assertEquals(8_000_000, policy.current)
        assertEquals(4_000_000, VideoMode.R720.bitrate)
        assertEquals(1280, VideoMode.R720.outW)
        assertEquals(720, VideoMode.R720.outH)
    }

    @Test
    fun slowUsbStillReducesTheHigherTargetAndRecoversOnlyToItsCeiling() {
        val policy = DesktopVideoQuality.bitratePolicy(BitrateProfile.USB)
        val drain = 210.0 * 1024
        var queued = 0.0
        for (now in 0L..30_000L step 100) {
            queued = maxOf(0.0, queued + policy.current / 8.0 * 0.1 - drain * 0.1)
            policy.update(now, queued / drain * 1000, drain)
        }
        assertTrue("slow USB must shed its initial 8 Mbit/s load", policy.current < 2_000_000)
        assertTrue(policy.downs > 0)
        assertTrue(policy.current >= BitratePolicy.FLOOR_BPS)
        for (now in 30_100L..300_000L step 100) {
            policy.update(now, 0.0, 2_000_000.0)
            assertTrue(policy.current <= 8_000_000)
        }
        assertEquals(8_000_000, policy.current)
    }

    @Test
    fun knownHuAndThermalCapsRemainAuthoritative() {
        val policy = DesktopVideoQuality.bitratePolicy(BitrateProfile.USB)
        policy.lowerCeiling(1_500_000)
        policy.setThermalCap(1_000_000)
        for (now in 0L..60_000L step 100) policy.update(now, 0.0, 2_000_000.0)
        assertEquals(1_000_000, policy.current)
        policy.setThermalCap(Int.MAX_VALUE)
        for (now in 60_100L..180_000L step 100) policy.update(now, 0.0, 2_000_000.0)
        assertEquals(1_500_000, policy.current)
        assertEquals(1_500_000, policy.ceilingBps)
    }
    @Test fun sixtyFpsSurvivesSelectionWhileAutoAndKnownHuCapsRemainExplicit() {
        assertEquals(60, DesktopVideoQuality.chooseFps(FpsSetting.FPS60, 30))
        assertEquals(60, DesktopVideoQuality.chooseFps(FpsSetting.AUTO, 60))
        assertEquals(30, DesktopVideoQuality.chooseFps(FpsSetting.AUTO, 30))
        assertEquals(30, DesktopVideoQuality.chooseFps(FpsSetting.AUTO, 120))
        assertEquals(30, DesktopVideoQuality.chooseFps(FpsSetting.AUTO, 0))
        assertEquals(20, DesktopVideoQuality.chooseFps(FpsSetting.FPS60, 60, 20))
        assertTrue(DesktopVideoQuality.frameRateChangeAllowed(60))
        org.junit.Assert.assertFalse(DesktopVideoQuality.frameRateChangeAllowed(61))
    }
}
