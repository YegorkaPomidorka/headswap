package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.LogThrottle
import com.yegorkapomidorka.headswap.PbReader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import com.yegorkapomidorka.headswap.aa.AapFraming as F
import com.yegorkapomidorka.headswap.aa.AapMessages as M

/** Куда падает видео от AA: чистый Annex-B без заголовков AAP. */
interface AaVideoSink {
    /** CODEC_CONFIG: SPS+PPS. */
    fun onCodecConfig(annexB: ByteArray)

    /** DATA: один кадр (8-байтный timestamp AAP снят), ptsUs из этого timestamp. */
    fun onFrame(annexB: ByteArray, ptsUs: Long)

    /** Телефон просит VIDEO_FOCUS NATIVE (плитка «Выйти» в AA). Ответ AA отправляет вызывающий позже (AaPeer.sendVideoFocusNative). */
    fun onAaNativeFocusRequest() {}
}

class AapOptions(
    val host: String = "127.0.0.1",
    val port: Int = 5277,
    /** Сторож тишины только до первого VIDEO MEDIA_START; после него сессия молчать может (статичный экран, нет Ping). */
    val preVideoTimeoutMs: Long = 30_000,
    val maxUnackedVideo: Int = 12,         // AapControl.kt maxUnackedFor(): 12 для сокета
    val maxUnackedAudio: Int = 30,
    val sd: AapSdOptions = AapSdOptions(),
    /** Если задан, SDR строится в момент запроса телефона (мост: звук зависит от FEATURE_CONFIG ГУ). */
    val sdProvider: (() -> AapSdOptions)? = null,
    val tls12Only: Boolean = false,
)

class AapStats {
    @Volatile var rxFrames = 0L
    @Volatile var txFrames = 0L
    @Volatile var videoCodecConfig = 0L
    @Volatile var videoData = 0L
    @Volatile var videoBytes = 0L
    @Volatile var audioData = 0L
}

/**
 * Одна сессия Android Auto head unit: TCP-клиент к серверу ГУ в AA (127.0.0.1:5277). Порт
 * pc-headunit/aap/session.py: Version -> TLS в plain-кадрах -> AuthComplete -> шифрованный обмен ->
 * ByeBye / shutdownOutput / дочитать до EOF / close (сервер 5277 одноразовый, грязный обрыв его глушит).
 *
 * Своя нить; sendTouch/requestStop можно звать из любой. log(line, ui): ui=false для потока видео-кадров.
 */
/** Что мост требует от AA-сессии. */
/** Палец AA-тача: pointer_id и координаты кадра AA. */
data class TouchPoint(val id: Int, val x: Int, val y: Int)

interface AaPeer {
    /** Тач со всеми активными пальцами; action_index - позиция в [points] пальца, вызвавшего POINTER_DOWN/UP. */
    fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>)

    /** Один палец с id 0. */
    fun sendTouch(action: Int, x: Int, y: Int) = sendTouch(action, 0, listOf(TouchPoint(0, x, y)))

    fun requestKeyFrame()

    /** Нажатие кнопки (Android KeyEvent keycode): down, затем up. По умолчанию игнорируется. */
    fun sendKey(keycode: Int) {}

    /** Поворот шайбы на delta щелчков (> 0 по часовой). По умолчанию игнорируется. */
    fun sendRotary(delta: Int) {}

    /** PCM16 mono 16 кГц микрофона -> телефону, пока открыта микрофонная сессия AA. По умолчанию игнорируется. */
    fun sendMicPcm(pcm: ByteArray, capturedAtUs: Long) {}

    /** VideoFocusNotification NATIVE (unsolicited = false): ГУ ушёл с проекции. По умолчанию игнорируется. */
    fun sendVideoFocusNative() {}

    /** Ночной режим AA (сенсор NIGHT): запоминается и уходит, как только телефон запустил сенсор. По умолчанию игнорируется. */
    fun sendNightMode(night: Boolean) {}
}

class AapSession(
    private val creds: AapCredentials,
    private val sink: AaVideoSink,
    private val opts: AapOptions = AapOptions(),
    private val log: (String, Boolean) -> Unit = { _, _ -> },
    private val socketFactory: (() -> Socket)? = null,
    private val onEstablished: () -> Unit = {},
    private val onFirstVideo: () -> Unit = {},
    private val onEnd: (reason: String, ok: Boolean) -> Unit = { _, _ -> },
) : AaPeer {
    val stats = AapStats()

    @Volatile private var stopReason: String? = null
    @Volatile private var userStop = false
    @Volatile var exitOk = false
        private set
    @Volatile var established = false
        private set

    /** TCP к серверу ГУ установлен (false в конце сессии = connection refused / недоступен). */
    @Volatile var connected = false
        private set

    /** Телефон открыл видеоканал (VIDEO MEDIA_START). */
    @Volatile var videoStarted = false
        private set
    private var establishedAtNs = 0L

    private val audioSink: AaAudioSink? = sink as? AaAudioSink
    private val micSink: AaMicSink? = sink as? AaMicSink
    private val metaSink: AaMetaSink? = sink as? AaMetaSink
    private val uplink = AaMicUplink({ sid, ts, pcm ->
        outQueue.offer(MsgJob(M.micData(ts, pcm), true, "mic sid=$sid ts=${ts}us"))
    })
    private var micSeq = 0
    @Volatile private var micSid = 0
    private var thread: Thread? = null
    private lateinit var sock: Socket
    private lateinit var tls: AapTls
    private val parser = F.Parser()
    private val reasm = F.Reassembler { log("AA reassembly: $it", true) }
    private val frames = ArrayDeque<F.Frame>()
    private val sessionIds = HashMap<Int, Int>()
    // медиа (кадры, аудио, их ACK, микрофон) в лог прорежено: первые и каждый 200-й по каналу и типу (номер в строке #N)
    private val rxMediaLog = LogThrottle()
    private val txMediaLog = LogThrottle()
    private val outQueue = LinkedBlockingQueue<Job>()
    private var writer: Thread? = null
    @Volatile private var writerActive = false
    @Volatile private var fatal = false
    private val clockBaseNs = System.nanoTime()
    private val readBuf = ByteArray(65536)

    @Volatile private var peerClosed = false
    private var byeSent = false
    private var byeAcked = false
    private var lastRx = System.nanoTime()
    private val firstVideo = AtomicLong(0)

    fun start() {
        val t = Thread({ run() }, "aa-session")
        thread = t
        t.start()
    }

    fun join(timeoutMs: Long) {
        thread?.join(timeoutMs)
    }

    /** Корректное завершение: нить сама отправит ByeBye и закроет сокет. */
    fun requestStop(reason: String) {
        userStop = true
        stop(reason)
    }

    private fun stop(reason: String) {
        if (stopReason == null) stopReason = reason
    }

    /** action 0 DOWN, 1 UP, 2 MOVE, 5 POINTER_DOWN, 6 POINTER_UP; координаты в пикселях кадра AA. Игнорируется до готовности сессии. */
    override fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        if (!established || stopReason != null) {
            log("AA touch($action,$actionIndex,$points) пропущен: сессия ${if (established) "завершается" else "ещё не готова"}", true)
            return
        }
        try {
            // uint64 нс, строго > 0 и монотонно; System.nanoTime() сам по себе может быть отрицательным/произвольным
            val ts = System.nanoTime() - clockBaseNs + 1_000_000_000L
            outQueue.offer(MsgJob(M.touchEvent(action, actionIndex, points, ts), false, "touch ts=${ts}ns"))
        } catch (e: Throwable) {
            log("AA ошибка постановки touch в очередь: ${e.javaClass.simpleName}: ${e.message}", true)
        }
    }

    /** Нажатие кнопки: down, затем up (Android KeyEvent keycode). Игнорируется до готовности сессии. */
    override fun sendKey(keycode: Int) {
        if (!established || stopReason != null) {
            log("AA key($keycode) пропущен: сессия ${if (established) "завершается" else "ещё не готова"}", true)
            return
        }
        try {
            val ts = System.nanoTime() - clockBaseNs + 1_000_000_000L
            outQueue.offer(MsgJob(M.keyEvent(ts, keycode, true), false, "key down $keycode ts=${ts}ns"))
            outQueue.offer(MsgJob(M.keyEvent(ts + 1_000_000L, keycode, false), false, "key up $keycode ts=${ts + 1_000_000L}ns"))
        } catch (e: Throwable) {
            log("AA ошибка постановки key в очередь: ${e.javaClass.simpleName}: ${e.message}", true)
        }
    }

    /** Поворот шайбы: relative_event KEYCODE_ROTARY_CONTROLLER. Игнорируется до готовности сессии. */
    override fun sendRotary(delta: Int) {
        if (!established || stopReason != null) {
            log("AA rotary($delta) пропущен: сессия ${if (established) "завершается" else "ещё не готова"}", true)
            return
        }
        try {
            val ts = System.nanoTime() - clockBaseNs + 1_000_000_000L
            outQueue.offer(MsgJob(M.rotaryEvent(ts, delta), false, "rotary $delta ts=${ts}ns"))
        } catch (e: Throwable) {
            log("AA ошибка постановки rotary в очередь: ${e.javaClass.simpleName}: ${e.message}", true)
        }
    }

    /**
     * Просит у телефона ключевой кадр циклом VideoFocus NATIVE -> (пауза) -> PROJECTED unsolicited
     * (headunit-revived AapTransport.sendKeyframeCycleRelease + focusCycleGainRunnable).
     */
    override fun requestKeyFrame() {
        if (!established || !videoStarted || stopReason != null || !writerActive) {
            log("AA keyframe-цикл пропущен: сессия ${if (!established) "не готова" else if (!videoStarted) "без видео" else "завершается"}", false)
            return
        }
        outQueue.offer(MsgJob(M.videoFocusNotification(projected = false, unsolicited = false), false, "keyframe cycle: NATIVE"))
        val t = Thread({
            try {
                Thread.sleep(KEYFRAME_CYCLE_GAP_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (stopReason == null && writerActive) {
                outQueue.offer(MsgJob(M.videoFocusNotification(projected = true, unsolicited = true), false, "keyframe cycle: PROJECTED"))
            }
        }, "aa-keyframe")
        t.isDaemon = true
        t.start()
    }

    /** VideoFocus NATIVE одним сообщением (без возврата в PROJECTED): ГУ покинул CarLife после «Выйти». */
    override fun sendVideoFocusNative() {
        if (!established || stopReason != null || !writerActive) {
            log("AA VideoFocus NATIVE пропущен: сессия ${if (!established) "не готова" else "завершается"}", true)
            return
        }
        log("AA VideoFocus NATIVE (ГУ вышел из CarLife)", true)
        outQueue.offer(MsgJob(M.videoFocusNotification(projected = false, unsolicited = false), false, "exit: NATIVE"))
    }

    @Volatile private var nightMode = false
    @Volatile private var nightSensorStarted = false

    override fun sendNightMode(night: Boolean) {
        val changed = night != nightMode
        nightMode = night
        if (!nightSensorStarted) {
            if (changed) log("AA night=$night запомнила, сенсор NIGHT ещё не запущен телефоном", true)
            return
        }
        if (!established || stopReason != null || !writerActive) {
            log("AA night=$night пропущен: сессия ${if (!established) "не готова" else "завершается"}", true)
            return
        }
        log("AA SENSOR night=$night (тема ГУ)", true)
        outQueue.offer(MsgJob(M.nightModeEvent(night), false, "night=$night"))
    }

    /** PCM микрофона: нарезается на кадры по 128 мс и уходит в окно maxUnacked; без открытой микрофонной сессии игнорируется. */
    override fun sendMicPcm(pcm: ByteArray, capturedAtUs: Long) {
        if (stopReason != null || !writerActive) return
        if (uplink.feed(pcm, capturedAtUs) == AaMicUplink.Feed.OVERFLOW) micAbort("телефон не подтверждает кадры микрофона (очередь переполнена)")
    }

    /** Закрыть микрофонную сессию по инициативе HU: телефону уходит ошибка, источнику звука - close. */
    private fun micAbort(why: String) {
        val sum = uplink.close() ?: return
        log("AA MIC прервана: $why; $sum", true)
        if (writerActive) outQueue.offer(MsgJob(M.micResponse(false, sum.sessionId), false, "mic abort"))
        micSink?.onMicClose(why)
    }

    private fun onMicRequest(payload: ByteArray) {
        val open = (firstVarint(payload, 1) ?: 0L) != 0L
        val window = (firstVarint(payload, 4) ?: 2L).toInt()
        val ms = micSink
        if (open) {
            if (ms == null) {
                send(M.micResponse(success = false))                    // захвата нет: открытие отклоняем
                return
            }
            if (uplink.isOpen) {                                         // повторный Open: та же сессия
                send(M.micResponse(success = true, sessionId = micSid))
                return
            }
            if (window !in 1..4096) {
                log("AA MIC open: maxUnacked=$window вне 1..4096, отказ", true)
                send(M.micResponse(success = false))
                return
            }
            val sid = micSeq + 1
            uplink.open(sid, window)
            val accepted = try {
                ms.onMicOpen()
            } catch (e: Throwable) {
                log("AA MIC open: источник звука упал: ${e.javaClass.simpleName}: ${e.message}", true)
                false
            }
            if (!accepted) {
                uplink.close()
                log("AA MIC open отклонён (нет источника звука)", true)
                send(M.micResponse(success = false))                    // как раньше: отказ с session_id 0
                return
            }
            micSeq = sid
            micSid = sid
            log("AA MIC open принят: session=$sid, окно maxUnacked=$window, кадры по ${AaMicFormat.CHUNK_BYTES} Б", true)
            send(M.micResponse(success = true, sessionId = sid))
            uplink.activate()                                            // DATA только после ответа в очереди
        } else {
            val sid = micSid
            val sum = uplink.close()
            if (sum != null) {
                log("AA MIC close: $sum", true)
                ms?.onMicClose("AA закрыла микрофон")
            }
            send(M.micResponse(success = true, sessionId = sid))
        }
    }

    private fun onMicAck(payload: ByteArray) {
        val f = PbReader.parse(payload)
        val sid = f?.let { PbReader.int(it, 1) } ?: 0
        val stamps = f?.count { it.number == 3 } ?: 0
        val count = if (stamps > 0) stamps else (f?.let { PbReader.int(it, 2) } ?: 0)
        if (!uplink.ack(sid, count)) log("AA MIC ack проигнорирован: session=$sid count=$count", false)
    }

    // ---------------------------------------------------------------------------------------------
    private class SessionError(msg: String) : Exception(msg)
    private class PeerClosed(msg: String) : Exception(msg)

    private fun run() {
        try {
            try {
                sock = (socketFactory ?: {
                    Socket().also { it.connect(InetSocketAddress(opts.host, opts.port), 5000) }
                }).invoke()
            } catch (e: IOException) {
                // сервер ГУ не слушает: тихий выход без ByeBye и итогов, мост сам повторит
                log("AA сервер ${opts.host}:${opts.port} недоступен: ${e.javaClass.simpleName}: ${e.message}", false)
                stopReason = "сервер недоступен: ${e.javaClass.simpleName}"
                return
            }
            connected = true
            sock.tcpNoDelay = true
            log("AA TCP подключено ${opts.host}:${opts.port}", true)
            tls = AapTls(creds, serverMode = false, tls12Only = opts.tls12Only)
            versionExchange()
            tlsHandshake()
            sendAuthComplete()
            startWriter()
            established = true
            establishedAtNs = System.nanoTime()
            lastRx = System.nanoTime()
            log("AA сессия установлена (${tls.describe()}), ждём телефон", true)
            onEstablished()
            serve()
        } catch (e: PeerClosed) {
            peerClosed = true
            if (stopReason == null) stopReason = "peer closed: ${e.message}"
            log("AA ${e.message}", true)
        } catch (e: SessionError) {
            if (stopReason == null) stopReason = e.message
            log("AA ${e.message}", true)
        } catch (e: javax.net.ssl.SSLException) {
            if (stopReason == null) stopReason = "TLS: ${e.message}"
            log("AA TLS ошибка: ${e.javaClass.simpleName}: ${e.message}", true)
        } catch (e: IOException) {
            peerClosed = true
            if (stopReason == null) stopReason = "ошибка сокета: ${e.javaClass.simpleName}: ${e.message}"
            log("AA ошибка сокета: ${e.javaClass.simpleName}: ${e.message}", true)
        } catch (e: Throwable) {
            if (stopReason == null) stopReason = "${e.javaClass.simpleName}: ${e.message}"
            log("AA сбой: ${e.javaClass.simpleName}: ${e.message}", true)
        } finally {
            if (!connected) {
                onEnd(stopReason ?: "не подключено", false)
                return
            }
            if (uplink.close() != null) micSink?.onMicClose("сессия AA закрыта")
            try {
                shutdown()
                if (userStop && byeAcked) exitOk = true
            } catch (e: Throwable) {
                log("AA сбой в shutdown: ${e.javaClass.simpleName}: ${e.message}", true)
            }
            val s = stats
            log(
                "AA итог: кадров rx=${s.rxFrames} tx=${s.txFrames}; видео config=${s.videoCodecConfig} data=${s.videoData} " +
                    "байт=${s.videoBytes}; аудио data=${s.audioData}; причина: $stopReason", true
            )
            onEnd(stopReason ?: "конец", exitOk)
        }
    }

    // --- сокет / кадры ---
    private fun sendRaw(data: ByteArray) {
        stats.txFrames++
        sock.getOutputStream().write(data)
        sock.getOutputStream().flush()
    }

    private fun readMore(timeoutMs: Int) {
        sock.soTimeout = maxOf(timeoutMs, 1)
        val n = try {
            sock.getInputStream().read(readBuf)
        } catch (_: SocketTimeoutException) {
            return
        } catch (e: java.net.SocketException) {
            throw PeerClosed("соединение сброшено телефоном (${e.message})")
        }
        if (n < 0) throw PeerClosed("телефон закрыл соединение (EOF)")
        lastRx = System.nanoTime()
        for (fr in parser.feed(readBuf, 0, n)) {
            stats.rxFrames++
            frames.add(fr)
        }
    }

    private fun nextFrame(deadlineNs: Long): F.Frame? {
        while (frames.isEmpty()) {
            val leftMs = (deadlineNs - System.nanoTime()) / 1_000_000
            if (leftMs <= 0) return null
            readMore(minOf(leftMs, POLL_MS.toLong()).toInt())
            stopReason?.let { throw SessionError("прервано во время handshake: $it") }
        }
        return frames.removeFirst()
    }

    // --- исходящая очередь ---
    // После handshake ВСЕ исходящие кадры (сообщения, touch, ответы TLS-слоя) идут через одну очередь и
    // одну нить: wrap и write строго атомарно, иначе запись TLS с номером N может уйти после N+1
    // (или не уйти вовсе), и телефон рвёт TLS. До handshake пишет сама нить сессии напрямую.
    private sealed interface Job
    private class MsgJob(val out: AapOut, val media: Boolean, val note: String) : Job
    private object TlsFlushJob : Job
    private class MarkerJob(val latch: CountDownLatch) : Job
    private object PoisonJob : Job

    private fun startWriter() {
        writerActive = true
        val t = Thread({ writerLoop() }, "aa-writer")
        t.isDaemon = true
        writer = t
        t.start()
    }

    private fun writerLoop() {
        while (true) {
            val job = outQueue.take()
            try {
                when (job) {
                    is PoisonJob -> return
                    is MarkerJob -> job.latch.countDown()
                    is TlsFlushJob -> {
                        val data = tls.drainOutgoing()
                        if (data.isNotEmpty()) {
                            log("AA TX CONTROL(0) ENCAPSULATED_SSL len=${data.size} plain frame", false)
                            sendRaw(F.buildPlain(F.CHANNEL_CONTROL, M.ENCAPSULATED_SSL, data))
                        }
                    }
                    is MsgJob -> {
                        val n = if (job.media) txMediaLog.next(mediaKey(job.out.chan, job.out.type)) else -1L
                        if (n != 0L) logMsg("TX", job.out.chan, job.out.type, job.out.payload, job.media, if (n > 0) "${job.note} #$n".trim() else job.note)
                        val plaintext = F.typed(job.out.type, job.out.payload)
                        for (fr in F.splitMessage(plaintext, job.out.chan, job.out.type)) {
                            val record = tls.encrypt(fr.chunk)
                            sendRaw(F.buildFrame(job.out.chan, fr.flags, record, fr.total))
                        }
                    }
                }
            } catch (e: Throwable) {
                // wrap мог пройти, а запись нет: поток TLS рассинхронизирован, продолжать нельзя
                fatal = true
                peerClosed = true
                if (stopReason == null) stopReason = "сбой исходящей записи: ${e.javaClass.simpleName}: ${e.message}"
                log("AA СБОЙ ЗАПИСИ, TLS-поток рассинхронизирован, закрываю сессию: ${e.javaClass.simpleName}: ${e.message}", true)
                outQueue.clear()
                (job as? MarkerJob)?.latch?.countDown()
                writerActive = false
                return
            }
        }
    }

    /** Ставит сообщение в исходящую очередь (никогда не пишет в сокет из вызывающей нити). */
    private fun send(out: AapOut, media: Boolean = false, note: String = "") {
        if (!writerActive) {
            if (fatal) return                       // writer умер: сессия уже закрывается
            throw IllegalStateException("writer не запущен")
        }
        outQueue.offer(MsgJob(out, media, note))
    }

    private fun flushTls() {
        if (tls.wantsWrap()) outQueue.offer(TlsFlushJob)
    }

    /** Дождаться, пока writer отправит всё накопленное (или сдохнет). */
    private fun flushWriter(timeoutMs: Long) {
        if (!writerActive) return
        val latch = CountDownLatch(1)
        outQueue.offer(MarkerJob(latch))
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun stopWriter() {
        val t = writer ?: return
        if (writerActive) outQueue.offer(PoisonJob)
        t.join(3000)
        writerActive = false
    }

    private fun sendSsl(data: ByteArray) {
        if (data.isEmpty()) return
        log("AA TX CONTROL(0) ENCAPSULATED_SSL len=${data.size} plain frame", false)
        sendRaw(F.buildPlain(F.CHANNEL_CONTROL, M.ENCAPSULATED_SSL, data))
    }

    private fun mediaKey(chan: Int, type: Int) = (chan shl 16) or (type and 0xFFFF)

    private fun logMsg(dir: String, chan: Int, type: Int, payload: ByteArray, media: Boolean, note: String = "", text: String? = null) {
        val body = text ?: M.describe(chan, type, payload)
        log(
            "AA $dir ${M.channelName(chan)}($chan) ${M.messageName(chan, type)} 0x%04x len=${payload.size}".format(type) +
                (if (body.isNotEmpty()) " | $body" else "") + (if (note.isNotEmpty()) "  [$note]" else ""),
            !media
        )
    }

    // --- handshake ---
    private fun versionExchange() {
        val body = M.versionRequestBody()
        val frame = F.buildFrame(F.CHANNEL_CONTROL, F.FLAGS_PLAIN_BULK, body)
        log("AA TX CONTROL(0) VERSION_REQUEST: ${frame.joinToString(" ") { "%02x".format(it) }}", true)
        sendRaw(frame)
        val deadline = System.nanoTime() + VERSION_TIMEOUT_MS * 1_000_000
        while (true) {
            val fr = nextFrame(deadline) ?: break
            if (fr.chan == 0 && !fr.encrypted && fr.body.size >= 2 && u16(fr.body, 0) == M.VERSION_RESPONSE) {
                val v = M.parseVersionResponse(fr.body.copyOfRange(2, fr.body.size))
                    ?: throw SessionError("VERSION_RESPONSE слишком короткий")
                val extra = if (v.options.isNotEmpty()) " options=" + v.options.joinToString(" ") { "%02x".format(it) } else ""
                log("AA RX CONTROL(0) VERSION_RESPONSE | телефон выбрал ${v.major}.${v.minor} (просили ${M.VERSION_MAJOR}.${M.VERSION_MINOR}) status=${v.status}$extra", true)
                if (v.status != 0) throw SessionError("VERSION_RESPONSE status ${v.status}, телефон отказал протоколу ${v.major}.${v.minor}")
                return
            }
            log("AA игнорирую кадр до version response: ch=${fr.chan} flags=0x%02x".format(fr.flags), true)
        }
        throw SessionError(
            "нет VERSION_RESPONSE. Если соединение открыто, но тишина: сервер ГУ завис на прошлой сессии, " +
                "перезапусти его в настройках разработчика Android Auto (или force-stop AA)."
        )
    }

    private fun tlsHandshake() {
        val deadline = System.nanoTime() + TLS_TIMEOUT_MS * 1_000_000
        sendSsl(tls.handshakeStep())
        while (!tls.handshakeDone) {
            val fr = nextFrame(deadline) ?: throw SessionError("TLS handshake: таймаут")
            if (fr.encrypted || fr.body.size < 2) {
                log("AA неожиданный кадр во время TLS handshake: ch=${fr.chan} flags=0x%02x".format(fr.flags), true)
                continue
            }
            val type = u16(fr.body, 0)
            when (type) {
                M.ENCAPSULATED_SSL -> {
                    log("AA RX CONTROL(0) ENCAPSULATED_SSL len=${fr.body.size - 2} plain frame", false)
                    val out = try {
                        tls.handshakeStep(fr.body.copyOfRange(2, fr.body.size))
                    } catch (e: javax.net.ssl.SSLException) {
                        throw SessionError("TLS handshake не прошёл: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    sendSsl(out)
                }
                M.VERSION_RESPONSE -> log("AA отброшен запоздавший VERSION_RESPONSE", true)
                else -> log("AA неожиданное plain-сообщение во время TLS handshake: type=0x%04x".format(type), true)
            }
        }
    }

    private fun sendAuthComplete() {
        log("AA TX CONTROL(0) AUTH_COMPLETE | AuthResponse{status: 0}", true)
        sendRaw(F.buildPlain(F.CHANNEL_CONTROL, M.AUTH_COMPLETE, M.AUTH_COMPLETE_DATA))
    }

    // --- основной цикл ---
    private fun serve() {
        while (stopReason == null) {
            readMore(POLL_MS)
            while (frames.isNotEmpty() && stopReason == null) onFrame(frames.removeFirst())
            if (stopReason == null && !videoStarted && (System.nanoTime() - establishedAtNs) / 1_000_000 > opts.preVideoTimeoutMs) {
                stop("видео не началось за ${opts.preVideoTimeoutMs / 1000} с после AuthComplete")
            }
        }
    }

    private fun onFrame(fr: F.Frame) {
        if (!fr.encrypted) {
            onPlainFrame(fr)
            return
        }
        val d = try {
            tls.decrypt(fr.body, drain = false)
        } catch (e: javax.net.ssl.SSLException) {
            throw SessionError("TLS-ошибка от телефона: ${e.javaClass.simpleName}: ${e.message} (ch${fr.chan} flags=0x%02x)".format(fr.flags))
        }
        flushTls()
        if (tls.closed) throw PeerClosed("телефон прислал TLS close_notify")
        if (d.plaintext.isEmpty()) {
            log("AA RX ch${fr.chan} TLS-запись без данных приложения flags=0x%02x".format(fr.flags), false)
            return
        }
        val whole = reasm.push(fr.chan, fr.flags, d.plaintext, fr.total) ?: return
        if (whole.size < 2) {
            log("AA ch${fr.chan}: сообщение короче поля type", true)
            return
        }
        dispatch(fr.chan, u16(whole, 0), whole.copyOfRange(2, whole.size))
    }

    private fun onPlainFrame(fr: F.Frame) {
        if (fr.body.size < 2) {
            log("AA plain-кадр без type: ch=${fr.chan} flags=0x%02x".format(fr.flags), true)
            return
        }
        val type = u16(fr.body, 0)
        if (type == M.ENCAPSULATED_SSL) {
            // Пост-handshake записи TLS (тикеты TLS 1.3) могут прийти plain-кадрами; они обязаны пройти
            // через TLS-слой: он считает записи, пропуск сломал бы расшифровку всех следующих.
            tls.decrypt(fr.body.copyOfRange(2, fr.body.size), drain = false)
            log("AA RX CONTROL(0) ENCAPSULATED_SSL len=${fr.body.size - 2} post-handshake TLS (plain frame)", false)
            flushTls()
            return
        }
        log("AA RX ch${fr.chan} type=0x%04x неожиданный plain-кадр после handshake flags=0x%02x".format(type, fr.flags), true)
    }

    private fun dispatch(chan: Int, type: Int, payload: ByteArray) {
        val isMediaData = (chan == M.CH_VIDEO || chan in M.AUDIO_CHANNELS) && (type == M.MEDIA_DATA || type == M.MEDIA_CODEC_CONFIG)
        if (isMediaData) {
            onMediaData(chan, type, payload)
            return
        }
        // ack микрофона идёт 8 раз в секунду, статус плеера и дистанция навигации - раз в секунду: в файл, не в UI
        logMsg("RX", chan, type, payload, media = (chan == M.CH_MIC && type == M.MEDIA_ACK) || M.isPeriodicMeta(chan, type))

        if (type == M.CHANNEL_OPEN_REQUEST) {
            send(M.channelOpenResponse(chan))
            if (chan == M.CH_SENSOR) send(M.drivingStatusEvent(0))          // UNRESTRICTED
            return
        }
        when {
            chan == M.CH_CONTROL -> onControl(type, payload)
            chan == M.CH_SENSOR -> onSensor(type, payload)
            chan == M.CH_INPUT -> if (type == M.INPUT_BINDING_REQUEST) send(M.bindingResponse(chan))
            chan == M.CH_VIDEO || chan in M.AUDIO_CHANNELS || chan == M.CH_MIC -> onMediaControl(chan, type, payload)
            // MEDIA_PLAYBACK / NAVIGATION: телефон только присылает статус, отвечать нечего; метаданные -> мост
            chan == M.CH_MEDIA_PLAYBACK || chan == M.CH_NAVIGATION -> AaMeta.dispatch(chan, type, payload, metaSink)?.let {
                log("AA META ${M.channelName(chan)} ${M.messageName(chan, type)}: $it", !M.isPeriodicMeta(chan, type))
            }
        }
    }

    private fun firstVarint(payload: ByteArray, field: Int): Long? =
        PbReader.parse(payload)?.firstOrNull { it.number == field && it.wire == 0 }?.num

    private fun onControl(type: Int, payload: ByteArray) {
        when (type) {
            M.SERVICE_DISCOVERY_REQUEST -> send(M.serviceDiscoveryResponse(opts.sdProvider?.invoke() ?: opts.sd))
            M.PING_REQUEST -> send(M.pingResponse(firstVarint(payload, 1) ?: 0L))
            M.NAV_FOCUS_REQUEST -> send(M.navFocusNotification())
            M.AUDIO_FOCUS_REQUEST -> M.audioFocusResponse((firstVarint(payload, 1) ?: 0L).toInt())?.let { send(it) }
            M.BYEBYE_REQUEST -> {
                send(M.byebyeResponse())
                Thread.sleep(500)                                  // AapControl.kt:378
                byeAcked = true
                stop("телефон прислал ByeByeRequest")
                exitOk = true
            }
            M.BYEBYE_RESPONSE -> {
                byeAcked = true
                stop("получен ByeByeResponse")
                exitOk = true
            }
            M.VOICE_SESSION_NOTIFICATION -> audioSink?.onVoiceSession((firstVarint(payload, 1) ?: 0L).toInt())
            // CHANNEL_CLOSE_NOTIFICATION и прочее: только лог
        }
    }

    private fun onSensor(type: Int, payload: ByteArray) {
        if (type != M.SENSOR_START_REQUEST) return
        val sensorType = firstVarint(payload, 1)?.toInt()
        send(M.sensorStartResponse())
        if (sensorType == 10) {                                            // NIGHT
            nightSensorStarted = true                                      // до send: смена темы в этот момент уйдёт следом
            send(M.nightModeEvent(nightMode))
            log("AA SENSOR NIGHT запущен, night=$nightMode", true)
        }
    }

    private fun onMediaControl(chan: Int, type: Int, payload: ByteArray) {
        when {
            type == M.MEDIA_SETUP -> {
                val unacked = if (chan == M.CH_VIDEO) opts.maxUnackedVideo else opts.maxUnackedAudio
                send(M.mediaConfig(chan, unacked))
                if (chan == M.CH_VIDEO) send(M.videoFocusNotification(projected = true, unsolicited = true))
                else if (chan in M.AUDIO_CHANNELS) send(M.audioFocusGainUnsolicited())
            }
            type == M.MEDIA_START -> {
                sessionIds[chan] = (firstVarint(payload, 1) ?: 0L).toInt()
                if (chan == M.CH_VIDEO) videoStarted = true
                if (chan in M.AUDIO_CHANNELS) audioSink?.onAudioStart(chan)
            }
            type == M.MEDIA_STOP && chan in M.AUDIO_CHANNELS -> audioSink?.onAudioStop(chan)
            type == M.MEDIA_VIDEO_FOCUS_REQUEST && chan == M.CH_VIDEO -> {
                val mode = PbReader.parse(payload)?.firstOrNull { it.number == 2 && it.wire == 0 }?.num?.toInt()
                if (mode == 1) send(M.videoFocusNotification(projected = true, unsolicited = false))
                else if (mode == 2) {
                    log("AA VIDEO_FOCUS_REQUEST NATIVE («Выйти»)", true)
                    sink.onAaNativeFocusRequest()      // не отвечаем: NATIVE уйдёт, когда ГУ реально выйдет из CarLife
                }
            }
            type == M.MEDIA_MIC_REQUEST && chan == M.CH_MIC -> onMicRequest(payload)
            type == M.MEDIA_ACK && chan == M.CH_MIC -> onMicAck(payload)
            // MEDIA_STOP, MEDIA_ACK остальных каналов, UPDATE_UI_CONFIG_*: только лог
        }
    }

    private fun onMediaData(chan: Int, type: Int, payload: ByteArray) {
        val isData = type == M.MEDIA_DATA
        val body: ByteArray
        var ptsUs = 0L
        var note: String
        if (isData) {
            if (payload.size < 8) {
                log("AA ch$chan: DATA короче своего timestamp (${payload.size} Б)", true)
                return
            }
            for (i in 0 until 8) ptsUs = (ptsUs shl 8) or (payload[i].toLong() and 0xFF)
            body = payload.copyOfRange(8, payload.size)
            note = "ts=${ptsUs}us"
        } else {
            body = payload
            note = "codec config"
        }

        if (chan == M.CH_VIDEO) {
            stats.videoBytes += body.size
            if (isData) {
                stats.videoData++
                sink.onFrame(body, ptsUs)
                if (firstVideo.compareAndSet(0, System.nanoTime())) onFirstVideo()
            } else {
                stats.videoCodecConfig++
                sink.onCodecConfig(body)
            }
        } else if (isData) {
            stats.audioData++
            audioSink?.onAudioData(chan, body, ptsUs)
        }

        // CODEC_CONFIG редкий и важный - всегда; DATA прорежено
        val n = if (isData) rxMediaLog.next(mediaKey(chan, type)) else -1L
        if (n != 0L) logMsg("RX", chan, type, body, media = true, note = if (n > 0) "$note #$n" else note, text = "")
        // Видео подтверждает и DATA, и CODEC_CONFIG (AapTransport.kt dispatchVideo), аудио только DATA.
        if (chan == M.CH_VIDEO || isData) send(M.mediaAck(chan, sessionIds[chan] ?: 0), media = true)
    }

    // --- завершение ---
    /**
     * ByeBye, ждём ответ, закрываем так, чтобы сервер увидел чистый конец: ByeByeRequest (если телефон
     * не закончил сам), ждём ByeByeResponse или EOF до 2 с, shutdownOutput, дочитываем до EOF до 1.5 с
     * (закрытие с непрочитанными данными шлёт RST), и только потом close.
     */
    private fun shutdown() {
        log("AA завершаю: ${stopReason ?: "конец"}", true)
        if (!::sock.isInitialized) return
        try {
            if (::tls.isInitialized && tls.handshakeDone && !peerClosed && !byeAcked && !byeSent) {
                byeSent = true
                send(M.byebyeRequest())
                flushWriter(2000)
                Thread.sleep(150)                                  // AapTransport.kt:706
                waitBye(BYE_WAIT_MS)
            }
        } catch (e: Exception) {
            log("AA при отправке ByeBye: ${e.javaClass.simpleName}: ${e.message}", true)
        }
        stopWriter()                                               // всё накопленное уходит до shutdownOutput
        try {
            try {
                sock.shutdownOutput()
            } catch (_: IOException) {
            }
            if (!peerClosed) drainUntilEof(DRAIN_WAIT_MS)
        } finally {
            try {
                sock.close()
            } catch (_: IOException) {
            }
            log("AA сокет закрыт", true)
        }
    }

    /** Читаем (и отвечаем на bye телефона) до ByeByeResponse, EOF или таймаута. */
    private fun waitBye(timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        try {
            while (!byeAcked && System.nanoTime() < deadline) {
                readMore(minOf(POLL_MS.toLong(), maxOf((deadline - System.nanoTime()) / 1_000_000, 1)).toInt())
                while (frames.isNotEmpty()) {
                    val fr = frames.removeFirst()
                    if (!fr.encrypted) continue
                    val d = tls.decrypt(fr.body, drain = false)
                    flushTls()
                    val whole = if (d.plaintext.isNotEmpty()) reasm.push(fr.chan, fr.flags, d.plaintext, fr.total) else null
                    if (whole == null || whole.size < 2) continue
                    val t = u16(whole, 0)
                    if (fr.chan == M.CH_CONTROL && (t == M.BYEBYE_RESPONSE || t == M.BYEBYE_REQUEST)) {
                        logMsg("RX", fr.chan, t, whole.copyOfRange(2, whole.size), false)
                        if (t == M.BYEBYE_REQUEST) send(M.byebyeResponse())
                        byeAcked = true
                    }
                }
            }
        } catch (_: PeerClosed) {
            peerClosed = true
            log("AA телефон закрыл соединение после ByeBye", true)
        }
        if (!byeAcked && !peerClosed) log("AA нет ByeByeResponse за ${timeoutMs / 1000.0} с, закрываю всё равно", true)
    }

    private fun drainUntilEof(timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            sock.soTimeout = maxOf(((deadline - System.nanoTime()) / 1_000_000).toInt(), 1)
            try {
                if (sock.getInputStream().read(readBuf) < 0) return
            } catch (_: IOException) {
                return
            }
        }
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    companion object {
        private const val VERSION_TIMEOUT_MS = 10_000L      // AapTransport.kt HANDSHAKE_TIMEOUT_MS
        private const val TLS_TIMEOUT_MS = 15_000L          // AapSslContext.kt SSL_HANDSHAKE_TIMEOUT_MS
        private const val POLL_MS = 100
        private const val BYE_WAIT_MS = 2_000L
        private const val DRAIN_WAIT_MS = 1_500L
        private const val KEYFRAME_CYCLE_GAP_MS = 500L
    }
}
