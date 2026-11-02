// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopAudioPolicyTest {
    @Test fun captureRequiresEveryIndependentOptInAndLiveRoute() {
        assertTrue(DesktopAudioPolicy.mayCapture(true, true, true, true, true, true, true))
        for (missing in 0..6) {
            val flags = BooleanArray(7) { it != missing }
            assertFalse("missing gate $missing", DesktopAudioPolicy.mayCapture(
                flags[0], flags[1], flags[2], flags[3], flags[4], flags[5], flags[6]))
        }
    }

    @Test fun permissionExpansionRequiresExactExplicitArgument() {
        assertTrue(DesktopAudioPolicy.requestFromArgument("true"))
        for (value in listOf(null, "", "false", "1", "TRUE", " true"))
            assertFalse(DesktopAudioPolicy.requestFromArgument(value))
        assertEquals("android.permission.CAPTURE_AUDIO_OUTPUT", DesktopAudioPolicy.PERMISSION)
    }

    @Test fun shortReadPartitionsPreserveStereoFramesAndSampleOrder() {
        val original = ByteArray(128) { it.toByte() }
        for (chunkSize in 1..31) {
            val frames = DesktopPcmFrames()
            val actual = ArrayList<Byte>()
            var offset = 0
            while (offset < original.size) {
                val count = minOf(chunkSize, original.size - offset)
                val emitted = frames.append(original.copyOfRange(offset, offset + count), count)
                assertEquals(0, emitted.size % 4)
                actual.addAll(emitted.toList())
                offset += count
            }
            assertArrayEquals("chunk=$chunkSize", original, actual.toByteArray())
        }
    }

    @Test fun incompleteFrameWaitsForNextReadAndUnusedBufferBytesAreExcluded() {
        val frames = DesktopPcmFrames()
        assertArrayEquals(byteArrayOf(), frames.append(byteArrayOf(1, 2, 3, 99), 3))
        assertArrayEquals(byteArrayOf(), frames.append(byteArrayOf(88), 0))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frames.append(byteArrayOf(4, 99, 99), 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun impossibleReadLengthRejected() { DesktopPcmFrames().append(ByteArray(3), 4) }

    @Test fun oldCaptureCannotSendDataOrStopOnReconnectedTransport() {
        val gate = DesktopAudioSessionGate()
        val old = gate.next()
        val received = ArrayList<String>()
        assertTrue(gate.runIfCurrent(old) { received.add("old-data") })
        gate.next() // USB detached
        assertFalse(gate.runIfCurrent(old) { received.add("late-data") })
        val current = gate.next() // same bridge, new USB session
        assertFalse(gate.runIfCurrent(old) { received.add("old-stop-on-new-link") })
        assertTrue(gate.runIfCurrent(current) { received.add("new-data") })
        assertTrue(gate.runIfCurrent(current) { received.add("new-stop") })
        assertEquals(listOf("old-data", "new-data", "new-stop"), received)
    }
}
