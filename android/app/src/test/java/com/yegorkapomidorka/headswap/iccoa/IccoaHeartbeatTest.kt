// Derived from FT (github.com/OneAboveAll1964/ft-app), Apache-2.0: IccoaHeartbeatTest.kt. Изменено для Headswap; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import com.yegorkapomidorka.headswap.PbReader
import com.yegorkapomidorka.headswap.PbWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class IccoaHeartbeatTest {
    @Test fun startWritesEncryptedHeartbeatBeforeAnyScheduledCallback() {
        val scheduled = ArrayDeque<Pair<Long, () -> Unit>>()
        val cipher = IccoaFrame.Cipher(ByteArray(16) { it.toByte() })
        val wire = ByteArrayOutputStream()
        val heartbeat = IccoaHeartbeat(send = { body ->
            wire.write(IccoaFrame.frame(IccoaFrame.CAT_CONTROL, IccoaControl.HEARTBEAT, 0, cipher.encrypt(body), 1000, 1))
            true
        }, schedule = { delay, action -> scheduled.add(delay to action) })
        val before = System.currentTimeMillis()
        heartbeat.start()
        val next = scheduled.removeFirst()
        assertEquals(2000L, next.first)
        heartbeat.start() // Повторный start не плодит таймеры и кадры.
        assertTrue(scheduled.isEmpty())
        next.second()
        assertEquals(2000L, scheduled.removeFirst().first)
        val input = ByteArrayInputStream(wire.toByteArray())
        val frame = IccoaFrame.read(input)
        assertEquals(1, frame.category)
        assertEquals(1, frame.method)
        assertEquals(1, frame.format)
        val inbound = IccoaFrame.decode(IccoaFrame.CONTROL_PORT, frame, cipher)
        assertTrue(inbound.authenticatedControl)
        val timestamp = IccoaFrame.Fields(inbound.body).number(1)
        assertTrue(timestamp >= before && timestamp <= System.currentTimeMillis())
        assertEquals(1, IccoaFrame.read(input).method)
        assertNull(IccoaFrame.readOrNull(input))
        heartbeat.close()
    }

    @Test fun closedAttemptCannotSendAgainEvenIfItsTimerRunsDuringRetry() {
        val oldTimers = mutableListOf<() -> Unit>()
        var oldSends = 0
        val old = IccoaHeartbeat({ oldSends++; true }, { _, action -> oldTimers.add(action) })
        old.start()
        old.close()
        var newSends = 0
        val replacement = IccoaHeartbeat({ newSends++; true }, { _, _ -> })
        replacement.start()
        oldTimers.single()()
        old.start()
        assertEquals(1, oldSends)
        assertEquals(1, newSends)
        replacement.close()
    }

    @Test fun failedEnqueueStopsHeartbeatWithoutSchedulingOrRestarting() {
        var sends = 0
        val heartbeat = IccoaHeartbeat({ sends++; false }, { _, _ -> fail("Failed send must not schedule") })
        heartbeat.start()
        heartbeat.start()
        assertEquals(1, sends)
        heartbeat.close()
    }

    /** Только зашифрованный control с 57219 доказывает, что машина приняла сессию. */
    @Test fun onlyEncryptedControlLaneFrameCountsAsAuthenticated() {
        val cipher = IccoaFrame.Cipher(ByteArray(16))
        fun frame(category: Int, method: Int, body: ByteArray) =
            IccoaFrame.read(ByteArrayInputStream(IccoaFrame.frame(category, method, 0, body, 1)))
        val sealed = cipher.encrypt(byteArrayOf(8, 1))
        assertTrue(IccoaFrame.decode(57219, frame(1, 2, sealed), cipher).authenticatedControl)
        assertArrayEquals(byteArrayOf(8, 1), IccoaFrame.decode(57219, frame(1, 2, sealed), cipher).body)
        assertFalse(IccoaFrame.decode(57229, frame(1, 2, sealed), cipher).authenticatedControl)
        assertFalse(IccoaFrame.decode(57219, frame(4, 9, sealed), cipher).authenticatedControl)
        assertFalse(IccoaFrame.decode(57219, frame(1, 23, sealed), cipher).authenticatedControl)
        val plain = IccoaFrame.decode(57219, frame(1, 2, byteArrayOf(8, 1)), cipher)
        assertFalse(plain.authenticatedControl)
        assertArrayEquals(byteArrayOf(8, 1), plain.body)
        assertThrows(Exception::class.java) {
            IccoaFrame.decode(57219, frame(1, 2, sealed), IccoaFrame.Cipher(ByteArray(16) { 1 }))
        }
    }

    @Test fun ackAndControlBodiesMatchSpec() {
        val ack = IccoaFrame.read(ByteArrayInputStream(IccoaFrame.ack(77)))
        assertEquals(listOf(77, 7, 0, 2, 0), listOf(ack.sequence, ack.category, ack.method, ack.type, ack.body.size))
        assertEquals(IccoaControl.Key(false, 8107), IccoaControl.key(PbWriter().int32(1, 0).int32(2, 8107).toByteArray()))
        assertEquals(IccoaControl.Key(true, 87), IccoaControl.key(PbWriter().int32(1, 1).int32(2, 87).toByteArray()))
        assertNull(IccoaControl.key(PbWriter().int32(1, 1).toByteArray()))
        assertNull(IccoaControl.key(byteArrayOf(0x12, 4, 1)))
        val mic = PbReader.parse(IccoaControl.microphone(true))!!
        assertEquals(listOf(1, 16000, 1, 2), (1..4).map { PbReader.int(mic, it) })
        assertEquals(0, PbReader.int(PbReader.parse(IccoaControl.microphone(false))!!, 1))
        assertEquals(1_700_000_000_123L, PbReader.long(PbReader.parse(IccoaControl.heartbeat(1_700_000_000_123L))!!, 1))
    }
}
