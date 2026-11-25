// Порядок действий и таймауты — по FT (github.com/OneAboveAll1964/ft-app, Apache-2.0):
// IccoaSession.kt, IccoaService.kt, IccoaLaneServer.kt, IccoaMediaTransport.kt. Код свой; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import android.graphics.Bitmap
import android.graphics.Rect
import com.yegorkapomidorka.headswap.AnnexB
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.Ch
import com.yegorkapomidorka.headswap.HuSession
import com.yegorkapomidorka.headswap.Msg
import com.yegorkapomidorka.headswap.PbReader
import com.yegorkapomidorka.headswap.VideoSource
import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import com.yegorkapomidorka.headswap.aa.AaTranscoder
import com.yegorkapomidorka.headswap.aa.CarlifeAudio
import com.yegorkapomidorka.headswap.aa.CarlifeLink
import com.yegorkapomidorka.headswap.aa.DesktopVideoGeometry
import com.yegorkapomidorka.headswap.aa.IdrThrottle
import com.yegorkapomidorka.headswap.aa.TranscodeOutput
import com.yegorkapomidorka.headswap.aa.VideoCrop
import com.yegorkapomidorka.headswap.aa.VideoGeometry
import com.yegorkapomidorka.headswap.aa.VideoTranscoder
import com.yegorkapomidorka.headswap.dex.DesktopAudioOutputLease
import com.yegorkapomidorka.headswap.dex.DesktopVideoDelivery
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Адреса и порты одной сессии. Порты телефона — параметры, чтобы тест взял свободные. */
class IccoaConfig(
    val carHost: String,
    /** PIN с экрана машины; null — только быстрый вход по сохранённому ключу. */
    val pin: String?,
    /** Адрес телефона в сети машины: уходит в Confirm и в wfd_presentation_URL. */
    val localIp: String,
    val model: String,
    /** Под каким именем хранится ключ машины. */
    val carId: String = carHost,
    /** На каком адресе слушать; null — на всех (соединения всё равно принимаем только от carHost). */
    val bindHost: String? = null,
    val authPort: Int = IccoaFrame.AUTH_PORT,
    val controlPort: Int = IccoaFrame.CONTROL_PORT,
    val audioPort: Int = IccoaFrame.AUDIO_PORT,
    val sensorPort: Int = IccoaFrame.SENSOR_PORT,
    val rtspPort: Int = IccoaRtsp.PORT,
    val uibcPort: Int = IccoaRtsp.UIBC_PORT,
)

/** Всё, что в бою требует Android или сети машины; тест подставляет своё. */
class IccoaPlatform(
    val log: (String) -> Unit = { AppLog.i(it, false) },
    val nowUs: () -> Long = { System.nanoTime() / 1000 },
    /** Исходящие сокеты (auth, RTP); в Э4 сюда придёт Network.bindSocket. */
    val socket: () -> Socket = { Socket() },
    /** Заглушка: ширина, высота, fps, битрейт, слушатель, видимая область, формат рабочего стола. null — заглушки нет. */
    val stub: (Int, Int, Int, Int, VideoSource.Listener, Rect?, Boolean) -> VideoSource? =
        { w, h, fps, bps, listener, area, desktop -> VideoSource(w, h, fps, bps, listener, area, matchDesktopSps = desktop) },
    /** Энкодер моста: геометрия, fps, выход, directSurface (рабочий стол), поля AA снизу. */
    val transcoder: (VideoGeometry, Int, TranscodeOutput, Boolean, Boolean) -> VideoTranscoder =
        { m, fps, out, direct, marginsAtBottom ->
            AaTranscoder(m.outW, m.outH, fps, m.bitrate, out,
                srcWidth = if (direct) m.outW else m.aaW, srcHeight = if (direct) m.outH else m.aaH,
                crop = if (direct) VideoCrop.NONE else m.cropFor(marginsAtBottom),
                directSurface = direct, capabilityFps = fps).also { it.start() }
        },
    val aac: ((ByteArray, Long, Long) -> Unit, (String) -> Unit) -> IccoaAudioEncoder? =
        { onFrame, onFailure -> IccoaAacEncoder(onFrame, onFailure, nowUs) },
)

/**
 * Сессия ICCOA CarLink: вход (57209), каналы машины к телефону (control, audio, sensor, RTSP, UIBC), RTP/TS к машине.
 * Стартует без ядра моста: экран машины известен только из RTSP M3, тогда [onScreen] и [bind]. До [bind] всё,
 * что просит ядро, отбрасывается. Нити: ic-io (чтение каналов, вход, писатель RTP), ic-sched (heartbeat, тик RTSP,
 * старт и пауза видео). PIN, ключи и HMAC в лог не попадают.
 */
class IccoaSession(
    private val cfg: IccoaConfig,
    private val trust: IccoaTrust,
    private val platform: IccoaPlatform = IccoaPlatform(),
    /** Экран машины из M3, один раз за сессию, с нити RTSP. */
    private val onScreen: (IccoaVideoConfig) -> Unit,
    private val onClosed: (String) -> Unit,
) : CarlifeLink, HuSession, VideoSource.Listener {

    override val transportKind: String get() = KIND

    private val closed = AtomicBoolean(false)
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "ic-io").also { it.isDaemon = true } }
    private val sched = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ic-sched").also { it.isDaemon = true } }
    private val open = Collections.newSetFromMap(ConcurrentHashMap<Closeable, Boolean>())
    private val t0 = nowMs()
    private val lease = DesktopAudioOutputLease(t0)
    private val delivery = DesktopVideoDelivery()

    @Volatile private var carAddress: InetAddress? = null
    @Volatile private var cipher: IccoaFrame.Cipher? = null
    private var pendingCarKey: ByteArray? = null            // под this: ключ машины ждёт первого шифрованного control
    @Volatile private var controlOut: OutputStream? = null
    private val sequence = AtomicInteger(1)
    private var heartbeat: IccoaHeartbeat? = null
    private val seenMethods = HashSet<Int>()                // нить control

    private val rtspLock = Any()
    private var rtsp: IccoaRtsp? = null                     // под rtspLock
    @Volatile private var rtspSocket: Socket? = null
    private val screenSent = AtomicBoolean(false)
    private val configAsked = AtomicBoolean(false)
    @Volatile private var video: IccoaVideoConfig? = null

    private val mediaLock = Any()
    private val queue = IccoaStreamQueue()
    @Volatile private var muxer: IccoaTsMuxer? = null
    private var rtp: Socket? = null                         // под mediaLock
    @Volatile private var aac: IccoaAudioEncoder? = null
    @Volatile private var writingSinceUs = 0L
    @Volatile private var csd: ByteArray? = null
    private var overflows = 0
    private var frames = 0L

    @Volatile private var bridge: AaBridgeCore? = null
    @Volatile private var mode: VideoGeometry? = null
    @Volatile private var playing = false                   // RTSP PLAY получен, RTP открыт
    @Volatile private var foreground = true                 // control m4/m5
    @Volatile private var streaming = false                 // меняет только ic-sched

    // заглушка (VideoSource с текстом) или видео ядра; переключается ядром моста
    private val videoLock = Any()
    private val srcLock = Any()
    @Volatile private var bannerText: String? = null
    private var source: VideoSource? = null                 // под srcLock
    @Volatile private var stubCsd: ByteArray? = null
    @Volatile private var stubCropped = false
    private var acceptEncoder = true                        // под videoLock
    private var csdPending = true
    private val placeholderIdr = IdrThrottle()

    private val player = IccoaAudioPlayerState()            // под самим собой
    private var mediaOpen = false
    private var musicOn = true
    @Volatile private var mediaFormatOk = false

    private fun nowMs() = platform.nowUs() / 1000

    private fun ev(phase: String, event: String, vararg kv: Pair<String, Any?>) =
        platform.log("IC $phase $event" + kv.joinToString("") { " ${it.first}=${it.second ?: "-"}" })

    private fun after(ms: Long, task: () -> Unit) {
        try {
            sched.schedule({ if (!closed.get()) guarded(task) }, ms, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {           // сессия уже закрывается
        }
    }

    private fun guarded(task: () -> Unit) = try {
        task()
    } catch (e: Exception) {
        ev("link", "task-error", "err" to e.javaClass.simpleName)
    }

    private fun onIo(task: () -> Unit) {
        try {
            io.execute(task)
        } catch (_: RejectedExecutionException) {
        }
    }

    // ---------------- жизнь сессии ----------------

    override fun start() {
        ev("link", "begin", "transport" to KIND)
        try {
            carAddress = InetAddress.getByName(cfg.carHost)
            val bind = cfg.bindHost?.let(InetAddress::getByName)
            // Порты открыты до входа: после Confirm машина подключается сразу.
            for ((port, lane) in listOf(cfg.controlPort to CONTROL, cfg.audioPort to "audio", cfg.sensorPort to "sensor",
                cfg.rtspPort to RTSP, cfg.uibcPort to UIBC)) {
                val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bind, port), 2) }
                open += server
                onIo { accept(server, lane) }
            }
        } catch (e: Exception) {
            close("порты ICCOA не открылись (${e.javaClass.simpleName})")
            return
        }
        onIo(::writer)
        onIo(::signIn)
        try {
            sched.scheduleWithFixedDelay({ guarded(::tick) }, 1, 1, TimeUnit.SECONDS)
        } catch (_: RejectedExecutionException) {
        }
    }

    /**
     * Ядро моста готово под геометрию экрана машины. Главная нить BridgeService. RTSP к этому времени уже мог дойти
     * до PLAY: видео начнётся, как только есть и PLAY, и ядро.
     */
    fun bind(core: AaBridgeCore) {
        if (closed.get()) return
        mode = core.videoMode
        bridge = core
        core.attach(this)
        // FEATURE_CONFIG у ICCOA нет: без этого AA ждал бы его перед подключением.
        core.onHuFeatureConfig(null)
        // Звук только по link: при работающем CarLink машина музыку по BT не берёт (YU7). У рабочего стола
        // этим флагом владеет DesktopAudioCapture.
        if (!core.desktopInputEnabled) core.usbAudioEnabled = true
        ev("link", "bind", "mode" to core.videoMode.label, "transcode" to core.transcodeEnabled)
        if (closed.get()) core.onCarlifeClosed(this) else update()
    }

    override fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        ev("link", "end", "why" to reason, "aliveS" to (nowMs() - t0) / 1000, "frames" to frames, "overflows" to overflows)
        delivery.close()
        lease.close()
        heartbeat?.close()
        bridge?.onCarlifeClosed(this)
        queue.pause()
        sched.shutdownNow()
        closeRtp()
        for (c in open) runCatching { c.close() }
        io.shutdownNow()
        val old = synchronized(srcLock) { source.also { source = null } }
        runCatching { old?.stop() }
        onClosed(reason)
    }

    override fun canCaptureDesktopAudio(): Boolean = !closed.get() && lease.canCapture(nowMs())
    override fun hasFreshDesktopVideo(): Boolean = !closed.get() && delivery.isFlowing(nowMs())

    // ---------------- вход (телефон → машина:57209) ----------------

    private fun signIn() {
        try {
            val identity = trust.identity()
            val pinned = trust.carKey(cfg.carId)
            var quick = pinned != null
            var workMode = 0
            while (!closed.get()) {
                if (!quick && cfg.pin == null) {
                    close("нужен PIN с экрана машины")
                    return
                }
                val auth = IccoaAuth(if (quick) null else cfg.pin, trust.clientId, cfg.model, identity,
                    if (quick) pinned else null, WORK_MODES[workMode])
                try {
                    exchange(auth)
                    return
                } catch (e: IccoaHandshakeException) {
                    val result = if (e.reason == IccoaHandshakeException.Reason.RESPONSE_RESULT) e.result else null
                    if (result == 4L && workMode < WORK_MODES.lastIndex) {
                        workMode++
                        ev("auth", "retry", "why" to "work-mode", "next" to WORK_MODES[workMode])
                    } else if (result == 1L && quick && cfg.pin != null) {
                        // Машина нас забыла: знакомимся заново по PIN.
                        quick = false
                        ev("auth", "retry", "why" to "quick-rejected")
                    } else throw e
                }
            }
        } catch (e: Exception) {
            if (closed.get()) return
            // Текст исключения может нести данные машины: в лог и причину идёт только код.
            val why = (e as? IccoaHandshakeException)?.diagnostic ?: e.javaClass.simpleName
            close("вход ICCOA не удался: $why")
        }
    }

    /** Одна попытка: запрос, проверка ответа, Confirm. После Confirm машина сама идёт на порты телефона. */
    private fun exchange(auth: IccoaAuth) {
        val socket = platform.socket()
        open += socket
        try {
            socket.connect(InetSocketAddress(carAddress, cfg.authPort), 8_000)
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
            val out = socket.getOutputStream()
            ev("auth", "request", "quick" to auth.quick)
            out.write(IccoaFrame.frame(IccoaFrame.CAT_AUTH, 1, IccoaFrame.TYPE_REQUEST, auth.request(), 1))
            out.flush()
            val response = IccoaFrame.read(socket.getInputStream()) { ev("auth", "header $it") }
            if (response.category != IccoaFrame.CAT_AUTH) throw IccoaHandshakeException(IccoaHandshakeException.Reason.RESPONSE_LANE)
            val key = auth.accept(response.body) { ev("auth", it) }
            synchronized(this) { pendingCarKey = if (auth.quick) null else auth.verifiedCarIdentity }
            cipher = key
            out.write(IccoaFrame.frame(IccoaFrame.CAT_AUTH, 3, IccoaFrame.TYPE_NOTIFY, auth.confirm(key, cfg.localIp), 2))
            out.flush()
            ev("auth", "ok", "quick" to auth.quick)
            after(CONTROL_WAIT_MS) { if (controlOut == null) close("машина не подключилась к control") }
            // Канал входа стартовый: машина закроет его сама, до тех пор просто читаем.
            socket.soTimeout = 0
            try {
                while (IccoaFrame.readOrNull(socket.getInputStream()) != null) Unit
            } catch (_: Exception) {
            }
        } finally {
            open -= socket
            runCatching { socket.close() }
        }
    }

    /** Ключ машины сохраняется только после первого шифрованного кадра control: Confirm сам ничего не доказывает. */
    @Synchronized private fun commitTrust() {
        val key = pendingCarKey ?: return
        pendingCarKey = null
        // PIN с экрана машины только что проверен HMAC: прежний ключ (машину сбросили) заменяем.
        val replaced = trust.carKey(cfg.carId)?.let { !it.contentEquals(key) } ?: false
        trust.saveCarKey(cfg.carId, key)
        ev("auth", "car-key-saved", "replaced" to replaced)
    }

    // ---------------- каналы машина → телефон ----------------

    private fun accept(server: ServerSocket, lane: String) {
        try {
            while (!closed.get()) {
                val socket = server.accept()
                val key = cipher
                // До входа и не от машины никого не пускаем.
                if (key == null || socket.inetAddress != carAddress) {
                    ev("tcp", "reject", "lane" to lane)
                    socket.close()
                    continue
                }
                socket.tcpNoDelay = true
                open += socket
                ev("tcp", "accept", "lane" to lane)
                onIo { serve(lane, socket, key) }
            }
        } catch (e: Exception) {
            if (!closed.get()) close("$lane: слушатель закрыт (${e.javaClass.simpleName})")
        }
    }

    private fun serve(lane: String, socket: Socket, key: IccoaFrame.Cipher) {
        try {
            when (lane) {
                CONTROL -> control(socket, key)
                RTSP -> rtsp(socket)
                UIBC -> uibc(socket, key)
                else -> frames(lane, socket, socket.getOutputStream(), key) { frame, body ->
                    // shortcut: датчики (cat 2) и микрофон (cat 4) только считаем, разбор в Э5/Э6
                    if (seenOnce(frame.category shl 16 or frame.method)) ev(lane, "rx", "cat" to frame.category, "m" to frame.method, "bytes" to body.size)
                    true
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) ev("tcp", "lane-error", "lane" to lane,
                "err" to ((e as? IccoaHandshakeException)?.diagnostic ?: e.javaClass.simpleName))
        } finally {
            open -= socket
            runCatching { socket.close() }
            ev("tcp", "closed", "lane" to lane)
            // Конец сессии — по закрытию control, остальные каналы машина может переоткрыть.
            if (lane == CONTROL) close("control закрыт")
        }
    }

    @Synchronized private fun seenOnce(id: Int) = seenMethods.add(id)

    /** Кадры одного канала до EOF: ack на type 3, расшифровка. [handle] = false — выйти. */
    private fun frames(lane: String, socket: Socket, out: OutputStream, key: IccoaFrame.Cipher,
                       handle: (IccoaFrame.Frame, ByteArray) -> Boolean) {
        val input = BufferedInputStream(socket.getInputStream())
        while (!closed.get()) {
            val frame = IccoaFrame.readOrNull(input) ?: return
            if (frame.type == IccoaFrame.TYPE_NEEDS_ACK) synchronized(out) {
                out.write(IccoaFrame.ack(frame.sequence))
                out.flush()
            }
            // Порт control передаём константой: по нему decode решает, доказан ли вход.
            val inbound = IccoaFrame.decode(if (lane == CONTROL) IccoaFrame.CONTROL_PORT else 0, frame, key)
            if (inbound.authenticatedControl) commitTrust()
            if (!handle(frame, inbound.body)) return
        }
    }

    private fun control(socket: Socket, key: IccoaFrame.Cipher) {
        val out = socket.getOutputStream()
        controlOut = out
        heartbeat = IccoaHeartbeat({ sendControl(IccoaControl.HEARTBEAT, it) }, ::after).also { it.start() }
        audioState()
        frames(CONTROL, socket, out, key) { frame, body ->
            if (frame.category == IccoaFrame.CAT_CONTROL) onControl(frame.method, body) else true
        }
    }

    private fun onControl(method: Int, body: ByteArray): Boolean {
        when (method) {
            IccoaControl.HEARTBEAT -> Unit
            IccoaControl.GET_PORT_REQUEST -> IccoaPortNegotiation.responseFor(body)?.let {
                ev("control", "get-port", "sent" to sendControl(IccoaControl.GET_PORT_RESPONSE, it))
            }
            IccoaControl.CAR_FOREGROUND -> { ev("control", "foreground"); foreground = true; update() }
            IccoaControl.CAR_BACKGROUND, PAUSE_CAST -> { ev("control", "background", "m" to method); foreground = false; update() }
            IccoaControl.KEY_EVENT -> IccoaControl.key(body)?.let { if (!it.up) key(it.keyCode) }
            VOICE_BUTTON -> key(IccoaControl.KEY_VOICE)
            // Разбор m25 связь не рвёт: поля у машин разные.
            IccoaControl.CONFIG_RESPONSE -> ev("control", "config",
                "v" to runCatching { IccoaCarConfig.describe(body) }.getOrElse { "unreadable bytes=${body.size}" })
            // Машина спрашивает список «приложений» телефона: пустой ответ, как у FT.
            APP_LIST_REQUEST -> sendControl(APP_LIST_REQUEST + 1, ByteArray(0))
            IccoaControl.DISCONNECT -> {
                close("машина завершила сессию (m23)")
                return false
            }
            else -> if (seenOnce(method)) ev("control", "rx", "m" to method, "bytes" to body.size)
        }
        return true
    }

    /** Кадр cat 1 машине. Тип 0 и свой счётчик seq на всё, включая ответы, — как у FT. */
    private fun sendControl(method: Int, body: ByteArray): Boolean {
        val out = controlOut ?: return false
        val key = cipher ?: return false
        return try {
            synchronized(out) {
                out.write(IccoaFrame.frame(IccoaFrame.CAT_CONTROL, method, IccoaFrame.TYPE_NOTIFY, key.encrypt(body), sequence.getAndIncrement()))
                out.flush()
            }
            lease.writeCompleted(nowMs())
            true
        } catch (e: Exception) {
            false                                           // обрыв увидит нить чтения control
        }
    }

    private fun key(androidKeyCode: Int) {
        val voice = androidKeyCode == IccoaControl.KEY_VOICE || androidKeyCode == IccoaControl.KEY_VOICE_ALT
        bridge?.onCarKey(if (voice) KEYCODE_SEARCH else androidKeyCode)
    }

    // ---------------- RTSP (7236) и RTP к машине ----------------

    private fun rtsp(socket: Socket) {
        val out = socket.getOutputStream()
        // RTP идёт на адрес пира RTSP, как у FT.
        val machine = IccoaRtsp(socket.inetAddress.hostAddress ?: cfg.carHost, cfg.localIp,
            send = { out.write(it.toByteArray(Charsets.ISO_8859_1)); out.flush() },
            onPlay = { c -> onIo { play(c) } },             // подключение RTP не под замком RTSP
            onPause = { ev("rtsp", "pause"); playing = false; update() },
            onKeyframe = ::keyframe,
            onConfig = { c ->
                video = c
                if (screenSent.compareAndSet(false, true)) {
                    ev("rtsp", "screen", "size" to "${c.width}x${c.height}", "fps" to c.fps, "dpi" to c.dpi, "rtpPort" to c.rtpPort)
                    onScreen(c)
                }
            },
            clockMs = ::nowMs)
        try {
            synchronized(rtspLock) {
                rtsp = machine
                rtspSocket = socket
                machine.start()                             // M1: первым говорит телефон
            }
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            while (!closed.get() && !machine.tornDown) {
                val message = IccoaRtsp.read(input)
                synchronized(rtspLock) { machine.onMessage(message) }
            }
        } finally {
            synchronized(rtspLock) { if (rtsp === machine) rtsp = null }
            playing = false
            closeRtp()
            update()
        }
    }

    /** ic-sched, раз в секунду: keep-alive RTSP и сторож записи RTP. */
    private fun tick() {
        val alive = synchronized(rtspLock) { runCatching { rtsp?.tick() ?: true }.getOrDefault(false) }
        if (!alive) {
            ev("rtsp", "keepalive-timeout")
            runCatching { rtspSocket?.close() }
        }
        val since = writingSinceUs
        if (since != 0L && platform.nowUs() - since > WRITE_TIMEOUT_US) close("RTP: запись зависла")
    }

    private fun play(c: IccoaVideoConfig) {
        try {
            synchronized(mediaLock) {
                if (closed.get()) return
                if (muxer == null) {
                    val socket = platform.socket()
                    open += socket
                    socket.tcpNoDelay = true
                    socket.sendBufferSize = 512 * 1024
                    socket.connect(InetSocketAddress(c.host, c.rtpPort), 4_000)
                    rtp = socket
                    muxer = IccoaTsMuxer(BufferedOutputStream(socket.getOutputStream(), 65536))
                    aac = runCatching { platform.aac(queue::audio) { ev("audio", "aac-failed", "err" to it) } }
                        .onFailure { ev("audio", "aac-unavailable", "err" to it.javaClass.simpleName) }.getOrNull()
                }
            }
            video = c
            ev("rtsp", "play", "size" to "${c.width}x${c.height}", "fps" to c.fps, "rtpPort" to c.rtpPort)
            playing = true
            update()
            // Конфиг машины (m25) просим через 3 с после первого PLAY, как FT.
            if (configAsked.compareAndSet(false, true)) after(3_000) { sendControl(IccoaControl.CONFIG_REQUEST, ByteArray(0)) }
        } catch (e: Exception) {
            close("RTP: нет соединения (${e.javaClass.simpleName})")
        }
    }

    private fun closeRtp() = synchronized(mediaLock) {
        muxer = null
        aac?.close()
        aac = null
        rtp?.let { open -= it; runCatching { it.close() } }
        rtp = null
    }

    /** Видео идёт, когда есть PLAY, ядро и машина не в фоне. Переходы — только на ic-sched, по одному. */
    private fun update() {
        try {
            sched.execute { guarded(::apply) }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun apply() {
        val core = bridge
        val want = playing && foreground && core != null && !closed.get()
        if (want == streaming) {
            if (want) keyframe()                            // повторный PLAY: машине нужен IDR
            return
        }
        streaming = want
        if (want) {
            ev("video", "start")
            delivery.start(nowMs())
            queue.resume(platform.nowUs())
            synchronized(videoLock) { csdPending = true }
            // START ядру: сброс звука и IDR (у AA — цикл фокуса, у энкодера — sync frame).
            core!!.onCarlifeStart()
            if (bannerText != null) startOrUpdatePlaceholder()
        } else {
            ev("video", "pause")
            delivery.pause()
            queue.pause()
            core?.onCarlifePause()
        }
        audioState()
    }

    private fun keyframe() {
        synchronized(srcLock) { source }?.requestKeyFrame()
        bridge?.onCarlifeKeyFrameRequest()
    }

    private fun writer() {
        var applied: ByteArray? = null
        var target: IccoaTsMuxer? = null
        while (!closed.get()) {
            var out: IccoaTsMuxer? = null
            try {
                val frame = queue.poll(100) ?: continue
                out = muxer
                if (out == null || !queue.current(frame)) continue
                if (target !== out) { target = out; applied = null }
                writingSinceUs = platform.nowUs()
                if (frame.video) {
                    val config = csd
                    if (config != null && applied !== config) { out.configure(config); applied = config }
                    out.video(frame.data, frame.key, frame.pts)
                } else out.audio(frame.data, frame.pts)
                writingSinceUs = 0
                val now = nowMs()
                lease.writeCompleted(now)
                if (frame.video) {
                    delivery.writeCompleted(Ch.VIDEO, Msg.VIDEO_DATA, frame.data.size, now)
                    if (frames++ == 0L) ev("video", "first-frame", "bytes" to frame.data.size, "key" to frame.key)
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                writingSinceUs = 0
                // RTP закрыли сами (TEARDOWN, конец RTSP) — не обрыв.
                if (muxer === out && !closed.get()) close("RTP: запись (${e.javaClass.simpleName})")
            }
        }
    }

    // ---------------- UIBC (4321) ----------------

    private fun uibc(socket: Socket, key: IccoaFrame.Cipher) {
        var last: IntArray? = null
        val gesture = IccoaInputGesture {
            last?.let { bridge?.onCarlifeTouch(IccoaTouch.UP, it[0], it[1]) }
            ev("uibc", "released-on-eof")
        }
        val json = IccoaInputJson { event -> onInput(event)?.let { gesture.accept(it[2]); last = it } }
        IccoaInputReader.read(socket.getInputStream(), key::decrypt, json) { ev("uibc", it) }
        gesture.finish()
    }

    /** Тач в кадр ГУ; возвращает (x, y, action) первого пальца. Кнопки 3/4 — сразу в ядро. */
    private fun onInput(event: JSONObject): IntArray? {
        if (event.optInt("type") != 1) {
            IccoaInputEvent.key(event)?.let(::key)
            return null
        }
        val screen = video ?: return null
        val frame = mode ?: return null
        val core = bridge ?: return null
        // touch() приводит координаты к экрану машины (width/height события), отсюда — в кадр ГУ.
        val touch = IccoaInputEvent.touch(event, screen) ?: return null
        fun x(v: Int) = if (v < 0) 0 else (v.toLong() * frame.outW / screen.width).toInt()
        fun y(v: Int) = if (v < 0) 0 else (v.toLong() * frame.outH / screen.height).toInt()
        core.onCarlifeTouch(touch.action or (touch.index shl 8), x(touch.x), y(touch.y), x(touch.x2), y(touch.y2))
        return intArrayOf(x(touch.x), y(touch.y), touch.action)
    }

    // ---------------- CarlifeLink: видео ----------------

    override fun sendVideo(payload: ByteArray): Boolean {
        if (closed.get() || !streaming) return false
        return synchronized(videoLock) { enqueue(payload) }
    }

    override fun sendVideoTakeover(payload: ByteArray): Boolean {
        if (closed.get() || !streaming) return false
        synchronized(videoLock) {
            if (!enqueue(payload)) return false
            // под тем же замком, что и onFrame заглушки: после IDR ядра ни один её кадр уже не уйдёт
            acceptEncoder = false
            return true
        }
    }

    /** Кадр в очередь TS. PTS — время прихода. false — очередь выбросила GOP, ядро запросит IDR. */
    private fun enqueue(payload: ByteArray): Boolean {
        val m = mode ?: return false
        val data = AnnexB.fixSps(payload, m.outW, m.outH)
        val (key, config) = head(data)
        // SPS/PPS запоминаем: мукс подставит их перед IDR, который пришёл без них.
        if (config != null) csd = config
        if (!queue.video(data, key, platform.nowUs())) return true
        if (overflows++ == 0) ev("video", "overflow", "frames" to frames)
        return false
    }

    override fun createTranscoder(out: TranscodeOutput): VideoTranscoder? = encoder(out, false)

    override fun createSurfaceEncoder(out: TranscodeOutput): VideoTranscoder? = encoder(out, true)

    private fun encoder(out: TranscodeOutput, direct: Boolean): VideoTranscoder? = try {
        val m = mode ?: error("no geometry")
        if (direct) require(DesktopVideoGeometry.matchesNative(m)) { "Desktop encoder requires native geometry" }
        ev("video", "encoder", "size" to "${m.outW}x${m.outH}", "fps" to fps(), "kbps" to m.bitrate / 1000, "direct" to direct)
        platform.transcoder(m, fps(), out, direct, bridge?.marginsAtBottom == true)
    } catch (e: Exception) {
        ev("video", "encoder-failed", "err" to e.javaClass.simpleName)
        null
    }

    private fun fps() = minOf(video?.fps ?: MAX_FPS, MAX_FPS)

    override fun showPlaceholder(text: String) {
        bannerText = text
        synchronized(videoLock) {
            csdPending = true
            acceptEncoder = true
        }
        if (streaming && !closed.get()) startOrUpdatePlaceholder()
    }

    private fun startOrUpdatePlaceholder() {
        val core = bridge ?: return
        val m = mode ?: return
        synchronized(srcLock) {
            val text = bannerText ?: return
            source?.let {
                it.banner = text
                it.requestKeyFrame()
                return
            }
            // h529: в пробросе с SPS-кропом заглушка в кадре AA с теми же полями, машина не видит смены размера.
            stubCropped = core.stubInAaGeometry
            val s = try {
                if (stubCropped) {
                    val (x, y) = core.spsCropOrigin
                    platform.stub(m.aaW, m.aaH, fps(), m.bitrate, this, Rect(x, y, x + m.outW, y + m.outH), false)
                } else platform.stub(m.outW, m.outH, fps(), m.bitrate, this, null, core.desktopInputEnabled)
            } catch (e: Exception) {
                null
            } ?: return
            s.banner = text
            try {
                s.start()
                source = s
                ev("video", "stub", "size" to "${s.width}x${s.height}", "cropped" to stubCropped)
            } catch (e: Exception) {
                ev("video", "stub-failed", "err" to e.javaClass.simpleName)
                s.stop()
            }
        }
    }

    override fun placeholderSnapshot(): Bitmap? = try {
        synchronized(srcLock) { source }?.snapshot()
    } catch (e: Exception) {
        null
    }

    override fun hidePlaceholder() {
        synchronized(videoLock) { acceptEncoder = false }
        bannerText = null
        val old = synchronized(srcLock) { source.also { source = null } }
        if (old != null) Thread({ old.stop() }, "ic-placeholder-stop").start()
    }

    // VideoSource.Listener: кадры заглушки
    override fun onConfig(csd: ByteArray) {
        stubCsd = csd
    }

    override fun onFrame(data: ByteArray, key: Boolean) {
        if (closed.get() || !streaming) return
        synchronized(videoLock) {
            if (!acceptEncoder || (!key && csdPending)) return
            var payload = data
            if (key) {
                // SPS/PPS в каждом IDR заглушки: запомненные муксом могут быть от видео ядра.
                val c = stubCsd
                if (c != null && !AnnexB.startsWithSps(data)) payload = AnnexB.glue(c, data)
                if (stubCropped) bridge?.let { payload = it.stubSpsCrop(payload) }
            }
            if (enqueue(payload)) {
                if (key) csdPending = false
            } else {
                // кадр потерян: P после него слать нельзя, ждём IDR; запрос не чаще раза в 1,5 с
                csdPending = true
                if (placeholderIdr.tryRequest(nowMs())) synchronized(srcLock) { source }?.requestKeyFrame()
            }
        }
    }

    override fun onError(msg: String, t: Throwable?) = ev("video", "stub-error", "err" to (t?.javaClass?.simpleName ?: "-"))

    // ---------------- CarlifeLink: звук ----------------

    override fun sendAudio(channel: Int, type: Int, payload: ByteArray): Boolean {
        if (closed.get()) return false
        // shortcut: речь не подмешивается в AAC, решить AAC/PCM cat 4 после первой машины
        if (channel != CarlifeAudio.CH_MEDIA) return true
        when (type) {
            CarlifeAudio.MEDIA_INIT -> {
                val f = PbReader.parse(payload)
                val rate = f?.let { PbReader.int(it, 1) }
                val channels = f?.let { PbReader.int(it, 2) }
                // shortcut: AAC только 48 кГц стерео; другая частота из настроек музыки молчит, ресэмпл — в Э5
                mediaFormatOk = rate == 48000 && channels == 2
                ev("audio", "media-init", "rate" to rate, "channels" to channels, "ok" to mediaFormatOk)
                media(true)
            }
            CarlifeAudio.MEDIA_RESUME -> media(true)
            CarlifeAudio.MEDIA_STOP, CarlifeAudio.MEDIA_PAUSE -> media(false)
            CarlifeAudio.MEDIA_DATA -> if (mediaFormatOk) queue.audioEpoch()?.let { aac?.pcm(payload, it) }
        }
        return true
    }

    override fun sendModuleStatus(module: Int, status: Int): Boolean {
        if (closed.get()) return false
        if (module == CarlifeAudio.MODULE_MUSIC) {
            synchronized(player) { musicOn = status != 0 }
            audioState()
        }
        return true
    }

    private fun media(on: Boolean) {
        synchronized(player) { mediaOpen = on }
        audioState()
    }

    /** m8: start на первой музыке, pause/resume дальше; пока control не поднят, помнит желаемое. */
    private fun audioState() = synchronized(player) {
        if (mediaOpen && musicOn && streaming) player.play() else player.pause()
        player.flush { sendControl(IccoaControl.AUDIO_PLAYER_STATE, it) }?.let { ev("audio", "player-state", "state" to it) }
    }

    override fun goToDesktop() {}

    companion object {
        const val KIND = "iccoa"
        private const val CONTROL = "control"
        private const val RTSP = "rtsp"
        private const val UIBC = "uibc"
        /** Перебор при result=4, как XDL. */
        private val WORK_MODES = intArrayOf(5, 769, 1)
        private const val CONTROL_WAIT_MS = 25_000L
        private const val WRITE_TIMEOUT_US = 5_000_000L
        private const val MAX_FPS = 30
        private const val KEYCODE_SEARCH = 84
        /** cat 1: кнопка голоса, пауза показа, запрос списка приложений (таблица XDL). */
        private const val VOICE_BUTTON = 16
        private const val PAUSE_CAST = 41
        private const val APP_LIST_REQUEST = 26

        /** Ключевой ли кадр и его SPS/PPS (всё до первого слайса). Слайсы не сканируем. */
        internal fun head(d: ByteArray): Pair<Boolean, ByteArray?> {
            var i = 0
            var sps = false
            while (i + 3 < d.size) {
                if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                    val type = d[i + 3].toInt() and 31
                    if (type == 7) sps = true
                    if (type in 1..5) {
                        val start = if (i > 0 && d[i - 1].toInt() == 0) i - 1 else i
                        return (type == 5) to (if (sps && start > 0) d.copyOfRange(0, start) else null)
                    }
                    i += 3
                } else i++
            }
            return false to null
        }
    }
}
