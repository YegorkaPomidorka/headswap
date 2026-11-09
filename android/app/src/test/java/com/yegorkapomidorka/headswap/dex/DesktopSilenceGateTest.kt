package com.yegorkapomidorka.headswap.dex

import com.yegorkapomidorka.headswap.dex.DesktopSilenceGate.Step
import org.junit.Assert.*
import org.junit.Test

class DesktopSilenceGateTest {
    @Test fun silenceAfterConnectNeverOpensMedia() {
        val gate = DesktopSilenceGate(5_000)
        for (t in 0L..60_000L step 20) assertEquals(Step.DROP, gate.step(false, t))
        assertFalse(gate.active)
    }

    @Test fun firstSoundStartsThenSends() {
        val gate = DesktopSilenceGate(5_000)
        assertEquals(Step.DROP, gate.step(false, 0))
        assertEquals(Step.START, gate.step(true, 100))
        assertEquals(Step.SEND, gate.step(true, 120))
        assertTrue(gate.active)
    }

    @Test fun shortGapBetweenTracksKeepsMediaOpen() {
        val gate = DesktopSilenceGate(5_000)
        gate.step(true, 0)
        for (t in 20L..4_980L step 20) assertEquals(Step.SEND, gate.step(false, t))
        assertEquals(Step.SEND, gate.step(true, 5_000))
        assertEquals(Step.SEND, gate.step(false, 9_980))
    }

    @Test fun longSilenceStopsOnceAndSoundReopens() {
        val gate = DesktopSilenceGate(5_000)
        gate.step(true, 0)
        assertEquals(Step.STOP, gate.step(false, 5_000))
        assertFalse(gate.active)
        assertEquals(Step.DROP, gate.step(false, 5_020))
        assertEquals(Step.START, gate.step(true, 8_000))
    }

    @Test fun audibleIgnoresZerosAndDither() {
        assertFalse(DesktopSilenceGate.audible(ByteArray(3840)))
        val dither = ByteArray(3840) { i -> if (i % 2 == 0) 3 else 0 } // +3
        assertFalse(DesktopSilenceGate.audible(dither))
        val minus = ByteArray(8).also { it[2] = 0xF0.toByte(); it[3] = 0xFF.toByte() } // -16
        assertFalse(DesktopSilenceGate.audible(minus))
        val loud = ByteArray(8).also { it[4] = 0x00; it[5] = 0x10 } // 4096
        assertTrue(DesktopSilenceGate.audible(loud))
        val loudNeg = ByteArray(8).also { it[6] = 0x00; it[7] = 0x80.toByte() } // -32768
        assertTrue(DesktopSilenceGate.audible(loudNeg))
    }
}
