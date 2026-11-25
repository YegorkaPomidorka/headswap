// Derived from FT (github.com/OneAboveAll1964/ft-app), Apache-2.0: IccoaControlTest.kt. Изменено для Headswap; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.nio.ByteBuffer

class IccoaFrameTest {
    // Synthetic payload; fixed header matches the XA60 report's length/lane/flags, not captured secrets.
    private fun xa60Response(): ByteArray =
        byteArrayOf(0, 0, 1, 60, 0, 0, 0, 42, 0, 0, 0, 123, 0x12, 3, 0, 2, 0, 0, 0x2c, 0x5a) +
            ByteArray(296) { it.toByte() }

    @Test fun xa60AuthHeaderWithLowBitClearSupportsFragmentedAndConsecutiveFrames() {
        val packet = xa60Response()
        val next = IccoaFrame.frame(1, 4, 0, byteArrayOf(9), 43)
        val input = object : ByteArrayInputStream(packet + next) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(length, 3))
        }
        val frame = IccoaFrame.read(input)
        assertEquals(42, frame.sequence)
        assertEquals(3, frame.category)
        assertEquals(2, frame.method)
        assertEquals(2, frame.type)
        assertEquals(1, frame.format)
        assertArrayEquals(packet.copyOfRange(20, packet.size), frame.body)
        assertEquals(43, IccoaFrame.read(input).sequence)
        assertEquals(-1, input.read())
    }

    @Test fun xa60FlagsDoNotBypassCrcLengthOrTruncationChecks() {
        val packet = xa60Response()
        val corrupt = packet.copyOf().also { it[4] = 1 }
        val crcError = assertThrows(IccoaHandshakeException::class.java) {
            IccoaFrame.read(ByteArrayInputStream(corrupt))
        }
        assertEquals(IccoaHandshakeException.Reason.HEADER_CRC, crcError.reason)
        val oversized = packet.copyOf().also {
            ByteBuffer.wrap(it).putInt(Int.MAX_VALUE)
            ByteBuffer.wrap(it).putShort(18, IccoaFrame.crc16(it, 18).toShort())
        }
        val lengthError = assertThrows(IccoaHandshakeException::class.java) {
            IccoaFrame.read(ByteArrayInputStream(oversized))
        }
        assertEquals(IccoaHandshakeException.Reason.FRAME_LENGTH, lengthError.reason)
        assertThrows(EOFException::class.java) {
            IccoaFrame.read(ByteArrayInputStream(packet.copyOf(packet.size - 1)))
        }
    }

    @Test fun frameRoundTripAndIndependentCrcVector() {
        assertEquals(0xbb3d, IccoaFrame.crc16("123456789".toByteArray()))
        val packet = IccoaFrame.frame(3, 1, 1, byteArrayOf(7, 8, 9), 42, timeSeconds = 123)
        assertEquals(11, packet[12].toInt())
        val frame = IccoaFrame.read(ByteArrayInputStream(packet))
        assertEquals(42, frame.sequence)
        assertEquals(3, frame.category)
        assertEquals(1, frame.method)
        assertEquals(1, frame.type)
        assertEquals(1, frame.format)
        assertArrayEquals(byteArrayOf(7, 8, 9), frame.body)
    }

    @Test fun corruptOrOversizedFramesFailBeforeReadingBody() {
        val packet = IccoaFrame.frame(1, 1, 0, byteArrayOf(), 1)
        val corrupt = packet.copyOf().also { it[4] = (it[4].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { IccoaFrame.read(ByteArrayInputStream(corrupt)) }
        val oversized = packet.copyOf().also { ByteBuffer.wrap(it).putInt(Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { IccoaFrame.read(ByteArrayInputStream(oversized)) }
        val truncated = IccoaFrame.frame(1, 1, 0, byteArrayOf(1, 2), 1).dropLast(1).toByteArray()
        assertThrows(EOFException::class.java) { IccoaFrame.read(ByteArrayInputStream(truncated)) }
    }

    @Test fun gcmAuthenticatesBodyAndSupportsConsecutiveEnvelopes() {
        val cipher = IccoaFrame.Cipher(ByteArray(16) { it.toByte() })
        val first = cipher.encrypt("first".toByteArray())
        val second = cipher.encrypt("second".toByteArray())
        assertEquals("firstsecond", cipher.decrypt(first + second).toString(Charsets.UTF_8))
        val tampered = first.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { cipher.decrypt(tampered) }
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt(first + byteArrayOf(0)) }
        assertThrows(Exception::class.java) { IccoaFrame.Cipher(ByteArray(16)).decrypt(first) }
    }

    @Test fun cipherRecoversAfterAuthenticationFailureAndSeparatesConcurrentLanes() {
        val cipher = IccoaFrame.Cipher(ByteArray(16) { it.toByte() })
        val valid = cipher.encrypt("valid".toByteArray())
        val corrupt = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { cipher.decrypt(corrupt) }
        assertEquals("valid", cipher.decrypt(valid).toString(Charsets.UTF_8))
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        val ivs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        try {
            val jobs = (0 until 4).map { lane ->
                executor.submit {
                    repeat(100) { index ->
                        val plain = "$lane:$index".toByteArray()
                        val envelope = cipher.encrypt(plain)
                        assertTrue(ivs.add(envelope.copyOfRange(4, 16).joinToString()))
                        assertArrayEquals(plain, cipher.decrypt(envelope))
                        assertArrayEquals(plain + plain, cipher.decrypt(envelope + envelope))
                    }
                }
            }
            jobs.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
    }

    @Test fun boundedProtobufParserRejectsTruncationOverflowAndDuplicateFields() {
        for (bad in listOf(byteArrayOf(10, 5, 1), byteArrayOf(8, -128), byteArrayOf(8, 1, 8, 2),
            byteArrayOf(8) + ByteArray(10) { -1 }, byteArrayOf(0))) {
            assertThrows(IllegalArgumentException::class.java) { IccoaFrame.Fields(bad) }
        }
    }

    @Test fun badHeaderReportsMetadataBeforeRejectingWithoutDumpingPayload() {
        val secret = "do-not-log-payload"
        val packet = IccoaFrame.frame(3, 1, 2, secret.toByteArray(), 1)
            .also { it[4] = (it[4].toInt() xor 1).toByte() }
        val messages = mutableListOf<String>()
        val error = assertThrows(IccoaHandshakeException::class.java) {
            IccoaFrame.read(ByteArrayInputStream(packet), messages::add)
        }
        assertEquals(IccoaHandshakeException.Reason.HEADER_CRC, error.reason)
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("category=3 method=1"))
        assertTrue(messages.single().contains("crcValid=false"))
        assertFalse(messages.single().contains(secret))
    }

    @Test fun fieldShapeOmitsBothBinaryAndNumericValues() {
        val fields = IccoaFrame.Fields(com.yegorkapomidorka.headswap.PbWriter()
            .string(2, "secret-data").int32(7, 654321).toByteArray())
        assertEquals("2:bytes(11),7:number", fields.shape())
    }
}
