// Derived from FT (github.com/OneAboveAll1964/ft-app), Apache-2.0: IccoaAudioPlayerStateTest.kt. Изменено для Headswap; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import com.yegorkapomidorka.headswap.PbReader
import org.junit.Assert.*
import org.junit.Test

class IccoaAudioPlayerStateTest {
    private fun state(body: ByteArray) = PbReader.int(PbReader.parse(body)!!, 2)!!

    @Test fun delayedLaneReceivesAacStartWithReferenceFields() {
        val player = IccoaAudioPlayerState()
        player.play()
        repeat(3) { assertNull(player.flush { false }) }
        assertTrue(player.pending)
        assertEquals(0, player.flush { bytes ->
            val body = PbReader.parse(bytes)!!
            assertEquals(listOf(1, 2, 3, 4, 5, 6), body.map { it.number })
            assertEquals(16, PbReader.int(body, 1))
            assertEquals(0, PbReader.int(body, 2))
            assertEquals(48000, PbReader.int(body, 3))
            assertEquals(2, PbReader.int(body, 4))
            assertEquals(2, PbReader.int(body, 5)) // UCar encoding_format PCM_16BIT
            assertEquals("audio/mp4a-latm", PbReader.str(body, 6))
            true
        })
        assertFalse(player.pending)
    }

    @Test fun speechStreamUsesItsOwnStreamType() {
        val player = IccoaAudioPlayerState(IccoaAudioPlayerState.STREAM_TTS)
        player.play()
        player.flush { assertEquals(6, PbReader.int(PbReader.parse(it)!!, 1)); true }
    }

    @Test fun pauseSupersedesAnUnsentStart() {
        val player = IccoaAudioPlayerState()
        player.play()
        player.flush { false }
        player.pause()
        assertEquals(2, player.flush { assertEquals(2, state(it)); true })
        player.play()
        assertEquals(3, player.flush { true })
    }

    @Test fun repeatedPlayDoesNotRestartAnActivePlayer() {
        val player = IccoaAudioPlayerState()
        val states = mutableListOf<Int>()
        fun flush() = player.flush { states += state(it); true }
        player.play(); flush()
        player.play(); flush()
        player.pause(); flush()
        player.pause(); flush()
        player.play(); flush()
        assertEquals(listOf(0, 2, 3), states)
    }

    @Test fun reconnectDropsPendingStateAndStartsANewPlayer() {
        val player = IccoaAudioPlayerState()
        player.play(); player.flush { true }
        player.pause(); player.flush { false }
        player.reset()
        assertFalse(player.pending)
        player.pause()
        assertNull(player.flush { fail("No old-session notification should be sent"); true })
        player.play()
        assertEquals(0, player.flush { true })
    }

    @Test fun failedPauseIsNotReplayedAfterAnotherPlay() {
        val player = IccoaAudioPlayerState()
        player.play(); player.flush { true }
        player.pause(); player.flush { false }
        player.play()
        assertFalse(player.pending)
        assertNull(player.flush { fail("Receiver already has the active state"); true })
    }
}
