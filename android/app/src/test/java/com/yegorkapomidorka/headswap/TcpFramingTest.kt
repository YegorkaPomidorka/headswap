package com.yegorkapomidorka.headswap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream

/** ADB-режим: чтение «голых» inner-сообщений из TCP-потока канала (без внешнего заголовка). */
class TcpFramingTest {

    private fun stream(vararg parts: ByteArray) = ByteArrayInputStream(parts.fold(ByteArray(0)) { a, b -> a + b })

    /** Поток, отдающий по одному байту за read: сообщение приходит кусками как угодно. */
    private class Trickle(private val data: ByteArray) : InputStream() {
        private var p = 0
        override fun read(): Int = if (p < data.size) data[p++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val c = read()
            if (c < 0) return -1
            b[off] = c.toByte()
            return 1
        }
    }

    @Test
    fun cmdMessage() {
        val inner = Wire.cmdInner(Msg.HU_PROTOCOL_VERSION, Msgs.protocolVersion(1, 0))
        val got = TcpFraming.readInner(stream(inner), Ch.CMD)
        assertArrayEquals(inner, got)
    }

    @Test
    fun cmdMessageWithEmptyBody() {
        val inner = Wire.cmdInner(Msg.SCREEN_ON, null)
        assertEquals(8, inner.size)
        assertArrayEquals(inner, TcpFraming.readInner(stream(inner), Ch.TOUCH))
    }

    @Test
    fun dataMessage() {
        val inner = Wire.dataInner(Msg.VIDEO_DATA, 1234, ByteArray(300) { it.toByte() })
        val got = TcpFraming.readInner(stream(inner), Ch.VIDEO)
        assertArrayEquals(inner, got)
    }

    @Test
    fun twoMessagesBackToBackThenCleanEof() {
        val a = Wire.cmdInner(Msg.HU_PROTOCOL_VERSION, Msgs.protocolVersion(1, 0))
        val b = Wire.cmdInner(Msg.PROTOCOL_VERSION_MATCH_STATUS, Msgs.matchStatus(1))
        val s = stream(a, b)
        assertArrayEquals(a, TcpFraming.readInner(s, Ch.CMD))
        assertArrayEquals(b, TcpFraming.readInner(s, Ch.CMD))
        assertNull(TcpFraming.readInner(s, Ch.CMD))
    }

    @Test
    fun cleanEofOnEmptyStream() {
        assertNull(TcpFraming.readInner(stream(), Ch.MEDIA))
    }

    @Test
    fun splitIntoOneByteReads() {
        val inner = Wire.dataInner(Msg.VIDEO_DATA, 7, ByteArray(50) { (it * 3).toByte() })
        assertArrayEquals(inner, TcpFraming.readInner(Trickle(inner), Ch.VIDEO))
    }

    @Test
    fun eofInMiddleOfBody() {
        val inner = Wire.dataInner(Msg.VIDEO_DATA, 1, ByteArray(100))
        try {
            TcpFraming.readInner(stream(inner.copyOf(60)), Ch.VIDEO)
            fail("ожидали EOFException")
        } catch (_: EOFException) {
        }
    }

    @Test
    fun eofInMiddleOfHeader() {
        val inner = Wire.cmdInner(Msg.HU_PROTOCOL_VERSION, Msgs.protocolVersion(1, 0))
        try {
            TcpFraming.readInner(stream(inner.copyOf(5)), Ch.CMD)
            fail("ожидали EOFException")
        } catch (_: EOFException) {
        }
    }

    @Test
    fun brokenDeclaredOnDataChannel() {
        // declared больше MAX_OUTER_LEN
        val tooBig = Wire.dataInner(Msg.VIDEO_DATA, 0, null).also { Wire.putU32(it, 0, Wire.MAX_OUTER_LEN + 1) }
        // declared как отрицательный int (0xFFFFFFFF)
        val negative = Wire.dataInner(Msg.VIDEO_DATA, 0, null).also { Wire.putU32(it, 0, -1) }
        for (bad in listOf(tooBig, negative)) {
            try {
                TcpFraming.readInner(stream(bad), Ch.VIDEO)
                fail("ожидали ProtocolDesync")
            } catch (e: ProtocolDesync) {
                assertTrue(e.message!!, e.message!!.contains(Hex.compact(bad, 12)))
            }
        }
    }

    @Test
    fun maxDeclaredOnDataChannelIsAccepted() {
        // граница: ровно MAX_OUTER_LEN не рассинхрон, дальше упрёмся в EOF тела
        val hdr = Wire.dataInner(Msg.VIDEO_DATA, 0, null).also { Wire.putU32(it, 0, Wire.MAX_OUTER_LEN) }
        try {
            TcpFraming.readInner(stream(hdr), Ch.VIDEO)
            fail("ожидали EOFException")
        } catch (e: EOFException) {
            assertNotNull(e.message)
        }
    }
}
