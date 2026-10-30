package com.yegorkapomidorka.headswap.link

import com.yegorkapomidorka.headswap.Ch
import com.yegorkapomidorka.headswap.Msg
import com.yegorkapomidorka.headswap.Wire
import com.yegorkapomidorka.headswap.link.diag.WLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TransportTest {
    private val clock = { System.currentTimeMillis() }
    private val wl = Collections.synchronizedList(ArrayList<String>())

    @Before
    fun setUp() {
        WLog.clock = clock
        WLog.sink = { wl += it }
    }

    // ---------------- StreamTransport: то, что уходит в USB, не меняется ни на байт ----------------

    /** Пишет каждый write() отдельной записью, flush отмечает отдельно. */
    private class RecordingOut : OutputStream() {
        val calls = Collections.synchronizedList(ArrayList<ByteArray>())
        @Volatile var flushes = 0
        override fun write(b: Int) {
            calls += byteArrayOf(b.toByte())
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            calls += b.copyOfRange(off, off + len)
        }
        override fun flush() {
            flushes++
        }
    }

    private class BlockingIn : InputStream() {
        val latch = CountDownLatch(1)
        override fun read(): Int {
            latch.await()
            return -1
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            latch.await()
            return -1
        }
        override fun close() = latch.countDown()
    }

    @Test
    fun streamWritesExactlyWriteSequence() {
        val out = RecordingOut()
        val t = StreamTransport(BlockingIn(), out, TransportCaps.USB_AOA, "test", clock = clock)
        val written = CountDownLatch(3)
        t.start({ _, _ -> }, { _, _ -> written.countDown() }, { })
        val cmd = Wire.cmdInner(Msg.MD_INFO, ByteArray(300) { it.toByte() })
        val video = Wire.dataInner(Msg.VIDEO_DATA, 7, ByteArray(40_000) { (it * 31).toByte() })
        val media = Wire.dataInner(0x00030006, 1, ByteArray(4096))
        t.send(Ch.CMD, cmd)
        t.send(Ch.VIDEO, video)
        t.send(Ch.MEDIA, media)
        assertTrue(written.await(5, TimeUnit.SECONDS))
        val expected = Wire.writeSequence(Ch.CMD, cmd) + Wire.writeSequence(Ch.VIDEO, video) + Wire.writeSequence(Ch.MEDIA, media)
        assertEquals("число write()", expected.size, out.calls.size)
        for (i in expected.indices) assertArrayEquals("write #$i", expected[i], out.calls[i])
        assertEquals("flush на сообщение", 3, out.flushes)
        assertEquals(0L, t.queuedVideoBytes)
        assertEquals(0, t.queuedVideoFrames)
        t.close()
    }

    @Test
    fun streamDropQueuedRemovesOnlyMatchingMediaData() {
        // писатель не запущен: всё лежит в очереди. h425: из неё уходит только MEDIA_DATA, остальное на месте
        val out = RecordingOut()
        val t = StreamTransport(BlockingIn(), out, TransportCaps.USB_AOA, "test", clock = clock)
        val data = Wire.dataInner(0x00030006, 1, ByteArray(4096))
        val pause = Wire.dataInner(0x00030003, 0, ByteArray(0))
        val tts = Wire.dataInner(0x00040006, 1, ByteArray(512))
        val video = Wire.dataInner(Msg.VIDEO_DATA, 7, ByteArray(1000))
        t.send(Ch.MEDIA, data)
        t.send(Ch.VIDEO, video)
        t.send(Ch.MEDIA, data)
        t.send(4, tts)
        t.send(Ch.MEDIA, pause)
        val dropped = t.dropQueued(Ch.MEDIA) { it.size >= 12 && Wire.u32(it, 8) == 0x00030006 }
        assertEquals(2, dropped)
        assertEquals(3, t.queueSize(Ch.MEDIA))
        assertEquals(1, t.queuedVideoFrames)
        t.close()
    }

    @Test
    fun streamReadsFramesAndReportsEof() {
        val pin = PipedInputStream(1 shl 16)
        val pout = PipedOutputStream(pin)
        var fdClosed = false
        val t = StreamTransport(pin, ByteArrayOutputStream(), TransportCaps.USB_AOA, "test", closeFd = { fdClosed = true }, clock = clock)
        val got = Collections.synchronizedList(ArrayList<Pair<Int, ByteArray>>())
        val reason = AtomicReference<String>()
        val closedLatch = CountDownLatch(1)
        t.start({ c, i -> got += c to i }, { _, _ -> }, { reason.set(it); closedLatch.countDown() })
        val ver = Wire.cmdInner(Msg.HU_PROTOCOL_VERSION, byteArrayOf(8, 2, 16, 0))
        val hb = Wire.dataInner(Msg.VIDEO_HEARTBEAT, 0, null)
        val wire = Wire.outerHeader(Ch.CMD, ver.size) + ver + Wire.outerHeader(Ch.VIDEO, hb.size) + hb
        for (b in wire) pout.write(b.toInt())   // по байту: границы чтения не важны
        pout.close()
        assertTrue(closedLatch.await(5, TimeUnit.SECONDS))
        assertEquals("EOF чтения (ГУ отключилось)", reason.get())
        assertEquals(2, got.size)
        assertEquals(Ch.CMD, got[0].first)
        assertArrayEquals(ver, got[0].second)
        assertEquals(Ch.VIDEO, got[1].first)
        assertEquals(wire.size.toLong(), t.rxBytes)
        t.close()
        assertTrue("close() закрывает дескриптор", fdClosed)
    }

    @Test
    fun streamCloseDoesNotCallOnClosed() {
        val inp = BlockingIn()
        val t = StreamTransport(inp, ByteArrayOutputStream(), TransportCaps.USB_AOA, "test", clock = clock)
        var calls = 0
        t.start({ _, _ -> }, { _, _ -> }, { calls++ })
        t.close()
        t.close()
        Thread.sleep(100)
        assertEquals(0, calls)
    }

    // ---------------- InnerCodec: inner без внешнего заголовка ----------------

    /** Отдаёт по одному байту за read: проверка сборки заголовка и тела из кусков. */
    private class Trickle(private val b: ByteArray) : InputStream() {
        private var p = 0
        override fun read(): Int = if (p < b.size) b[p++].toInt() and 0xFF else -1
        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            if (p >= b.size) return -1
            buf[off] = b[p++]
            return 1
        }
    }

    @Test
    fun codecReadsCmdStream() {
        val a = Wire.cmdInner(Msg.HU_PROTOCOL_VERSION, byteArrayOf(8, 2, 16, 0))
        val b = Wire.cmdInner(Msg.HU_INFO, ByteArray(700) { 1 })
        val c = Wire.cmdInner(Msg.VIDEO_ENCODER_START, null)
        val got = readInners(Ch.CMD, Trickle(a + b + c))
        assertEquals(3, got.size)
        assertArrayEquals(a, got[0])
        assertArrayEquals(b, got[1])
        assertArrayEquals(c, got[2])
    }

    @Test
    fun codecReadsDataStream() {
        val hb = Wire.dataInner(Msg.VIDEO_HEARTBEAT, 0, null)
        val frame = Wire.dataInner(Msg.VIDEO_DATA, 5, ByteArray(100_000) { it.toByte() })
        val got = readInners(Ch.VIDEO, ByteArrayInputStream(hb + frame + hb))
        assertEquals(3, got.size)
        assertArrayEquals(frame, got[1])
        val mic = Wire.dataInner(0x00058001, 0, ByteArray(640))
        assertArrayEquals(mic, readInners(Ch.VR, Trickle(mic)).single())
    }

    @Test
    fun codecRejectsForeignType() {
        // CMD-тип на TOUCH-сокете: заголовок чужого канала = разрыв, а не резинк
        val wrong = Wire.cmdInner(Msg.HU_INFO, byteArrayOf(1, 2))
        try {
            readInners(Ch.TOUCH, ByteArrayInputStream(wrong))
            fail()
        } catch (e: BadInnerHeader) {
            assertTrue(e.message!!.contains("TOUCH"))
        }
    }

    @Test
    fun codecSkipsHeartbeatOnCmd() {
        // h129, VW CNS3 Tiguan L Pro: 0x20002 с длиной 0 в CMD-сокете, CMD-кадр 8 байт
        val hb = Wire.cmdInner(Msg.VIDEO_HEARTBEAT, null)
        val a = Wire.cmdInner(0x0001800F, ByteArray(12) { 3 })
        val b = Wire.cmdInner(Msg.VIDEO_ENCODER_START, null)
        val want = listOf(hb, a, hb, b, hb).map { it.toList() }
        val wire = hb + a + hb + b + hb
        for (s in listOf<InputStream>(ByteArrayInputStream(wire), Trickle(wire))) {
            assertEquals(want, readInners(Ch.CMD, BufferedInputStream(s)).map { it.toList() })
        }
        // 12-байтный видео-heartbeat [0][тип][метка] в CMD-сокете: хвост метки пропущен, поток не сбит
        val hb12 = ByteArray(12)
        Wire.putU32(hb12, 4, Msg.VIDEO_HEARTBEAT)
        Wire.putU32(hb12, 8, 0x12345678)
        val got = readInners(Ch.CMD, BufferedInputStream(ByteArrayInputStream(hb12 + a + b)))
        assertEquals(3, got.size)
        assertArrayEquals(a, got[1])
        assertArrayEquals(b, got[2])
    }

    @Test
    fun codecRejectsHeartbeatWithBodyOnCmd() {
        val bad = Wire.cmdInner(Msg.VIDEO_HEARTBEAT, ByteArray(4))
        try {
            readInners(Ch.CMD, ByteArrayInputStream(bad))
            fail()
        } catch (e: BadInnerHeader) {
            assertTrue(e.message!!.contains("заголовок 00 04"))
        }
    }

    @Test
    fun codecRejectsHugeLength() {
        val h = ByteArray(12)
        Wire.putU32(h, 0, Wire.MAX_OUTER_LEN + 1)
        Wire.putU32(h, 8, Msg.VIDEO_DATA)
        try {
            readInners(Ch.VIDEO, ByteArrayInputStream(h))
            fail()
        } catch (e: BadInnerHeader) {
            assertTrue(e.message!!.contains("длина"))
        }
        Wire.putU32(h, 0, -5)
        try {
            readInners(Ch.VIDEO, ByteArrayInputStream(h))
            fail()
        } catch (_: BadInnerHeader) {
        }
    }

    @Test
    fun codecEofSemantics() {
        assertNull(InnerCodec.read(Ch.CMD, DataInputStream(ByteArrayInputStream(ByteArray(0)))))
        val m = Wire.cmdInner(Msg.HU_INFO, ByteArray(50))
        for (cut in listOf(3, 8, 20)) {
            try {
                InnerCodec.read(Ch.CMD, DataInputStream(ByteArrayInputStream(m.copyOf(cut))))
                fail("cut=$cut")
            } catch (_: EOFException) {
            }
        }
    }

    // ---------------- Tcp6Listener + MultiSocketTransport на loopback ----------------

    private val zeroPorts = Tcp6Ports.DEFAULT.mapValues { 0 }

    private class Hu(val socks: Map<Int, Socket>) {
        fun send(chan: Int, inner: ByteArray) {
            socks.getValue(chan).getOutputStream().apply { write(inner); flush() }
        }
        fun readExactly(chan: Int, n: Int): ByteArray {
            val b = ByteArray(n)
            DataInputStream(socks.getValue(chan).getInputStream()).readFully(b)
            return b
        }
        fun close() = socks.values.forEach { try { it.close() } catch (_: Exception) {} }
    }

    private fun connect(l: Tcp6Listener, chans: Collection<Int>): Map<Int, Socket> =
        chans.associateWith { c -> Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), l.boundPort(c)), 2000) } }

    private fun up(): Triple<Tcp6Listener, MultiSocketTransport, Hu> {
        val l = Tcp6Listener(InetAddress.getLoopbackAddress(), zeroPorts, dataPort = 0, clock = clock).open()
        val hu = Hu(connect(l, Tcp6Ports.DEFAULT.keys))
        val t = l.await(5000, "test")
        return Triple(l, t, hu)
    }

    @Test
    fun tcp6RoundTripWithoutOuterHeader() {
        val (_, t, hu) = up()
        val got = Collections.synchronizedList(ArrayList<Pair<Int, ByteArray>>())
        val two = CountDownLatch(2)
        t.start({ c, i -> got += c to i; two.countDown() }, { _, _ -> }, { })
        try {
            val ver = Wire.cmdInner(Msg.HU_PROTOCOL_VERSION, byteArrayOf(8, 2, 16, 0))
            val touch = Wire.cmdInner(Msg.TOUCH_ACTION, byteArrayOf(8, 0, 16, 10, 24, 20))
            hu.send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_HEARTBEAT, 0, null))   // вычитывается и не доходит до протокола
            hu.send(Ch.CMD, ver)
            hu.send(Ch.TOUCH, touch)
            assertTrue(two.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertEquals(2, got.size)
            assertEquals(1L, t.heartbeats)
            val byChan = got.associate { it.first to it.second }
            assertArrayEquals(ver, byChan[Ch.CMD])
            assertArrayEquals(touch, byChan[Ch.TOUCH])

            val match = Wire.cmdInner(Msg.PROTOCOL_VERSION_MATCH_STATUS, byteArrayOf(8, 1))
            val frame = Wire.dataInner(Msg.VIDEO_DATA, 3, ByteArray(70_000) { it.toByte() })
            t.send(Ch.CMD, match)
            t.send(Ch.VIDEO, frame)
            assertArrayEquals("CMD-сокет: голый inner", match, hu.readExactly(Ch.CMD, match.size))
            assertArrayEquals("VIDEO-сокет: голый inner", frame, hu.readExactly(Ch.VIDEO, frame.size))
        } finally {
            t.close()
            hu.close()
        }
    }

    @Test
    fun tcp6SecondConnectionOnSamePortIsRejected() {
        val (l, t, hu) = up()
        t.start({ _, _ -> }, { _, _ -> }, { })
        try {
            val dup = Socket()
            dup.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), l.boundPort(Ch.CMD)), 2000)
            dup.soTimeout = 3000
            assertEquals("второй CMD закрыт сразу", -1, try { dup.getInputStream().read() } catch (_: java.net.SocketException) { -1 })
            dup.close()
            // первый CMD жив
            t.send(Ch.CMD, Wire.cmdInner(Msg.FOREGROUND, null))
            assertEquals(8, hu.readExactly(Ch.CMD, 8).size)
            assertTrue(wl.any { it.startsWith("WL tcp reject") && "why=dup" in it })
        } finally {
            t.close()
            hu.close()
        }
    }

    @Test
    fun tcp6OneSocketClosedClosesSessionWithReason() {
        val (_, t, hu) = up()
        val reason = AtomicReference<String>()
        val done = CountDownLatch(1)
        var calls = 0
        t.start({ _, _ -> }, { _, _ -> }, { calls++; reason.set(it); done.countDown() })
        try {
            hu.socks.getValue(Ch.TOUCH).close()
            assertTrue("сессия не зависла", done.await(5, TimeUnit.SECONDS))
            assertTrue(reason.get(), reason.get().startsWith("tcp6 TOUCH("))
            // остальные сокеты транспорт закрыл сам
            val cmd = hu.socks.getValue(Ch.CMD)
            cmd.soTimeout = 3000
            assertEquals(-1, try { cmd.getInputStream().read() } catch (_: java.net.SocketException) { -1 })
            t.close()
            Thread.sleep(100)
            assertEquals("onClosed ровно один раз", 1, calls)
            assertTrue(wl.any { it.startsWith("WL tcp close by=link chan=TOUCH") })
        } finally {
            hu.close()
        }
    }

    @Test
    fun tcp6BadHeaderClosesWithReason() {
        val (_, t, hu) = up()
        val reason = AtomicReference<String>()
        val done = CountDownLatch(1)
        t.start({ _, _ -> }, { _, _ -> }, { reason.set(it); done.countDown() })
        try {
            hu.send(Ch.MEDIA, Wire.dataInner(Msg.VIDEO_DATA, 0, ByteArray(4)))   // тип видео на MEDIA-сокете
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(reason.get(), reason.get().startsWith("tcp6 MEDIA(") && "плохой заголовок" in reason.get())
        } finally {
            t.close()
            hu.close()
        }
    }

    @Test
    fun tcp6TimeoutNamesMissingSockets() {
        val l = Tcp6Listener(InetAddress.getLoopbackAddress(), zeroPorts, dataPort = null, clock = clock).open()
        val hu = Hu(connect(l, listOf(Ch.CMD, Ch.VIDEO, Ch.MEDIA)))
        try {
            l.await(700, "test")
            fail()
        } catch (e: LinkSetupException) {
            assertTrue(e.message, "3/6" in e.message!!)
            assertTrue(e.message, "TTS(0)" in e.message!! && "VR(0)" in e.message!! && "TOUCH(0)" in e.message!!)
            assertTrue(wl.any { it.startsWith("WL tcp timeout ready=3/6") })
        } finally {
            hu.close()
        }
    }

    @Test
    fun tcp6PeerFilterRejectsForeignSubnet() {
        // ветка B: пускаем только адреса из подсетей маяка; loopback сюда не входит
        val l = Tcp6Listener(InetAddress.getLoopbackAddress(), zeroPorts, dataPort = null, clock = clock, peerOk = { false }).open()
        val s = Socket()
        try {
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), l.boundPort(Ch.CMD)), 2000)
            s.soTimeout = 3000
            assertEquals(-1, try { s.getInputStream().read() } catch (_: java.net.SocketException) { -1 })
            assertTrue(wl.any { it.startsWith("WL tcp reject chan=CMD") && "why=subnet" in it })
        } finally {
            s.close()
            l.abort()
        }
    }

    // ---------------- WLog ----------------

    @Test
    fun wlogFormat() {
        assertEquals(
            "WL tcp accept chan=CMD port=7240 peer=192.168.43.2 ready=1/6 t=15",
            WLog.format("tcp", "accept", 15, listOf("chan" to "CMD", "port" to 7240, "peer" to "192.168.43.2", "ready" to "1/6")),
        )
        assertEquals("WL link end why=\"EOF чтения (ГУ отключилось)\" x=- t=0",
            WLog.format("link", "end", 0, listOf("why" to "EOF чтения (ГУ отключилось)", "x" to null)))
        assertEquals("AA:BB:CC:xx:xx:xx", WLog.maskMac("AA:BB:CC:DD:EE:FF"))
        assertEquals("?", WLog.maskMac("garbage"))
    }

    @Test
    fun wlogHasNoHexDumps() {
        // транспортные WL-строки не содержат байтов рукопожатия: ни одной последовательности из 4+ hex-пар
        tcp6RoundTripWithoutOuterHeader()
        val hex = Regex("""(\b[0-9a-fA-F]{2}\s){4,}""")
        for (line in wl) assertTrue(line, !hex.containsMatchIn(line))
    }
}
