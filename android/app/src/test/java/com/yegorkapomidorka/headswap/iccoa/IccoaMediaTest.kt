// Derived from FT (github.com/OneAboveAll1964/ft-app), Apache-2.0: IccoaMediaTest.kt. Изменено для Headswap; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer

class IccoaMediaTest {
    private fun rtp(bytes: ByteArray): List<ByteArray> {
        val stream = DataInputStream(ByteArrayInputStream(bytes))
        val result = mutableListOf<ByteArray>()
        while (stream.available() > 0) {
            val length = stream.readUnsignedShort()
            assertTrue(length in 200..1328)
            result += ByteArray(length).also(stream::readFully)
        }
        return result
    }

    @Test fun transportPreservesNalDataAndPresentationClock() {
        val output = ByteArrayOutputStream()
        val muxer = IccoaTsMuxer(output)
        val config = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1f, 0, 0, 0, 1, 0x68, 0x12)
        val nal = ByteArray(2300) { (it * 13).toByte() }.apply { this[0] = 0x65 }
        muxer.configure(config)
        muxer.video(ByteBuffer.allocate(nal.size + 4).putInt(nal.size).put(nal).array(), true, 1_234_000)
        val packets = rtp(output.toByteArray())
        assertTrue(packets.size >= 2)
        val sequence = ByteBuffer.wrap(packets.first()).getShort(2).toInt() and 65535
        val video = ByteArrayOutputStream()
        val counters = mutableMapOf<Int, Int>()
        packets.forEachIndexed { index, packet ->
            assertEquals(0x80, packet[0].toInt() and 255)
            assertEquals(33, packet[1].toInt() and 127)
            assertEquals(index == packets.lastIndex, packet[1].toInt() and 128 != 0)
            assertEquals((sequence + index) and 65535, ByteBuffer.wrap(packet).getShort(2).toInt() and 65535)
            assertEquals(111060, ByteBuffer.wrap(packet).getInt(4))
            assertEquals(0, (packet.size - 12) % 188)
            (12 until packet.size step 188).forEach { at ->
                val ts = packet.copyOfRange(at, at + 188)
                assertEquals(0x47, ts[0].toInt())
                val pid = ((ts[1].toInt() and 31) shl 8) or (ts[2].toInt() and 255)
                val counter = ts[3].toInt() and 15
                counters[pid]?.let { assertEquals((it + 1) and 15, counter) }
                counters[pid] = counter
                val payload = 4 + if (ts[3].toInt() and 32 != 0) 1 + (ts[4].toInt() and 255) else 0
                if (pid == 256) video.write(ts, payload, 188 - payload)
                if (pid == 0 || pid == 4096) {
                    val start = payload + 1 + (ts[payload].toInt() and 255)
                    val length = ((ts[start + 1].toInt() and 15) shl 8) or (ts[start + 2].toInt() and 255)
                    // ISO 13818 CRC has zero remainder when the transmitted CRC is included.
                    assertEquals(0, crc(ts.copyOfRange(start, start + length + 3)))
                }
            }
        }
        val pes = video.toByteArray()
        assertArrayEquals(byteArrayOf(0, 0, 1, 0xe0.toByte()), pes.copyOfRange(0, 4))
        val pts = ((pes[9].toLong() and 14) shl 29) or ((pes[10].toLong() and 255) shl 22) or
            ((pes[11].toLong() and 254) shl 14) or ((pes[12].toLong() and 255) shl 7) or ((pes[13].toLong() and 254) ushr 1)
        assertEquals(111060L, pts)
        assertArrayEquals(config + byteArrayOf(0, 0, 0, 1) + nal, pes.copyOfRange(14, pes.size))
    }

    @Test fun streamedPacketsPreserveLargeFramesAndExactSevenPacketBoundary() {
        // 898 bytes + PES header = five video TS packets, plus PAT/PMT = seven.
        for (size in listOf(898, 899, 1_000_000)) {
            val output = ByteArrayOutputStream()
            val muxer = IccoaTsMuxer(output)
            val nal = ByteArray(size) { (it * 13).toByte() }.apply {
                this[0] = 0; this[1] = 0; this[2] = 0; this[3] = 1; this[4] = 0x65
            }
            muxer.video(nal, true, 1_000_000)
            val packets = rtp(output.toByteArray())
            if (size == 898) assertEquals(1, packets.size)
            if (size == 899) assertEquals(2, packets.size)
            val video = ByteArrayOutputStream()
            packets.forEachIndexed { index, packet ->
                assertEquals(index == packets.lastIndex, packet[1].toInt() and 128 != 0)
                for (at in 12 until packet.size step 188) {
                    val pid = ((packet[at + 1].toInt() and 31) shl 8) or (packet[at + 2].toInt() and 255)
                    if (pid == 256) {
                        val adaptation = if (packet[at + 3].toInt() and 32 != 0) 1 + (packet[at + 4].toInt() and 255) else 0
                        video.write(packet, at + 4 + adaptation, 184 - adaptation)
                    }
                }
            }
            assertArrayEquals(nal, video.toByteArray().drop(14).toByteArray())
            // Reusing the buffers for another access unit must not send old TS bytes.
            output.reset()
            muxer.audio(byteArrayOf(1, 2, 3), 1_020_000)
            muxer.video(byteArrayOf(0, 0, 0, 1, 0x61, 42), false, 1_033_333)
            val next = rtp(output.toByteArray())
            assertEquals(2, next.size)
            assertTrue(next.all { it.size == 200 && it[1].toInt() and 128 != 0 })
        }
    }

    private fun pesFor(bytes: ByteArray, pid: Int): ByteArray {
        val result = ByteArrayOutputStream()
        rtp(bytes).forEach { packet ->
            for (at in 12 until packet.size step 188) {
                val current = ((packet[at + 1].toInt() and 31) shl 8) or (packet[at + 2].toInt() and 255)
                if (current == pid) {
                    val adaptation = if (packet[at + 3].toInt() and 32 != 0)
                        1 + (packet[at + 4].toInt() and 255) else 0
                    result.write(packet, at + 4 + adaptation, 184 - adaptation)
                }
            }
        }
        return result.toByteArray()
    }

    @Test fun configPrefixCrossesTsBoundariesWithoutChangingPesPayload() {
        for (size in listOf(5, 161, 162, 163, 345, 346, 347, 700)) {
            val output = ByteArrayOutputStream()
            val muxer = IccoaTsMuxer(output)
            val config = byteArrayOf(0, 0, 0, 1, 0x67) + ByteArray(size - 5) { 42 }
            val frame = byteArrayOf(0, 0, 1, 0x65) + ByteArray(1200) { 73 }
            muxer.configure(config)
            muxer.video(frame, true, 0)
            val pes = pesFor(output.toByteArray(), 256)
            assertEquals(config.size + frame.size + 8,
                ((pes[4].toInt() and 255) shl 8) or (pes[5].toInt() and 255))
            assertArrayEquals(config + frame, pes.copyOfRange(14, pes.size))
        }
    }

    @Test fun spsAfterLongSeiIsNotDuplicatedAndLargePesLengthRemainsUnbounded() {
        val output = ByteArrayOutputStream()
        val muxer = IccoaTsMuxer(output)
        val config = byteArrayOf(0, 0, 0, 1, 0x67, 42, 0, 0, 1, 0x68, 43)
        val frame = byteArrayOf(0, 0, 1, 6) + ByteArray(600) { 55 } +
            config + byteArrayOf(0, 0, 1, 0x65) + ByteArray(70_000) { 42 }
        muxer.configure(config)
        muxer.video(frame, true, 0)
        val pes = pesFor(output.toByteArray(), 256)
        assertEquals(0, pes[4].toInt())
        assertEquals(0, pes[5].toInt())
        assertArrayEquals(frame, pes.copyOfRange(14, pes.size))
    }

    @Test fun bufferedOutputFlushesEveryFrameAndReusableAdtsHandlesSizeChanges() {
        val output = ByteArrayOutputStream()
        val muxer = IccoaTsMuxer(java.io.BufferedOutputStream(output, 65536))
        for (size in listOf(8184, 1, 162, 163, 164)) {
            output.reset()
            val audio = ByteArray(size) { (it * 11).toByte() }
            muxer.audio(audio, 1_000_000)
            val pes = pesFor(output.toByteArray(), 257)
            assertEquals(size + 21, pes.size)
            assertEquals(size + 15, ((pes[4].toInt() and 255) shl 8) or (pes[5].toInt() and 255))
            val length = ((pes[17].toInt() and 3) shl 11) or
                ((pes[18].toInt() and 255) shl 3) or ((pes[19].toInt() ushr 5) and 7)
            assertEquals(size + 7, length)
            assertArrayEquals(audio, pes.copyOfRange(21, pes.size))
        }
    }

    @Test fun annexBHandlesLengthPrefixedAndBareNals() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1, 0, 0, 0, 1, 0x61),
            IccoaTsMuxer.annexB(byteArrayOf(0, 0, 0, 2, 0x65, 1, 0, 0, 0, 1, 0x61)))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65), IccoaTsMuxer.annexB(byteArrayOf(0x65)))
    }

    @Test fun aacIsFramedAsStereo48KhzAdtsWithPresentationTimestamp() {
        val output = ByteArrayOutputStream()
        val aac = ByteArray(400) { (it * 7).toByte() }
        IccoaTsMuxer(output).audio(aac, 2_000_000)
        val pesBytes = ByteArrayOutputStream()
        rtp(output.toByteArray()).forEach { packet ->
            assertEquals(180000, ByteBuffer.wrap(packet).getInt(4))
            for (at in 12 until packet.size step 188) {
                val ts = packet.copyOfRange(at, at + 188)
                assertEquals(257, ((ts[1].toInt() and 31) shl 8) or (ts[2].toInt() and 255))
                val payload = 4 + if (ts[3].toInt() and 32 != 0) 1 + (ts[4].toInt() and 255) else 0
                pesBytes.write(ts, payload, 188 - payload)
            }
        }
        val pes = pesBytes.toByteArray()
        assertEquals(0xc0, pes[3].toInt() and 255)
        val pts = ((pes[9].toLong() and 14) shl 29) or ((pes[10].toLong() and 255) shl 22) or
            ((pes[11].toLong() and 254) shl 14) or ((pes[12].toLong() and 255) shl 7) or ((pes[13].toLong() and 254) ushr 1)
        assertEquals(180000L, pts)
        val adts = pes.copyOfRange(14, 21)
        assertEquals(0xff, adts[0].toInt() and 255)
        assertEquals(1, (adts[2].toInt() ushr 6) and 3) // AAC-LC object type minus one
        assertEquals(3, (adts[2].toInt() ushr 2) and 15) // 48 kHz
        assertEquals(2, ((adts[2].toInt() and 1) shl 2) or ((adts[3].toInt() ushr 6) and 3))
        assertEquals(aac.size + 7, ((adts[3].toInt() and 3) shl 11) or
            ((adts[4].toInt() and 255) shl 3) or ((adts[5].toInt() ushr 5) and 7))
        assertArrayEquals(aac, pes.copyOfRange(21, pes.size))
    }

    @Test fun rtspRejectsOversizedAndConflictingMessages() {
        listOf("RTSP/1.0 200 OK\r\nContent-Length: 65537\r\n\r\n",
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\ncseq: 2\r\n\r\n").forEach { text ->
            assertThrows(IllegalArgumentException::class.java) {
                IccoaRtsp.read(DataInputStream(ByteArrayInputStream(text.toByteArray())))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            IccoaRtsp.capabilities(IccoaVideoConfig("127.0.0.1"), "display=99999:720;dpi=200;fps=30")
        }
    }

    @Test fun rtspCombinesRepeatedListHeadersInWireOrder() {
        val input = "RTSP/1.0 200 OK\r\nCSeq: 7\r\nPublic: SETUP, PLAY\r\npublic: PAUSE\r\n" +
            "Require: org.wfa.wfd1.0\r\nrequire: another.extension\r\n\r\n"
        val message = IccoaRtsp.read(DataInputStream(ByteArrayInputStream(input.toByteArray())))
        assertEquals("SETUP, PLAY, PAUSE", message.headers["public"])
        assertEquals("org.wfa.wfd1.0, another.extension", message.headers["require"])
    }

    @Test fun rtspIdenticalSingletonsPreserveBodyAndFollowingMessage() {
        val input = "RTSP/1.0 200 OK\r\nCSeq: 7\r\ncseq: 7\r\nContent-Length: 4\r\ncontent-length: 4\r\n\r\nbody" +
            "GET_PARAMETER * RTSP/1.0\r\nCSeq: 8\r\n\r\n"
        val stream = DataInputStream(ByteArrayInputStream(input.toByteArray()))
        val first = IccoaRtsp.read(stream)
        assertEquals("7", first.headers["cseq"])
        assertEquals("body", first.body)
        val second = IccoaRtsp.read(stream)
        assertEquals("GET_PARAMETER * RTSP/1.0", second.first)
        assertEquals("8", second.headers["cseq"])
        assertEquals(0, stream.available())
    }

    @Test fun rtspRejectsConflictingSingletonsAndRetainsHeaderLimits() {
        listOf("CSeq: 1\r\nCSeq: 2", "Content-Length: 4\r\nContent-Length: 5",
            "Session: 1\r\nSession: 2", "Transport: RTP/AVP/TCP;port=1\r\nTransport: RTP/AVP/TCP;port=2",
            (1..65).joinToString("\r\n") { "Public: PLAY" }).forEach { headers ->
            val input = "RTSP/1.0 200 OK\r\n$headers\r\n\r\n"
            assertThrows(IllegalArgumentException::class.java) {
                IccoaRtsp.read(DataInputStream(ByteArrayInputStream(input.toByteArray())))
            }
        }
    }

    /** Эталонная машина без сокета: то, что автомат отдал в send, разбирается тем же read. */
    private class FakeCar(var now: Long = 0) {
        val sent = ArrayDeque<IccoaRtsp.Message>()
        var played: IccoaVideoConfig? = null
        var configured: IccoaVideoConfig? = null
        var pauses = 0
        var keyframes = 0
        val rtsp = IccoaRtsp("192.168.50.1", "192.168.50.20",
            send = { sent += parse(it) }, onPlay = { played = it }, onPause = { pauses++ },
            onKeyframe = { keyframes++ }, onConfig = { configured = it }, clockMs = { now })

        fun read(): IccoaRtsp.Message = sent.removeFirst()
        fun send(text: String) = rtsp.onMessage(parse(text))
        fun reply(message: IccoaRtsp.Message, body: String = "") =
            send("RTSP/1.0 200 OK\r\nCSeq: ${message.headers["cseq"]}\r\nContent-Length: ${body.length}\r\n\r\n$body")

        /** M1–M7 до PLAY. */
        fun negotiate() {
            rtsp.start()
            val options = read()
            assertEquals("OPTIONS * RTSP/1.0", options.first)
            assertEquals("org.wfa.wfd1.0", options.headers["require"])
            reply(options)
            send("OPTIONS * RTSP/1.0\r\nCSeq: 20\r\n\r\n")
            assertTrue(read().first.startsWith("RTSP/1.0 200"))
            val get = read()
            assertTrue(get.first.startsWith("GET_PARAMETER"))
            assertTrue(get.body.contains("wfd_car_display_mode"))
            reply(get, "wfd_car_display_mode: display=2422:1272;dpi=200;fps=60\r\nwfd_client_rtp_ports: RTP/AVP/TCP;unicast 15550 0 mode=play\r\n")
            assertEquals(IccoaVideoConfig("192.168.50.1", 2422, 1272, 60, 15550, 200), configured)
            val settings = read()
            assertTrue(settings.first.startsWith("SET_PARAMETER"))
            assertTrue(settings.body.contains("port=4321;uibc_encrypted=true"))
            assertTrue(settings.body.contains("wfd_audio_codecs: AAC 00000001 00"))
            assertTrue(settings.body.contains("wfd_client_rtp_ports: RTP/AVP/TCP;unicast 15550 0 mode=play"))
            assertTrue(settings.body.contains("rtsp://192.168.50.20/wfd1.0/streamid=0"))
            reply(settings)
            val trigger = read()
            assertEquals("wfd_trigger_method: SETUP\r\n", trigger.body)
            reply(trigger)
            send("SETUP rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 21\r\nTransport: RTP/AVP/TCP;unicast;client_port=23456\r\n\r\n")
            assertTrue(read().headers["session"]!!.startsWith("1"))
            assertNull(played)
            send("PLAY rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 22\r\nSession: 1\r\n\r\n")
            assertEquals("22", read().headers["cseq"])
        }

        companion object {
            fun parse(text: String) = IccoaRtsp.read(DataInputStream(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1))))
        }
    }

    @Test fun fakeCarCompletesWfdNegotiationWithoutSockets() {
        val car = FakeCar()
        car.negotiate()
        assertEquals(IccoaVideoConfig("192.168.50.1", 2422, 1272, 60, 23456, 200), car.played)
        assertEquals(car.played, car.rtsp.config)
        car.send("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 23\r\nContent-Length: 17\r\n\r\nwfd_idr_request\r\n")
        assertTrue(car.read().first.startsWith("RTSP/1.0 200"))
        assertEquals(1, car.keyframes)
        car.send("PAUSE rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 24\r\n\r\n")
        car.read()
        assertEquals(1, car.pauses)
        car.send("DESCRIBE rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 25\r\n\r\n")
        assertTrue(car.read().first.startsWith("RTSP/1.0 405"))
        assertFalse(car.rtsp.tornDown)
        car.send("TEARDOWN rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 26\r\n\r\n")
        car.read()
        assertTrue(car.rtsp.tornDown)
        assertTrue(car.sent.isEmpty())
    }

    @Test fun keepAliveStartsAfterPlayRepeatsEveryTwentySecondsAndTimesOutAfterTen() {
        // До PLAY keep-alive не идёт, сколько бы ни прошло.
        val waiting = FakeCar(now = 1_000)
        waiting.rtsp.start()
        waiting.now += 60_000
        assertTrue(waiting.rtsp.tick())
        assertEquals(1, waiting.sent.size)
        val idle = FakeCar(now = 1_000).also { it.negotiate() }
        idle.now += 19_999
        assertTrue(idle.rtsp.tick())
        assertTrue(idle.sent.isEmpty())
        idle.now += 1
        assertTrue(idle.rtsp.tick())
        val m16 = idle.read()
        assertEquals("GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", m16.first)
        assertEquals("1", m16.headers["session"])
        idle.now += 5_000
        assertTrue(idle.rtsp.tick())
        assertTrue(idle.sent.isEmpty())
        idle.reply(m16)
        idle.now += 20_000
        assertTrue(idle.rtsp.tick())
        idle.read()
        idle.now += 10_001
        assertFalse(idle.rtsp.tick())
    }

    @Test fun protocolViolationsThrowInsteadOfContinuing() {
        val early = FakeCar()
        early.rtsp.start()
        assertThrows(IllegalStateException::class.java) {
            early.send("PLAY rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 22\r\n\r\n")
        }
        assertThrows(IllegalStateException::class.java) { early.send("OPTIONS * RTSP/1.0\r\n\r\n") }
        assertThrows(IllegalArgumentException::class.java) {
            early.send("SETUP rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 3\r\nTransport: RTP/AVP/UDP;unicast;client_port=1\r\n\r\n")
        }
        val rejected = FakeCar()
        rejected.rtsp.start()
        assertThrows(IllegalArgumentException::class.java) {
            rejected.send("RTSP/1.0 400 Bad Request\r\nCSeq: ${rejected.read().headers["cseq"]}\r\n\r\n")
        }
    }

    @Test fun fragmentedInputKeepsObjectBoundariesInsideStrings() {
        val values = mutableListOf<Int>()
        val parser = IccoaInputJson { values += it.getInt("type") }
        parser.accept("{\"type\":1,\"text\":\"}\\\"{\"".toByteArray())
        parser.accept("}{\"type\":2}".toByteArray())
        assertEquals(listOf(1, 2), values)
        assertThrows(IllegalArgumentException::class.java) { parser.accept(ByteArray(65537) { 32 }) }
    }

    private fun crc(bytes: ByteArray): Int {
        var value = -1
        bytes.forEach { byte ->
            value = value xor ((byte.toInt() and 255) shl 24)
            repeat(8) { value = if (value and Int.MIN_VALUE != 0) (value shl 1) xor 0x04c11db7 else value shl 1 }
        }
        return value
    }
}
