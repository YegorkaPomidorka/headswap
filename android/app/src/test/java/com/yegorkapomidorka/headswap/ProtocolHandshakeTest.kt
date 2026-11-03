// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap

import org.junit.Assert.*
import org.junit.Test

class ProtocolHandshakeTest {
    private fun message(body: ByteArray = Msgs.protocolVersion(2, 0), truncated: Boolean = false) =
        CmdMsg(Msg.HU_PROTOCOL_VERSION, 0, body.size, body, truncated)

    @Test fun firstHandshakeThenSoftwareReconnectReplaysExactGreetingAndIdentity() {
        var infoReads = 0
        val handshake = ProtocolHandshake(true) {
            DeviceInfo(brand = "brand${++infoReads}", model = "phone", sdk = "29", sdkInt = 29)
        }
        val first = requireNotNull(handshake.accept(message()))
        assertFalse(first.repeated)
        assertEquals(2, first.major)
        assertEquals(0, first.minor)
        assertEquals(listOf(Msg.PROTOCOL_VERSION_MATCH_STATUS, Msg.FOREGROUND, Msg.SCREEN_ON, Msg.MD_INFO),
            first.commands.map { it.type })
        assertArrayEquals(byteArrayOf(8, 1), first.commands.first().body)
        assertEquals("brand1phone29", first.mdInfoKey)

        repeat(3) {
            val replay = requireNotNull(handshake.accept(message()))
            assertTrue(replay.repeated)
            assertEquals(first.mdInfoKey, replay.mdInfoKey)
            assertEquals(first.commands.size, replay.commands.size)
            first.commands.zip(replay.commands).forEach { (original, repeated) ->
                assertArrayEquals(Wire.cmdInner(original.type, original.body), Wire.cmdInner(repeated.type, repeated.body))
            }
        }
        assertEquals("A retry must not change MD_INFO or the identity used for HU authentication", 1, infoReads)
    }

    @Test fun retriesPreserveScreenOnConfiguration() {
        val handshake = ProtocolHandshake(false) { DeviceInfo() }
        repeat(2) {
            val reply = requireNotNull(handshake.accept(message(Msgs.protocolVersion(1, 0))))
            assertEquals(listOf(Msg.PROTOCOL_VERSION_MATCH_STATUS, Msg.FOREGROUND, Msg.MD_INFO), reply.commands.map { it.type })
            assertEquals(it != 0, reply.repeated)
        }
    }

    @Test fun malformedVersionsCannotEstablishOrReplaceHandshake() {
        var infoReads = 0
        val handshake = ProtocolHandshake(true) { infoReads++; DeviceInfo() }
        val bad = listOf(
            message(ByteArray(0)),
            message(PbWriter().int32(1, 2).toByteArray()), // Missing minor, not a valid proto2 greeting.
            message(PbWriter().int32(2, 0).toByteArray()),
            message(Msgs.protocolVersion(0, 0)),
            message(Msgs.protocolVersion(-1, 0)),
            message(Msgs.protocolVersion(2, -1)),
            message(byteArrayOf(8, 0x80.toByte())), // Truncated protobuf varint.
            message(truncated = true), // A complete-looking body in a truncated wire message.
            message(PbWriter().string(1, "2").int32(2, 0).toByteArray()),
            message(PbWriter().int32(1, 2).int32(1, 4).int32(2, 0).toByteArray()),
            CmdMsg(Msg.VIDEO_ENCODER_INIT, 0, 4, Msgs.protocolVersion(2, 0), false),
        )
        bad.forEach { assertNull(handshake.accept(it)) }
        assertEquals(0, infoReads)
        assertFalse(requireNotNull(handshake.accept(message())).repeated)
        bad.forEach { assertNull(handshake.accept(it)) }
        assertTrue(requireNotNull(handshake.accept(message())).repeated)
        assertEquals(1, infoReads)
    }

    @Test fun differentVersionCannotRenegotiateLiveSession() {
        val handshake = ProtocolHandshake(true) { DeviceInfo() }
        handshake.accept(message())
        assertNull(handshake.accept(message(Msgs.protocolVersion(1, 0))))
        assertNull(handshake.accept(message(Msgs.protocolVersion(2, 1))))
        val same = requireNotNull(handshake.accept(message()))
        assertTrue(same.repeated)
        assertEquals(2, same.major)
        assertEquals(0, same.minor)
    }
}
