package com.yegorkapomidorka.headswap.iccoa

import com.yegorkapomidorka.headswap.PbWriter
import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import com.yegorkapomidorka.headswap.aa.CustomVideoMode
import com.yegorkapomidorka.headswap.aa.FakePeer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyAgreement

/** Сессия целиком по localhost против «машины» из ядра протокола: вход, каналы, RTSP, RTP/TS, UIBC, повторный вход. */
class IccoaSessionTest {
    private val loop = InetAddress.getByName("127.0.0.1")
    private val logs = CopyOnWriteArrayList<String>()
    private val toClose = ArrayList<Closeable>()

    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xc0.toByte(), 0x1f, 0xda.toByte(), 0x01, 0x40)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xce.toByte(), 0x3c, 0x80.toByte())
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(3000) { (it * 7 + 1).toByte() }
    private val p1 = byteArrayOf(0, 0, 0, 1, 0x41) + ByteArray(300) { (it * 5 + 3).toByte() }

    @After fun tearDown() = toClose.forEach { runCatching { it.close() } }

    internal class MemoryTrust : IccoaTrust {
        override val clientId = "a1b2c3"
        override val mac = "02:00:00:00:00:01"
        private val pair = IccoaAuth.newKeyPair()
        val cars = HashMap<String, ByteArray>()
        override fun identity(): KeyPair = pair
        @Synchronized override fun carKey(car: String): ByteArray? = cars[car]
        @Synchronized override fun saveCarKey(car: String, key: ByteArray) { cars[car] = key }
    }

    /** Машина: сервер входа и приёмник RTP, клиент каналов телефона. Помнит клиентов, вошедших по PIN. */
    private inner class Car(private val pin: String) : Closeable {
        val identity = IccoaAuth.newKeyPair()
        val identityBytes = IccoaAuth.publicBytes(identity.public as ECPublicKey)
        val auth = ServerSocket(0, 2, loop)
        val rtp = ServerSocket(0, 2, loop)
        private val known = HashSet<String>()
        private val sockets = CopyOnWriteArrayList<Closeable>(listOf(auth, rtp))
        private var sequence = 1
        lateinit var cipher: IccoaFrame.Cipher
        lateinit var control: Socket
        lateinit var uibc: Socket
        var quick = false
        var confirm = ""
        /** Кадры control от телефона: метод и расшифрованное тело. */
        val controlRx = CopyOnWriteArrayList<Pair<Int, ByteArray>>()
        private val videoPes = ByteArrayOutputStream()

        /** Один вход на 57209; false — отказ с result=1. */
        fun signIn(): Boolean = auth.accept().use { s ->
            val input = s.getInputStream()
            val frame = IccoaFrame.read(input)
            assertEquals(IccoaFrame.CAT_AUTH to 1, frame.category to frame.method)
            val f = IccoaFrame.Fields(frame.body)
            val clientId = f.bytes(7).toString(Charsets.UTF_8)
            quick = f.optionalBytes(2) == null
            val accepted = if (quick) clientId in known
                else MessageDigest.isEqual(IccoaAuth.pinMac(pin, f.bytes(2), f.bytes(6)), f.bytes(3))
            fun reply(body: ByteArray) = s.getOutputStream().run {
                write(IccoaFrame.frame(IccoaFrame.CAT_AUTH, 2, IccoaFrame.TYPE_RESPONSE, body, frame.sequence)); flush()
            }
            if (!accepted) {
                reply(PbWriter().int32(7, 1).toByteArray())
                return false
            }
            val agreement = IccoaAuth.newKeyPair()
            val agreementBytes = IccoaAuth.publicBytes(agreement.public as ECPublicKey)
            val nonce = ByteArray(32) { (it + 1).toByte() }
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(identity.private)
                update(agreementBytes + f.bytes(4) + nonce + f.bytes(6))
                sign()
            }
            val shared = KeyAgreement.getInstance("ECDH").run {
                init(agreement.private)
                doPhase(IccoaAuth.publicKey(f.bytes(4)), true)
                generateSecret()
            }
            cipher = IccoaFrame.Cipher(IccoaAuth.hkdf(shared, IccoaAuth.sha256(f.bytes(6) + nonce), "session_key".toByteArray(), 16))
            val response = PbWriter().int32(1, 1793).bytes(2, identityBytes)
            if (!quick) response.bytes(3, IccoaAuth.pinMac(pin, identityBytes + f.bytes(2), nonce + f.bytes(6)))
            reply(response.bytes(4, agreementBytes).bytes(5, signature).bytes(6, nonce).int32(7, 0).toByteArray())
            val confirmFrame = IccoaFrame.read(input)
            assertEquals(IccoaFrame.CAT_AUTH to 3, confirmFrame.category to confirmFrame.method)
            confirm = cipher.decrypt(IccoaFrame.Fields(confirmFrame.body).bytes(1)).toString(Charsets.UTF_8)
            known += clientId
            true
        }

        private fun connect(port: Int) = Socket(loop, port).also { sockets += it }

        fun send(method: Int, body: ByteArray, type: Int = IccoaFrame.TYPE_NOTIFY) = synchronized(control) {
            control.getOutputStream().run {
                write(IccoaFrame.frame(IccoaFrame.CAT_CONTROL, method, type, cipher.encrypt(body), sequence++)); flush()
            }
        }

        /** Control: свой heartbeat и get_port датчиков, как машина Toyota. */
        fun openControl(port: Int) {
            control = connect(port)
            Thread {
                runCatching {
                    while (true) {
                        val frame = IccoaFrame.readOrNull(control.getInputStream()) ?: break
                        controlRx += frame.method to IccoaFrame.decode(IccoaFrame.CONTROL_PORT, frame, cipher).body
                    }
                }
            }.start()
            send(IccoaControl.HEARTBEAT, IccoaControl.heartbeat())
            send(IccoaControl.GET_PORT_REQUEST, byteArrayOf(0x08, 0x01), IccoaFrame.TYPE_REQUEST)
        }

        /** WFD-sink: M1–M7 до PLAY, потом приём RTP. */
        fun wfd(port: Int, display: String) {
            val socket = connect(port)
            val input = DataInputStream(socket.getInputStream())
            fun read() = IccoaRtsp.read(input)
            fun write(text: String) = socket.getOutputStream().run { write(text.toByteArray(Charsets.ISO_8859_1)); flush() }
            fun ok(m: IccoaRtsp.Message, body: String = "") =
                write("RTSP/1.0 200 OK\r\nCSeq: ${m.headers["cseq"]}\r\nContent-Length: ${body.length}\r\n\r\n$body")
            val m1 = read()
            assertEquals("OPTIONS * RTSP/1.0", m1.first)
            ok(m1)
            write("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n")
            assertTrue(read().first.startsWith("RTSP/1.0 200"))
            val m3 = read()
            assertTrue(m3.first.startsWith("GET_PARAMETER"))
            ok(m3, "wfd_car_display_mode: display=$display;dpi=200;fps=60\r\n" +
                "wfd_client_rtp_ports: RTP/AVP/TCP;unicast ${rtp.localPort} 0 mode=play\r\n")
            val m4 = read()
            assertTrue(m4.body.contains("wfd_client_rtp_ports: RTP/AVP/TCP;unicast ${rtp.localPort} 0 mode=play"))
            ok(m4)
            assertEquals("wfd_trigger_method: SETUP\r\n", read().also { ok(it) }.body)
            write("SETUP rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\nTransport: RTP/AVP/TCP;unicast;client_port=${rtp.localPort}\r\n\r\n")
            assertTrue(read().first.startsWith("RTSP/1.0 200"))
            write("PLAY rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 3\r\nSession: 1\r\n\r\n")
            assertTrue(read().first.startsWith("RTSP/1.0 200"))
            val media = rtp.accept().also { sockets += it }
            Thread { runCatching { demux(DataInputStream(media.getInputStream())) } }.start()
        }

        /** RFC 4571 → RTP PT 33 → TS: полезная нагрузка PID 256 подряд. */
        private fun demux(input: DataInputStream) {
            while (true) {
                val packet = ByteArray(input.readUnsignedShort()).also(input::readFully)
                assertEquals(33, packet[1].toInt() and 127)
                for (at in 12 until packet.size step 188) {
                    assertEquals(0x47, packet[at].toInt())
                    val pid = ((packet[at + 1].toInt() and 31) shl 8) or (packet[at + 2].toInt() and 255)
                    val payload = 4 + if (packet[at + 3].toInt() and 32 != 0) 1 + (packet[at + 4].toInt() and 255) else 0
                    if (pid == 256) synchronized(videoPes) { videoPes.write(packet, at + payload, 188 - payload) }
                }
            }
        }

        fun received(nal: ByteArray): Boolean {
            val all = synchronized(videoPes) { videoPes.toByteArray() }
            return (0..all.size - nal.size).any { at -> nal.indices.all { all[at + it] == nal[it] } }
        }

        fun input(port: Int, vararg json: String) {
            if (!::uibc.isInitialized) uibc = connect(port)
            // каждый JSON в своём AES-GCM-конверте
            for (j in json) uibc.getOutputStream().run { write(cipher.encrypt(j.toByteArray())); flush() }
        }

        override fun close() = sockets.forEach { runCatching { it.close() } }
    }

    private class Phone(val cfg: IccoaConfig, val session: IccoaSession, val screen: AtomicReference<IccoaVideoConfig>,
                        val closed: CopyOnWriteArrayList<String>)

    private fun phone(car: Car, trust: IccoaTrust, pin: String?, air: Boolean = false): Phone {
        val ports = (1..5).map { ServerSocket(0, 1, loop).use { s -> s.localPort } }
        val cfg = IccoaConfig("127.0.0.1", pin, "127.0.0.1", "test-model", bindHost = "127.0.0.1",
            authPort = car.auth.localPort, controlPort = ports[0], audioPort = ports[1], sensorPort = ports[2],
            rtspPort = ports[3], uibcPort = ports[4], air = air)
        val screen = AtomicReference<IccoaVideoConfig>()
        val closed = CopyOnWriteArrayList<String>()
        val platform = IccoaPlatform(log = { logs += it }, stub = { _, _, _, _, _, _, _ -> null }, aac = { _, _ -> null })
        val session = IccoaSession(cfg, trust, platform, onScreen = { screen.set(it) }) { closed += it }
        toClose += Closeable { session.close("конец теста") }
        session.start()
        return Phone(cfg, session, screen, closed)
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("не дождались: $what\n" + logs.joinToString("\n"))
            Thread.sleep(10)
        }
    }

    @Test fun pinSignInStreamsVideoTakesInputAndNextSignInNeedsNoPin() {
        val car = Car("123456").also { toClose += it }
        val trust = MemoryTrust()
        val phone = phone(car, trust, "123456")
        assertTrue(car.signIn())
        assertFalse(car.quick)
        assertEquals("127.0.0.1:0", car.confirm)
        car.openControl(phone.cfg.controlPort)
        // RTSP доходит до PLAY без ядра моста: экран машины известен только из M3.
        car.wfd(phone.cfg.rtspPort, "1920:1080")
        await("экран машины из M3") { phone.screen.get() != null }
        assertEquals(1920 to 1080, phone.screen.get().width to phone.screen.get().height)
        assertEquals(car.rtp.localPort, phone.screen.get().rtpPort)

        await("heartbeat телефона") { car.controlRx.any { it.first == IccoaControl.HEARTBEAT } }
        await("ответ get_port") { car.controlRx.any { it.first == IccoaControl.GET_PORT_RESPONSE &&
            it.second.contentEquals(PbWriter().int32(1, 1).int32(2, IccoaFrame.SENSOR_PORT).toByteArray()) } }
        // Ключ машины сохранён только после её шифрованного кадра control.
        await("ключ машины") { trust.carKey("127.0.0.1")?.contentEquals(car.identityBytes) == true }

        val core = AaBridgeCore({ line, _ -> logs += line })
        core.videoMode = CustomVideoMode.fitCar(phone.screen.get().width, phone.screen.get().height)
        val peer = FakePeer()
        core.peer = peer
        phone.session.bind(core)
        await("START ядру после PLAY и bind") { core.isCarlifeStarted }
        assertTrue(core.featureConfigSeen)
        assertTrue(core.audioViaUsb)

        core.onCodecConfig(sps + pps)
        core.onFrame(idr, 1)
        core.onFrame(p1, 2)
        await("IDR с SPS/PPS в TS") { car.received(sps + pps + idr) }
        await("P-кадр в TS") { car.received(p1) }

        // Тач в пикселях экрана из события (960×540) масштабируется в кадр ГУ 1920×1080.
        car.input(phone.cfg.uibcPort,
            """{"type":1,"action":0,"count":1,"x0":480,"y0":270,"trackID0":0,"width":960,"height":540}""",
            """{"type":1,"action":1,"count":1,"x0":480,"y0":270,"trackID0":0,"width":960,"height":540}""",
            """{"type":2,"action":0,"keycode":4}""", """{"type":2,"action":1,"keycode":4}""")
        await("тач") { peer.touches.firstOrNull() == Triple(0, 960, 540) }
        await("кнопка UIBC") { 4 in peer.keys }
        // m12 приходит парой: в ядро идёт только нажатие.
        car.send(IccoaControl.KEY_EVENT, PbWriter().int32(1, 0).int32(2, 87).toByteArray())
        car.send(IccoaControl.KEY_EVENT, PbWriter().int32(1, 1).int32(2, 87).toByteArray())
        await("кнопка руля") { 87 in peer.keys }
        Thread.sleep(100)
        assertEquals(1, peer.keys.count { it == 87 })

        car.control.close()
        await("закрытие по control") { phone.closed.isNotEmpty() }
        Thread.sleep(200)
        assertEquals(listOf("control закрыт"), phone.closed.toList())
        assertFalse(core.hasCarlifeLink)
        assertFalse(phone.session.sendVideo(p1))

        // Повторный вход той же машины: без PIN, по сохранённому ключу.
        val again = phone(car, trust, null)
        assertTrue(car.signIn())
        assertTrue(car.quick)
        car.openControl(again.cfg.controlPort)
        car.wfd(again.cfg.rtspPort, "2422:1272")
        await("экран машины при быстром входе") { again.screen.get() != null }
        assertEquals(2422 to 1272, again.screen.get().width to again.screen.get().height)
        again.session.close("стоп")
        assertEquals(listOf("стоп"), again.closed.toList())
        assertTrue(logs.none { "123456" in it })
    }

    @Test fun wrongPinClosesSessionWithReasonAndWithoutPin() {
        val car = Car("123456").also { toClose += it }
        val phone = phone(car, MemoryTrust(), "999999")
        assertFalse(car.signIn())
        await("отказ входа") { phone.closed.isNotEmpty() }
        assertEquals(listOf("вход ICCOA не удался: RESPONSE_RESULT result=1"), phone.closed.toList())
        assertTrue(logs.none { "999999" in it })
    }

    @Test fun noPinAndUnknownCarIsRefusedBeforeAnyNetwork() {
        val car = Car("123456").also { toClose += it }
        val phone = phone(car, MemoryTrust(), null)
        await("отказ без PIN") { phone.closed.isNotEmpty() }
        assertEquals(listOf("нужен PIN с экрана машины"), phone.closed.toList())
    }

    // По воздуху машина уже получила PIN в CLIENT_INFO и ждёт по TCP обычный вход, даже если ключ её известен.
    @Test fun airWithPinSignsInByPinEvenForKnownCar() {
        val car = Car("123456").also { toClose += it }
        val trust = MemoryTrust().also { it.saveCarKey("127.0.0.1", car.identityBytes) }
        phone(car, trust, "123456", air = true)
        assertTrue(car.signIn())
        assertFalse(car.quick)
    }

    @Test fun airQuickRejectedAsksForPinWithoutRetry() {
        val car = Car("123456").also { toClose += it }
        val trust = MemoryTrust().also { it.saveCarKey("127.0.0.1", car.identityBytes) }
        val phone = phone(car, trust, null, air = true)
        assertFalse(car.signIn())       // машина телефон не помнит
        assertTrue(car.quick)
        await("отказ быстрого входа") { phone.closed.isNotEmpty() }
        assertEquals(listOf("машина не узнала телефон, введите PIN"), phone.closed.toList())
        assertTrue(logs.any { it.startsWith("IC auth normal-required") })
        assertTrue(logs.none { "quick-rejected" in it })
    }

    @Test fun headFindsKeyFrameAndItsParameterSets() {
        val (key, config) = IccoaSession.head(sps + pps + idr)
        assertTrue(key)
        assertArrayEquals(sps + pps, config)
        assertEquals(false to null, IccoaSession.head(p1))
        assertEquals(true to null, IccoaSession.head(idr))
    }
}
