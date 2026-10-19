package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.yegorkapomidorka.headswap.PbReader
import com.yegorkapomidorka.headswap.aa.AapFraming as F
import com.yegorkapomidorka.headswap.aa.AapMessages as M

/**
 * Живая AapSession против скриптованного "телефона" (сервер AAP, написан отдельно от AapSession)
 * по loopback-сокету: version, TLS-сервер с запросом клиентского сертификата, service discovery,
 * каналы, видео с фрагментацией, ping, фокус, touch, ByeBye в обе стороны и чистый конец сокета.
 */
class AapSessionTest {
    open class Sink : AaVideoSink {
        val configs = CopyOnWriteArrayList<ByteArray>()
        val frames = CopyOnWriteArrayList<Pair<ByteArray, Long>>()
        override fun onCodecConfig(annexB: ByteArray) {
            configs.add(annexB)
        }

        override fun onFrame(annexB: ByteArray, ptsUs: Long) {
            frames.add(annexB to ptsUs)
        }

        val nativeRequests = java.util.concurrent.atomic.AtomicInteger()
        override fun onAaNativeFocusRequest() {
            nativeRequests.incrementAndGet()
        }
    }

    /** Приёмник с микрофоном: принимает open, считает open/close. */
    class MicSink : Sink(), AaMicSink {
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val closes = CopyOnWriteArrayList<String>()
        override fun onMicOpen(): Boolean {
            opens.incrementAndGet()
            return true
        }

        override fun onMicClose(why: String) {
            closes.add(why)
        }
    }

    class Phone(
        private val sock: Socket, clientSet: String, private val tls12: Boolean, private val end: String,
        private val stress: Boolean = false, private val onBeforeFail: () -> Unit = {},
        private val externalTouch: Boolean = false, private val idleAfterVideoMs: Long = 0,
        private val fullAudio: Boolean = false, private val mic: Boolean = false, private val exitTap: Boolean = false,
    ) : Thread("fake-phone") {
        val micFrames = CopyOnWriteArrayList<Pair<Long, Int>>()
        @Volatile var micFlags = -1
        lateinit var session: AapSession
        @Volatile var error: Throwable? = null
        @Volatile var sawEof = false
        @Volatile var reset = false
        val touches = CopyOnWriteArrayList<Triple<Int, Int, Int>>()
        val touchTs = CopyOnWriteArrayList<Long>()
        val sdrPayload = arrayOfNulls<ByteArray>(1)
        private val parser = F.Parser()
        private val queue = ArrayDeque<F.Frame>()
        private val reasm = F.Reassembler()
        private val tls = AapTls(
            AapCerts.load(TestAssets.opener, AapCerts.SETS.first { it != clientSet }), serverMode = true, tls12Only = tls12
        )
        private val buf = ByteArray(65536)
        val rxFlags = HashMap<Pair<Int, Int>, Int>()
        val ready = CountDownLatch(1)

        private fun recvFrame(): F.Frame {
            while (queue.isEmpty()) {
                val n = sock.getInputStream().read(buf)
                if (n < 0) throw java.io.EOFException("HU закрыл соединение")
                queue.addAll(parser.feed(buf, 0, n))
            }
            return queue.removeFirst()
        }

        private fun sendPlain(type: Int, data: ByteArray) {
            sock.getOutputStream().write(F.buildPlain(0, type, data))
        }

        fun sendMsg(chan: Int, type: Int, payload: ByteArray, fragment: Int = F.MAX_FRAGMENT) {
            val plaintext = F.typed(type, payload)
            for (fr in F.splitMessage(plaintext, chan, type, fragment)) {
                sock.getOutputStream().write(F.buildFrame(chan, fr.flags, tls.encrypt(fr.chunk), fr.total))
            }
        }

        /** Следующее сообщение от HU (touch-события складываются отдельно). */
        fun recvMsg(stopWhen: (() -> Boolean)? = null): Triple<Int, Int, ByteArray>? {
            while (true) {
                if (stopWhen != null && stopWhen()) return null
                val f = recvFrame()
                check(f.encrypted) { "plain-кадр после handshake" }
                val d = tls.decrypt(f.body)
                if (d.outgoing.isNotEmpty()) sock.getOutputStream().write(F.buildPlain(0, M.ENCAPSULATED_SSL, d.outgoing))
                if (d.plaintext.isEmpty()) continue
                val whole = reasm.push(f.chan, f.flags, d.plaintext, f.total) ?: continue
                val type = ((whole[0].toInt() and 0xFF) shl 8) or (whole[1].toInt() and 0xFF)
                rxFlags[f.chan to type] = f.flags
                val payload = whole.copyOfRange(2, whole.size)
                if (f.chan == 3 && type == M.INPUT_EVENT) {
                    val te = PbReader.parse(payload)!!.first { it.number == 3 }.data!!
                    val fields = PbReader.parse(te)!!
                    val p = PbReader.parse(fields.first { it.number == 1 }.data!!)!!
                    touchTs.add(PbReader.parse(payload)!!.first { it.number == 1 }.num)
                    touches.add(Triple(fields.first { it.number == 3 }.num.toInt(), p.first { it.number == 1 }.num.toInt(), p.first { it.number == 2 }.num.toInt()))
                    continue
                }
                return Triple(f.chan, type, payload)
            }
        }

        fun expect(chan: Int, type: Int): ByteArray {
            val (c, t, p) = recvMsg()!!
            assertEquals("ожидали ch$chan 0x%04x, пришло ch$c 0x%04x".format(type, t), chan to type, c to t)
            return p
        }

        override fun run() {
            try {
                script()
            } catch (e: Throwable) {
                error = e
            } finally {
                try {
                    sock.shutdownOutput()
                } catch (_: Exception) {
                }
                try {
                    sock.soTimeout = 5000
                    while (true) {
                        if (sock.getInputStream().read(buf) < 0) {
                            sawEof = true
                            break
                        }
                    }
                } catch (_: java.net.SocketException) {
                    reset = true
                } catch (_: Exception) {
                }
                sock.close()
            }
        }

        private fun script() {
            sock.soTimeout = 15000
            // version
            val f = recvFrame()
            assertEquals(0, f.chan)
            assertEquals(0x03, f.flags)
            assertArrayEquals(Golden.hex("0001 0001 0002"), f.body)
            sendPlain(M.VERSION_RESPONSE, Golden.hex("0001 0007 0000"))
            // TLS в plain-кадрах
            var firstOut = tls.handshakeStep(null)
            check(firstOut.isEmpty())
            while (!tls.handshakeDone) {
                val fr = recvFrame()
                assertTrue(fr.chan == 0 && fr.flags == 0x03 && fr.body[0].toInt() == 0 && fr.body[1].toInt() == 3)
                val out = tls.handshakeStep(fr.body.copyOfRange(2, fr.body.size))
                if (out.isNotEmpty()) sendPlain(M.ENCAPSULATED_SSL, out)
            }
            val auth = recvFrame()
            assertEquals(0, auth.chan)
            assertArrayEquals(Golden["auth_complete"], F.buildFrame(0, auth.flags, auth.body))

            // service discovery
            sendMsg(0, M.SERVICE_DISCOVERY_REQUEST, AapPb().str(4, "Pixel").str(5, "Google").toByteArray())
            val sd = expect(0, M.SERVICE_DISCOVERY_RESPONSE)
            assertArrayEquals(Golden[if (fullAudio) "sdr_full" else "sdr_default"], sd)
            assertEquals(0x0B, rxFlags[0 to 6])
            if (end == "no_video") {
                // видео не открывается: через preVideoTimeoutMs HU сам завершает сессию чистым ByeBye
                assertArrayEquals(Golden["byebye_request"], expect(0, M.BYEBYE_REQUEST))
                sendMsg(0, M.BYEBYE_RESPONSE, ByteArray(0))
                return
            }
            if (end == "write_fail") {
                // с этого момента запись HU падает; HU должен закрыть сессию сам и не слать ByeBye
                onBeforeFail()
                sendMsg(0, M.PING_REQUEST, AapPb().v(1, 1L).toByteArray())
                // свою сторону не закрываем, пока HU не закроет сокет сам: иначе наш EOF обгоняет сбой записи
                while (sock.getInputStream().read(buf) >= 0) {
                }
                return
            }

            // каналы
            for (chan in if (fullAudio) listOf(1, 2, 3, 4, 5, 6, 7, 9, 10) else listOf(1, 2, 3, 5, 7, 9, 10)) {
                sendMsg(chan, M.CHANNEL_OPEN_REQUEST, AapPb().v(1, 0).v(2, chan).toByteArray())
                assertArrayEquals(Golden["channel_open_response"], expect(chan, M.CHANNEL_OPEN_RESPONSE))
                assertEquals(if (chan != 0) 0x0F else 0x0B, rxFlags[chan to 8])
                if (chan == 1) assertArrayEquals(Golden["driving_status"], expect(1, M.SENSOR_EVENT))
            }
            // сенсоры, input
            sendMsg(1, M.SENSOR_START_REQUEST, AapPb().v(1, 13).v(2, 0).toByteArray())
            assertArrayEquals(Golden["sensor_start_response"], expect(1, M.SENSOR_START_RESPONSE))
            sendMsg(1, M.SENSOR_START_REQUEST, AapPb().v(1, 10).v(2, 0).toByteArray())
            assertArrayEquals(Golden["sensor_start_response"], expect(1, M.SENSOR_START_RESPONSE))
            assertArrayEquals(Golden["night"], expect(1, M.SENSOR_EVENT))
            if (::session.isInitialized) {                    // сценарии с AaConnector сессию не передают
                session.sendNightMode(true)                   // CHANGE_MD_THEME тёмная после старта сенсора
                assertArrayEquals(M.nightModeEvent(true).payload, expect(1, M.SENSOR_EVENT))
                session.sendNightMode(false)
                assertArrayEquals(Golden["night"], expect(1, M.SENSOR_EVENT))
            }
            sendMsg(3, M.INPUT_BINDING_REQUEST, AapPb().v(1, 3).v(1, 4).v(1, 84).toByteArray())
            assertArrayEquals(Golden["binding_response"], expect(3, M.INPUT_BINDING_RESPONSE))

            // видео: config + кадр в 3 фрагмента + маленький кадр
            sendMsg(2, M.MEDIA_SETUP, AapPb().v(1, 3).toByteArray())
            assertArrayEquals(Golden["media_config_video"], expect(2, M.MEDIA_CONFIG))
            assertArrayEquals(Golden["video_focus_unsol"], expect(2, M.MEDIA_VIDEO_FOCUS_NOTIFICATION))
            sendMsg(2, M.MEDIA_START, AapPb().v(1, 7).v(2, 0).toByteArray())
            sendMsg(2, M.MEDIA_CODEC_CONFIG, SPS_PPS)
            assertArrayEquals(Golden["media_ack"], expect(2, M.MEDIA_ACK))
            for ((n, unit) in listOf(IDR, SMALL).withIndex()) {
                val ts = 1_000_000L + n * 33_000L
                val tsBytes = ByteArray(8) { (ts ushr (56 - 8 * it)).toByte() }
                sendMsg(2, M.MEDIA_DATA, tsBytes + unit, fragment = 16384)
                assertArrayEquals(Golden["media_ack"], expect(2, M.MEDIA_ACK))
            }
            sendMsg(2, M.MEDIA_VIDEO_FOCUS_REQUEST, AapPb().v(2, 1).toByteArray())
            assertArrayEquals(Golden["video_focus_sol"], expect(2, M.MEDIA_VIDEO_FOCUS_NOTIFICATION))
            // mode=2 (NATIVE, «Выйти»): HU не отвечает (иначе следующим пришло бы не ch5 MEDIA_CONFIG), а зовёт sink
            // (только с голым sink: мост на него отвечает keyframe-циклом, это проверяет AaBridgeTest)
            if (exitTap) sendMsg(2, M.MEDIA_VIDEO_FOCUS_REQUEST, AapPb().v(2, 2).v(3, 0).toByteArray())

            // сторож тишины работает только до VIDEO MEDIA_START: пауза дольше его таймаута сессию не убивает
            if (idleAfterVideoMs > 0) Thread.sleep(idleAfterVideoMs)

            // аудио
            sendMsg(5, M.MEDIA_SETUP, AapPb().v(1, 1).toByteArray())
            assertArrayEquals(Golden["media_config_audio"], expect(5, M.MEDIA_CONFIG))
            assertArrayEquals(Golden["audio_focus_unsolicited"], expect(0, M.AUDIO_FOCUS_NOTIFICATION))
            sendMsg(5, M.MEDIA_START, AapPb().v(1, 3).v(2, 0).toByteArray())
            sendMsg(5, M.MEDIA_DATA, ByteArray(8) + ByteArray(640) { 1 })
            assertArrayEquals(Golden.hex("08 03 10 01"), expect(5, M.MEDIA_ACK))
            if (fullAudio) {
                // MEDIA (ch6, 48k стерео) и SPEECH (ch4, 16k моно): setup/config/focus/start/data -> ack, затем STOP MEDIA
                for ((chan, sid, bytes) in listOf(Triple(6, 4, 3840), Triple(4, 2, 640))) {
                    sendMsg(chan, M.MEDIA_SETUP, AapPb().v(1, 1).toByteArray())
                    assertArrayEquals(Golden["media_config_audio"], expect(chan, M.MEDIA_CONFIG))
                    assertArrayEquals(Golden["audio_focus_unsolicited"], expect(0, M.AUDIO_FOCUS_NOTIFICATION))
                    sendMsg(chan, M.MEDIA_START, AapPb().v(1, sid).v(2, 0).toByteArray())
                    sendMsg(chan, M.MEDIA_DATA, ByteArray(8) { if (it == 7) chan.toByte() else 0 } + ByteArray(bytes) { (chan + it % 3).toByte() })
                    assertArrayEquals(Golden.hex("08 0$sid 10 01"), expect(chan, M.MEDIA_ACK))
                }
                sendMsg(6, M.MEDIA_STOP, ByteArray(0))
            }
            // mic
            if (mic) {
                micDialog()
            } else {
                sendMsg(7, M.MEDIA_MIC_REQUEST, AapPb().bool(1, true).toByteArray())
                assertArrayEquals(Golden["mic_refuse"], expect(7, M.MEDIA_MIC_RESPONSE))
            }

            // control
            sendMsg(0, M.PING_REQUEST, AapPb().v(1, 123456789L).toByteArray())
            assertArrayEquals(Golden["ping_response"], expect(0, M.PING_RESPONSE))
            sendMsg(0, M.NAV_FOCUS_REQUEST, AapPb().v(1, 2).toByteArray())
            assertArrayEquals(Golden["nav_focus"], expect(0, M.NAV_FOCUS_NOTIFICATION))
            sendMsg(0, M.AUDIO_FOCUS_REQUEST, AapPb().v(1, 3).toByteArray())
            assertArrayEquals(Golden["audio_focus_duck"], expect(0, M.AUDIO_FOCUS_NOTIFICATION))
            sendMsg(0, M.VOICE_SESSION_NOTIFICATION, AapPb().v(1, 1).toByteArray())

            if (end == "bridge_grace") {
                // AA жива после обрыва USB: ждём keyframe-цикл NATIVE -> PROJECTED и отвечаем новым config + IDR
                ready.countDown()
                assertArrayEquals(Golden.hex("08 02 10 00"), expect(2, M.MEDIA_VIDEO_FOCUS_NOTIFICATION))
                assertArrayEquals(Golden["video_focus_unsol"], expect(2, M.MEDIA_VIDEO_FOCUS_NOTIFICATION))
                sendMsg(2, M.MEDIA_CODEC_CONFIG, SPS_PPS2)
                assertArrayEquals(Golden["media_ack"], expect(2, M.MEDIA_ACK))
                sendMsg(2, M.MEDIA_DATA, ByteArray(8) { if (it == 7) 9 else 0 } + IDR2)
                assertArrayEquals(Golden["media_ack"], expect(2, M.MEDIA_ACK))
                // дальше AA закрывается хостом после grace: чистый ByeBye
                assertArrayEquals(Golden["byebye_request"], expect(0, M.BYEBYE_REQUEST))
                sendMsg(0, M.BYEBYE_RESPONSE, ByteArray(0))
                return
            }
            if (stress) {
                // идёт поток видео (300 кадров с фрагментацией), а "чужая" нить шлёт touch DOWN, 30 MOVE, UP
                val toucher = Thread {
                    session.sendTouch(M.TOUCH_DOWN, 100, 200)
                    repeat(30) {
                        session.sendTouch(M.TOUCH_MOVE, 100 + it, 200 + it)
                        Thread.sleep(2)
                    }
                    session.sendTouch(M.TOUCH_UP, 130, 230)
                }
                toucher.start()
                for (n in 0 until 300) {
                    val ts = 2_000_000L + n * 33_000L
                    val tsBytes = ByteArray(8) { (ts ushr (56 - 8 * it)).toByte() }
                    sendMsg(2, M.MEDIA_DATA, tsBytes + (if (n % 50 == 0) IDR else SMALL), fragment = 16384)
                    assertArrayEquals(Golden["media_ack"], expect(2, M.MEDIA_ACK))
                }
                toucher.join(10_000)
                val extra = recvMsg { touches.size >= 32 }
                check(extra == null) { "лишнее сообщение ch${extra!!.first} 0x%04x".format(extra.second) }
                session.requestStop("тест")
                assertArrayEquals(Golden["byebye_request"], expect(0, M.BYEBYE_REQUEST))
                sendMsg(0, M.BYEBYE_RESPONSE, ByteArray(0))
                return
            }

            // touch: тест дёргает sendTouch из своей нити (как UI)
            if (!externalTouch) {
                session.sendTouch(M.TOUCH_DOWN, 640, 360)
                session.sendTouch(M.TOUCH_UP, 640, 360)
            }
            val extra = recvMsg { touches.size >= 2 }
            check(extra == null) { "лишнее сообщение ch${extra!!.first} 0x%04x при ожидании touch".format(extra.second) }

            if (end == "phone_bye") {
                sendMsg(0, M.BYEBYE_REQUEST, AapPb().v(1, 1).toByteArray())
                expect(0, M.BYEBYE_RESPONSE)
            } else {
                session.requestStop("тест")
                assertArrayEquals(Golden["byebye_request"], expect(0, M.BYEBYE_REQUEST))
                sendMsg(0, M.BYEBYE_RESPONSE, ByteArray(0))
            }
        }

        /**
         * Микрофон принят: Open(maxUnacked=2) -> успех session_id=1; HU шлёт первые 2 кадра по 4096 Б
         * (timestamp u64 BE мкс + PCM), остальные ждут Ack; после Ack(2) приходят ещё 2; Close -> успех.
         */
        private fun micDialog() {
            sendMsg(7, M.MEDIA_MIC_REQUEST, AapPb().bool(1, true).v(4, 2).toByteArray())
            assertArrayEquals(Golden.hex("08 00 10 01"), expect(7, M.MEDIA_MIC_RESPONSE))
            ready.countDown()                                   // тест подаёт 16384 Б PCM
            fun data(): Pair<Long, Int> {
                val p = expect(7, M.MEDIA_DATA)
                micFlags = rxFlags[7 to M.MEDIA_DATA] ?: -1
                var ts = 0L
                for (i in 0 until 8) ts = (ts shl 8) or (p[i].toLong() and 0xFF)
                // PCM внутри кадра должен быть ровно тем, что подали: кадр k, байт i = (k * 7 + i) & 0xFF
                val base = ((ts - 5_000_000L) / 128_000L).toInt()
                for (i in 0 until p.size - 8) {
                    assertEquals("PCM кадра #$base, байт $i", ((base * 7 + i) and 0xFF).toByte(), p[8 + i])
                }
                return ts to (p.size - 8)
            }
            repeat(2) { micFrames.add(data()) }
            sendMsg(7, M.MEDIA_ACK, AapPb().v(1, 1).v(2, 2).toByteArray())
            repeat(2) { micFrames.add(data()) }
            sendMsg(7, M.MEDIA_ACK, AapPb().v(1, 1).v(2, 2).toByteArray())
            sendMsg(7, M.MEDIA_MIC_REQUEST, AapPb().bool(1, false).toByteArray())
            assertArrayEquals(Golden.hex("08 00 10 01"), expect(7, M.MEDIA_MIC_RESPONSE))
        }

        companion object {
            val SPS_PPS = Golden.hex("00000001 67 42c01f da0140 16e840 00000001 68 ce3c80")
            val IDR = Golden.hex("00000001 65") + ByteArray(40000) { ((it * 7) % 251).toByte() }
            val SPS_PPS2 = Golden.hex("00000001 67 4d401f ac2ca5 00000001 68 ce3c80")
            val IDR2 = Golden.hex("00000001 65") + ByteArray(2000) { ((it * 3) % 251).toByte() }
            val SMALL = Golden.hex("00000001 41") + ByteArray(300) { 0x11 }
        }
    }

    private fun scenario(clientSet: String, tls12: Boolean, end: String, stress: Boolean = false, idleMs: Long = 0, preVideoMs: Long = 8000, mic: Boolean = false) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.use {
            val sink = if (mic) MicSink() else Sink()
            val phoneSock = java.util.concurrent.atomic.AtomicReference<Socket>()
            val accepted = CountDownLatch(1)
            val phoneReady = CountDownLatch(1)
            lateinit var phone: Phone
            Thread {
                phoneSock.set(server.accept())
                phone = Phone(phoneSock.get(), clientSet, tls12, end, stress, idleAfterVideoMs = idleMs, mic = mic, exitTap = true)
                accepted.countDown()
            }.start()
            val ended = CountDownLatch(1)
            val endInfo = arrayOfNulls<Pair<String, Boolean>>(1)
            val logs = CopyOnWriteArrayList<String>()
            val session = AapSession(
                AapCerts.load(TestAssets.opener, clientSet), sink,
                AapOptions(port = server.localPort, tls12Only = tls12, preVideoTimeoutMs = preVideoMs),
                log = { l, _ -> logs.add(l) },
                onEnd = { r, ok ->
                    endInfo[0] = r to ok
                    ended.countDown()
                }
            )
            session.start()
            assertTrue("phone не принял соединение", accepted.await(5, TimeUnit.SECONDS))
            phone.session = session
            phone.start()
            if (mic) {
                Thread {
                    if (phone.ready.await(10, TimeUnit.SECONDS)) {
                        val pcm = ByteArray(16384) { ((it / 4096 * 7 + it % 4096) and 0xFF).toByte() }
                        session.sendMicPcm(pcm, 5_000_000L)
                    }
                }.start()
            }
            assertTrue("сессия не завершилась\n" + logs.joinToString("\n"), ended.await(30, TimeUnit.SECONDS))
            phone.join(10_000)
            assertFalse("скрипт телефона завис", phone.isAlive)
            assertNull("ошибка телефона: ${phone.error}\n" + logs.joinToString("\n"), phone.error)
            assertTrue("HU не закрыл сокет штатно (EOF)", phone.sawEof && !phone.reset)
            assertTrue("сессия закончилась не чисто: ${endInfo[0]}\n" + logs.joinToString("\n"), session.exitOk)
            // видео: SPS/PPS в onCodecConfig, кадры без AA-заголовков, pts из timestamp
            assertEquals(1, sink.configs.size)
            assertArrayEquals(Phone.SPS_PPS, sink.configs[0])
            assertArrayEquals(Phone.IDR, sink.frames[0].first)
            assertEquals(1_000_000L, sink.frames[0].second)
            assertArrayEquals(Phone.SMALL, sink.frames[1].first)
            assertEquals(1_033_000L, sink.frames[1].second)
            assertEquals(1L, session.stats.videoCodecConfig)
            assertEquals("VIDEO_FOCUS_REQUEST mode=2 -> один вызов sink, mode=1 не считается", 1, (sink as Sink).nativeRequests.get())
            if (stress) {
                assertEquals(302, sink.frames.size)
                assertEquals(302L, session.stats.videoData)
                // DOWN, 30 MOVE, UP в порядке отправки, координаты целы
                val expected = listOf(Triple(0, 100, 200)) + (0 until 30).map { Triple(2, 100 + it, 200 + it) } + Triple(1, 130, 230)
                assertEquals(expected, phone.touches.toList())
                // uint64 нс: положительные и неубывающие
                assertTrue("timestamp <= 0: ${phone.touchTs}", phone.touchTs.all { it > 0 })
                assertEquals(phone.touchTs.sorted(), phone.touchTs.toList())
            } else {
                assertEquals(2, sink.frames.size)
                assertEquals(2L, session.stats.videoData)
                assertEquals(listOf(Triple(0, 640, 360), Triple(1, 640, 360)), phone.touches.toList())
                assertTrue("timestamp <= 0: ${phone.touchTs}", phone.touchTs.all { it > 0 })
            }
            assertTrue(logs.any { it.contains("SERVICE_DISCOVERY_REQUEST") })
            assertTrue(logs.any { it.contains("MEDIA_ACK") })
            if (mic) {
                val ms = sink as MicSink
                assertEquals(1, ms.opens.get())
                assertEquals("close от AA один раз, без повторного close при завершении сессии", listOf("AA закрыла микрофон"), ms.closes.toList())
                // 4 кадра по 4096 Б, timestamp каждого +128 мс от предыдущего; зашифрованный bulk-кадр 0x0b
                assertEquals(listOf(5_000_000L, 5_128_000L, 5_256_000L, 5_384_000L), phone.micFrames.map { it.first })
                assertTrue(phone.micFrames.all { it.second == 4096 })
                assertEquals(0x0B, phone.micFlags)
                assertTrue(logs.any { it.contains("AA MIC open принят: session=1") })
            }
        }
    }

    @Test fun micSessionEndToEnd() = scenario("jvc", false, "hu_bye", mic = true)

    @Test fun jvcTlsAutoHuEndsSession() = scenario("jvc", false, "hu_bye")
    @Test fun hurTlsAutoPhoneEndsSession() = scenario("hur", false, "phone_bye")
    @Test fun jvcTls12HuEndsSession() = scenario("jvc", true, "hu_bye")

    @Test fun silenceAfterVideoStartDoesNotKillSession() = scenario("jvc", false, "hu_bye", idleMs = 3000, preVideoMs = 1000)

    @Test fun touchFromForeignThreadDuringVideoStreamKeepsTls() = scenario("jvc", false, "hu_bye", stress = true)
    @Test fun touchFromForeignThreadDuringVideoStreamTls12() = scenario("hur", true, "hu_bye", stress = true)

    /** Сокет, у которого запись можно сломать на лету. */
    class FailSocket : Socket() {
        @Volatile var failWrites = false
        override fun getOutputStream(): java.io.OutputStream {
            val real = super.getOutputStream()
            return object : java.io.OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (failWrites) throw java.io.IOException("сымитированный сбой записи")
                    real.write(b, off, len)
                }
                override fun flush() = real.flush()
            }
        }
    }

    @Test
    fun writeFailureAfterWrapClosesSession() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.use {
            val fail = FailSocket()
            val logs = CopyOnWriteArrayList<String>()
            val ended = CountDownLatch(1)
            val endInfo = arrayOfNulls<Pair<String, Boolean>>(1)
            val accepted = CountDownLatch(1)
            lateinit var phone: Phone
            Thread {
                phone = Phone(server.accept(), "jvc", false, "write_fail", onBeforeFail = { fail.failWrites = true })
                accepted.countDown()
            }.start()
            val session = AapSession(
                AapCerts.load(TestAssets.opener, "jvc"), Sink(),
                AapOptions(port = server.localPort, preVideoTimeoutMs = 8000),
                log = { l, _ -> logs.add(l) },
                socketFactory = { fail.also { it.connect(java.net.InetSocketAddress("127.0.0.1", server.localPort), 5000) } },
                onEnd = { r, ok ->
                    endInfo[0] = r to ok
                    ended.countDown()
                }
            )
            session.start()
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            phone.start()
            assertTrue("сессия не закрылась после сбоя записи\n" + logs.joinToString("\n"), ended.await(15, TimeUnit.SECONDS))
            assertFalse(session.exitOk)
            assertTrue(endInfo[0].toString(), endInfo[0]!!.first.contains("сбой исходящей записи"))
            assertTrue("после сбоя не должно быть ByeBye", logs.none { it.contains("BYEBYE_REQUEST") })
            assertTrue(logs.any { it.contains("СБОЙ ЗАПИСИ") })
            phone.join(10_000)
        }
    }

    @Test
    fun preVideoWatchdogEndsSessionCleanly() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.use {
            val logs = CopyOnWriteArrayList<String>()
            val ended = CountDownLatch(1)
            val endInfo = arrayOfNulls<Pair<String, Boolean>>(1)
            lateinit var phone: Phone
            val accepted = CountDownLatch(1)
            Thread {
                phone = Phone(server.accept(), "jvc", false, "no_video")
                accepted.countDown()
            }.start()
            val session = AapSession(
                AapCerts.load(TestAssets.opener, "jvc"), Sink(),
                AapOptions(port = server.localPort, preVideoTimeoutMs = 1500),
                log = { l, _ -> logs.add(l) },
                onEnd = { r, ok ->
                    endInfo[0] = r to ok
                    ended.countDown()
                }
            )
            session.start()
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            phone.start()
            assertTrue("сторож не сработал\n" + logs.joinToString("\n"), ended.await(15, TimeUnit.SECONDS))
            assertTrue(endInfo[0].toString(), endInfo[0]!!.first.contains("видео не началось"))
            phone.join(10_000)
            assertNull(phone.error)
            assertTrue(phone.sawEof && !phone.reset)
        }
    }
}
