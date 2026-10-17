package com.yegorkapomidorka.headswap

import android.os.Build
import android.os.SystemClock
import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import com.yegorkapomidorka.headswap.aa.CarlifeAudio
import com.yegorkapomidorka.headswap.aa.AaTranscoder
import com.yegorkapomidorka.headswap.aa.BitratePolicy
import com.yegorkapomidorka.headswap.aa.BitrateProfile
import com.yegorkapomidorka.headswap.aa.CarlifeLink
import com.yegorkapomidorka.headswap.aa.FpsSetting
import com.yegorkapomidorka.headswap.aa.IdrThrottle
import com.yegorkapomidorka.headswap.aa.CarlifeMediaInfo
import com.yegorkapomidorka.headswap.aa.LinkVideoStats
import com.yegorkapomidorka.headswap.aa.NaviField6
import com.yegorkapomidorka.headswap.aa.NaviTurnInfo
import com.yegorkapomidorka.headswap.aa.TranscodeOutput
import com.yegorkapomidorka.headswap.aa.VideoMode
import com.yegorkapomidorka.headswap.aa.VideoQueueLimit
import com.yegorkapomidorka.headswap.aa.VideoTranscoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.yegorkapomidorka.headswap.link.CarTransport
import com.yegorkapomidorka.headswap.link.TransportCaps
import com.yegorkapomidorka.headswap.link.diag.WLog

/** Настройки сессии, снятые в момент старта. */
class SessionConfig(
    val screenOn: Boolean = true,
    /** Вместе с MIC_RECORD_* слать MODULE_STATUS VR 1/0, как Baidu (prepareShowVr / prepareCloseVr). */
    val micVrStatus: Boolean = true,
    /** Частота кадров: Авто (как просит ГУ) или фиксированная. В passthrough не действует. */
    val fpsSetting: FpsSetting = FpsSetting.DEFAULT,
    /** Трек, обложка и прогресс из AA (MEDIA_INFO / MEDIA_PROGRESS_BAR). */
    val metaMusic: Boolean = true,
    /** Подсказки поворотов из AA (NAVI_NEXT_TURN_INFO по подписке ГУ moduleID=0). */
    val metaNavi: Boolean = true,
    /** Поле 6 NAVI_NEXT_TURN_INFO: иконка и/или time. */
    val naviField6: NaviField6 = NaviField6.BOTH,
)

/**
 * Одна сессия с ГУ поверх транспорта (USB-поток или 6 сокетов): отвечает как MD, отдаёт мосту видео/звук/тач, пишет всё в лог.
 * Потоки: чтение и запись в транспорте (sink и onWritten сериализованы), enc-drain/render (VideoSource заглушки),
 * scheduler (статистика, watchdog тишины, отложенный MODULE_STATUS).
 * Протокол CarLife 2.0 по поведению MD Baidu CarLife 8.6.7, см. docs/carlife-v2-handshake.md.
 */
class CarLifeSession(
    private val transport: CarTransport,
    private val cfg: SessionConfig,
    private val bridge: AaBridgeCore,
    private val onClosed: (String) -> Unit,
) : VideoSource.Listener, CarlifeLink {

    /** Видео-режим хоста, снятый при подключении: одинаков для INIT_DONE, энкодера и тача в течение всей сессии. */
    private val mode: VideoMode = bridge.videoMode

    /** Транспорт сессии: usb-aoa, tcp-debug или tcp6. */
    val transportKind: String get() = transport.caps.kind

    /** Воздух (6 сокетов): своя лесенка битрейта, PAUSE-keepalive чаще, WL-строки видео. USB и tcp-debug - как было. */
    private val air = transport.caps == TransportCaps.TCP6
    private val bitratePolicy = BitratePolicy(mode.bitrate, profile = if (air) BitrateProfile.WIFI else BitrateProfile.USB)
    @Volatile private var vwIdrCutMs = NEVER_MS
    private val pauseKeepaliveMs = if (air) PAUSE_KEEPALIVE_AIR_MS else PAUSE_KEEPALIVE_MS
    private var overflows = 0
    private var maxQueueFrames = 0
    private val placeholderIdr = IdrThrottle()
    @Volatile private var lastOverflowMs = NEVER_MS
    private var passthroughBitrateLogged = false
    private var spsFixLogged = false
    private val closed = AtomicBoolean(false)
    private val sched: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cl-sched") }
    private val metaExec = Executors.newSingleThreadExecutor { r -> Thread(r, "cl-meta").also { it.isDaemon = true } }
    private var lastArtSrc: ByteArray? = null          // только нить cl-meta
    private var lastArtJpeg = ByteArray(0)
    private val turnIcons = HashMap<Int, ByteArray>()
    @Volatile private var metaMusicOffLogged = false

    private val t0 = SystemClock.elapsedRealtime()

    // протокол
    @Volatile private var matchSent = false
    private var huMajor = 0
    private var huMinor = 0
    private var pendingW = 0
    private var pendingH = 0
    private var pendingFps = FPS

    /** Целевой fps транскодера: из INIT (по настройке), в Авто меняется по FRAME_RATE_CHANGE; новый транскодер стартует с него. */
    @Volatile private var targetFps = FPS
    private var videoInited = false
    private var source: VideoSource? = null
    @Volatile private var csd: ByteArray? = null
    @Volatile private var csdPending = true
    @Volatile private var paused = false

    // находки для итогового резюме
    private var huVersion = "-"
    private var sawAuthRequest = false
    private var authRandom: String? = null
    /** brand + model + sdk из отправленного MD_INFO: ключ ветки авторизации 2.0. */
    private var mdInfoKey = ""
    private var huAuthResult: String? = null
    private var touchMode = "не было тачей"
    private var touchCount = 0
    private var maxFingers = 0
    private var lastMoveFingers = 0
    private val rxByType = java.util.TreeMap<Int, Int>()
    private var huInfo: String? = null
    private var huStatistic: String? = null
    private var huFeature: String? = null
    private var encryptionNeeded: Boolean? = null
    private var requested = "-"
    private var chosen = "-"

    // видео статистика
    @Volatile private var lastVideoTx = 0L
    private var vFrames = 0L
    private var vBytes = 0L
    private var vKeys = 0L
    private var vBeats = 0L
    private var vDropped = 0L
    private var audioTx = 0L
    @Volatile private var micRxPackets = 0L
    @Volatile private var micRxBytes = 0L
    /** Когда сами слали MODULE_STATUS VR=1: ответный MODULE_CONTROL VR=1 сразу после него считаем эхом. */
    @Volatile private var lastOwnVrOnMs = 0L

    // заглушка (VideoSource с текстом) или AA-видео; переключается ядром моста
    private val videoLock = Any()
    private val srcLock = Any()
    @Volatile private var bannerText: String? = null
    @Volatile private var acceptEncoder = true
    @Volatile private var carlifeStarted = false

    fun start() {
        bridge.attach(this)
        AppLog.i("сессия: старт")
        CarLifeState.resetSession()
        CarLifeState.videoMode = mode
        CarLifeState.densityOverride = bridge.densityOverride
        AppLog.i(mode.describe(bridge.densityOverride))
        AppLog.i("сессия: транспорт=${transport.caps.kind} (${transport.label})")
        WLog.ev(WLog.LINK, "session", "transport" to transport.caps.kind)
        if (air) {
            WLog.ev(WLog.VIDEO, "policy", "profile" to bitratePolicy.profile.name, "startKbps" to bitratePolicy.startBps / 1000,
                "ceilingKbps" to bitratePolicy.ceilingBps / 1000, "mode" to mode.name, "transcode" to bridge.transcodeEnabled)
        }
        CarLifeState.status = "соединено, жду HU_PROTOCOL_VERSION"
        transport.start(::dispatch, ::logTx, ::close)
        sched.scheduleWithFixedDelay({ tick() }, 1, 1, TimeUnit.SECONDS)
        sched.scheduleWithFixedDelay({ pauseHeartbeat() }, PAUSE_HEARTBEAT_MS, PAUSE_HEARTBEAT_MS, TimeUnit.MILLISECONDS)
        sched.scheduleWithFixedDelay({ adaptBitrate() }, 1000, BITRATE_POLL_MS, TimeUnit.MILLISECONDS)
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        AppLog.i("сессия: закрытие, причина: $reason")
        bridge.onCarlifeClosed(this)
        if (air) {
            WLog.ev(WLog.VIDEO, "summary", "frames" to vFrames, "dropped" to vDropped, "overflows" to overflows, "maxQueue" to maxQueueFrames,
                "downs" to bitratePolicy.downs, "ups" to bitratePolicy.ups, "minKbps" to bitratePolicy.minBps / 1000,
                "lastKbps" to bitratePolicy.current / 1000)
        }
        WLog.ev(WLog.LINK, "session-end", "transport" to transport.caps.kind, "aliveS" to (SystemClock.elapsedRealtime() - t0) / 1000, "why" to reason)
        logSummary(reason)
        CarLifeState.status = "закрыто: $reason"
        try {
            sched.shutdownNow()
            metaExec.shutdownNow()
        } catch (_: Exception) {
        }
        try {
            source?.stop()
        } catch (e: Exception) {
            AppLog.e("остановка видео", e)
        }
        source = null
        transport.close()
        AppLog.i("сессия: закрыта")
        onClosed(reason)
    }

    // ---------------- чтение ----------------

    private fun dispatch(chan: Int, inner: ByteArray) {
        if (Ch.isCmdStyle(chan)) {
            val m = Wire.parseCmd(inner)
            if (m == null) {
                AppLog.i("RX ${Ch.name(chan)} слишком короткое inner (${inner.size} байт): ${Hex.compact(inner, 32)}")
                AppLog.detail(Hex.dump(inner))
                return
            }
            rxByType.merge(m.type, 1, Int::plus)
            val decoded = if (m.body.isEmpty()) null else PbText.render(m.body, Schemas.byType[m.type])
            val note = (if (m.truncated) " [TRUNCATED declared=${m.declaredLen}]" else "") +
                (if (m.resv != 0) " [resv=${m.resv}]" else "") +
                (if (Schemas.byType[m.type] == null && m.body.isNotEmpty()) " [схемы нет]" else "")
            val quiet = m.type == Msg.TOUCH_ACTION_MOVE || (m.type == Msg.TOUCH_ACTION && touchCount > 20 && isMove(m.body))
            AppLog.msg("RX", chan, inner.size, m.type, m.declaredLen, decoded, inner, ui = !quiet, note = note)
            if (chan == Ch.CMD) onCmd(m) else onTouch(m)
        } else {
            val m = Wire.parseData(inner)
            if (m == null) {
                AppLog.i("RX ${Ch.name(chan)} слишком короткое inner (${inner.size} байт): ${Hex.compact(inner, 32)}")
                return
            }
            rxByType.merge(m.type, 1, Int::plus)
            if (chan == Ch.VR && m.type == Msg.VR_MIC_DATA) {
                onMicData(m)
                return
            }
            val note = " time=${m.time}" + (if (m.truncated) " [TRUNCATED declared=${m.declaredLen}]" else "")
            AppLog.msg("RX", chan, inner.size, m.type, m.declaredLen, null, inner, note = note)
        }
    }

    /** Микрофон ГУ (VR 0x58001): тело = PCM16 mono 16 кГц, шифрования нет. В лог: первый пакет и каждый 200-й. */
    private fun onMicData(m: DataMsg) {
        micRxPackets++
        micRxBytes += m.data.size
        if (micRxPackets == 1L || micRxPackets % 200 == 0L) {
            AppLog.i("RX VR MIC_DATA len=${m.data.size} time=${m.time}${if (m.truncated) " [TRUNCATED declared=${m.declaredLen}]" else ""} #$micRxPackets, всего $micRxBytes Б", ui = micRxPackets == 1L)
        }
        if (m.truncated || m.data.isEmpty()) return
        bridge.onCarlifeMicData(m.data)
    }

    private fun isMove(body: ByteArray): Boolean {
        val f = PbReader.parse(body) ?: return false
        return ((PbReader.int(f, 1) ?: -1) and 0xFF) == 2
    }

    // ---------------- CMD ----------------

    private fun onCmd(m: CmdMsg) {
        when (m.type) {
            Msg.HU_PROTOCOL_VERSION -> {
                val f = PbReader.parse(m.body)
                val maj = f?.let { PbReader.int(it, 1) }
                val min = f?.let { PbReader.int(it, 2) }
                huVersion = "$maj.$min"
                if (matchSent) {
                    // Baidu: ConnectClient.geHasMatch() -> второй MATCH не шлём (CarlifeConnectHsgHandler :308-310)
                    AppLog.i("== ПОВТОРНЫЙ HU_PROTOCOL_VERSION $huVersion, второй MATCH не шлю (как Baidu)")
                    return
                }
                huMajor = maj ?: 0
                huMinor = min ?: 0
                CarLifeState.protocol = "ГУ шлёт версию $huVersion"
                AppLog.i("== ВЕРСИЯ ПРОТОКОЛА ГУ: major=$maj minor=$min")
                WLog.ev(WLog.HS, "HU_PROTOCOL_VERSION", "version" to huVersion)
                matchSent = true
                // стартовая пачка сразу, ничего не ждём от ГУ: MATCH, FOREGROUND, SCREEN_ON, MD_INFO
                val info = deviceInfo()
                mdInfoKey = HuAuth.mdInfoKey(info)
                for (c in Handshake.startBatch(cfg.screenOn, info)) sendCmd(c.type, c.body)
                val mod = Handshake.moduleStatus(huMajor)
                try {
                    sched.schedule({ sendCmd(mod.type, mod.body) }, Handshake.MODULE_STATUS_DELAY_MS, TimeUnit.MILLISECONDS)
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    // сессия уже закрывается
                }
                CarLifeState.status = "handshake: стартовая пачка отправлена"
            }
            Msg.HU_INFO -> {
                huInfo = PbText.render(m.body, Schemas.deviceInfo)
                AppLog.i("== HU_INFO: $huInfo")
                PbReader.parse(m.body)?.let {
                    WLog.ev(WLog.HS, "HU_INFO", "manufacturer" to PbReader.str(it, 13), "model" to PbReader.str(it, 14))
                    bridge.onHuInfo(PbReader.str(it, 13), PbReader.str(it, 14), PbReader.str(it, 15))
                }
                logDesay()
                // ответа нет: Baidu только сохраняет (CarlifeDeviceInfoManager.setCarDeviceInfo)
            }
            Msg.STATISTIC_INFO -> {
                huStatistic = PbText.render(m.body, Schemas.statisticsInfo)
                AppLog.i("== STATISTIC_INFO: $huStatistic")
                PbReader.parse(m.body)?.let {
                    WLog.ev(WLog.HS, "STATISTIC_INFO", "channel" to PbReader.str(it, 4))
                    bridge.onHuStatistic(PbReader.str(it, 4))
                }
                logDesay()
                for (c in Handshake.onStatisticInfo(sendFeatureConfigRequest = true)) sendCmd(c.type, c.body)
            }
            Msg.HU_AUTHEN_REQUEST -> {
                sawAuthRequest = true
                val f = PbReader.parse(m.body)
                val rnd = f?.firstOrNull { it.number == 1 && it.wire == 2 }?.data
                val rndStr = rnd?.toString(Charsets.UTF_8)
                authRandom = rnd?.let { PbText.quote(it) + " hex=" + Hex.compact(it, 64) }
                val v2 = Handshake.isAuthV2(rndStr)
                AppLog.i("== ГУ ШЛЁТ HU_AUTHEN_REQUEST randomValue=$authRandom")
                AppLog.i("== AUTHEN randomValue ${if (v2) "начинается с \"2.0;\"" else "не начинается с \"2.0;\""}")
                CarLifeState.auth = "HU_AUTHEN_REQUEST пришёл, ${if (v2) "2.0;" else "не 2.0;"}"
                // как Baidu 8.6.7 (PrivateKeyManager.encryptContentProcess): сразу по запросу подпись приватным ключом Baidu,
                // для "2.0;" md5-код от MD_INFO; MD_AUTHEN_RESULT{true} по-прежнему уходит после STATISTIC_INFO
                val enc = if (rndStr == null) null else try {
                    HuAuth.encryptValue(rndStr, mdInfoKey)
                } catch (e: Exception) {
                    AppLog.e("подпись HU_AUTHEN_REQUEST", e)
                    null
                }
                if (enc == null) {
                    AppLog.i("на HU_AUTHEN_REQUEST не отвечаю (${if (rndStr == null) "нет randomValue" else "подпись не вышла"})")
                } else {
                    if (rndStr != null && HuAuth.isV2Branch(rndStr)) AppLog.i("== AUTHEN 2.0: md5 от \"CARLIFE\" + \"$mdInfoKey\" + дата")
                    AppLog.i("== отвечаю MD_AUTHEN_RESPONSE encryptValue=$enc")
                    CarLifeState.auth += ", MD_AUTHEN_RESPONSE отправлен"
                    sendCmd(Msg.MD_AUTHEN_RESPONSE, Msgs.authenResponse(enc))
                }
            }
            Msg.HU_FEATURE_CONFIG_RESPONSE -> onFeatureConfig(m)
            Msg.CARLIFE_DATA_SUBSCRIBE -> {
                val done = Msgs.subscribeDone(m.body)
                if (done == null) {
                    AppLog.i("== CARLIFE_DATA_SUBSCRIBE не разобрался, не отвечаю (как Baidu)")
                } else {
                    AppLog.i("== CARLIFE_DATA_SUBSCRIBE moduleID=${Msgs.subscribeIds(m.body)}, отвечаю DONE")
                    sendCmd(Msg.CARLIFE_DATA_SUBSCRIBE_DONE, done)
                }
            }
            Msg.CARLIFE_DATA_SUBSCRIBE_START, Msg.CARLIFE_DATA_SUBSCRIBE_STOP -> {
                val on = m.type == Msg.CARLIFE_DATA_SUBSCRIBE_START
                val ids = Msgs.subscribeIds(m.body)
                // навигация выключена в настройках: подписку ГУ не передаём, NAVI_NEXT_TURN_INFO не уйдёт
                val pass = ids.filter { it != SUB_NAVI || cfg.metaNavi }
                AppLog.i("== SUBSCRIBE_${if (on) "START" else "STOP"} moduleID=$ids" +
                    (if (pass.size != ids.size) ", навигация выключена в настройках" else ""))
                bridge.onCarlifeSubscribe(pass, on)
            }
            Msg.VIDEO_ENCODER_JPEG -> {
                AppLog.i("== ГУ просит JPEG-режим видео: отвечаю ACK, но шлю только H.264")
                sendCmd(Msg.VIDEO_ENCODER_JPEG_ACK, null)
            }
            Msg.REQUEST_MD_THEME_STATE -> sendCmd(Msg.RESPONSE_MD_THEME_STATE, Msgs.themeState(THEME_LIGHT))
            Msg.CHANGE_MD_THEME -> AppLog.i("== CHANGE_MD_THEME ${PbText.render(m.body, Schemas.themeState)}")
            Msg.HU_AUTHEN_RESULT -> {
                val f = PbReader.parse(m.body)
                val ok = f?.let { PbReader.int(it, 1) }
                huAuthResult = "authenResult=$ok"
                AppLog.i("== HU_AUTHEN_RESULT: $ok")
                CarLifeState.auth += " -> HU_AUTHEN_RESULT=$ok"
            }
            Msg.VIDEO_ENCODER_INIT -> onVideoInit(m)
            Msg.VIDEO_ENCODER_START -> onVideoStart()
            Msg.VIDEO_ENCODER_PAUSE -> {
                paused = true
                if (bridge.huVw20082120) { // первый heartbeat сразу; Desay - как раньше, через 2 с
                    lastVideoTx = 0L
                    pauseHeartbeat()
                }
                bridge.onCarlifePause()
                AppLog.i("видео: PAUSE, кадры не шлю")
            }
            Msg.VIDEO_ENCODER_RESET -> {
                AppLog.i("видео: RESET, прошу ключевой кадр")
                source?.requestKeyFrame()
                bridge.onCarlifeKeyFrameRequest()
            }
            Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE -> {
                // как VideoMsgHandler.changeFrameRate: вне 3..30 молча игнор, иначе DONE с тем же fps
                val fps = PbReader.parse(m.body)?.let { PbReader.int(it, 1) } ?: -1
                if (Handshake.frameRateChangeAllowed(fps)) {
                    onFrameRateChange(fps)
                    sendCmd(Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE, Msgs.frameRate(fps))
                } else {
                    AppLog.i("видео: FRAME_RATE_CHANGE fps=$fps вне 3..30, игнор (как Baidu)")
                }
            }
            Msg.MODULE_CONTROL -> {
                // ГУ спрашивает состояние модуля (moduleID=3 MUSIC; Desay G6S шлёт пачку status=1 после хендшейка и
                // при возврате на CarLife-музыку): отвечаем по факту, играет ли MEDIA, или по решению моста
                val f = PbReader.parse(m.body)
                val module = f?.let { PbReader.int(it, 1) }
                val status = f?.let { PbReader.int(it, 2) }
                if (module == CarlifeAudio.MODULE_MUSIC && AaBridgeCore.MODULE_MUSIC_ENABLED) {
                    val on = bridge.onCarlifeMusicControl(status)
                    AppLog.i("== MODULE_CONTROL MUSIC status=$status, отвечаю MODULE_STATUS MUSIC=${if (on) 1 else 0}", ui = false)
                    sendModuleStatus(CarlifeAudio.MODULE_MUSIC, if (on) 1 else 0)
                } else if (module == MODULE_VR) {
                    // Кнопка голоса на ГУ/руле: Baidu на VR=1 открывает голосовой ввод (openVrAndDialog), 0/3 - закрывает
                    val sinceOwn = SystemClock.elapsedRealtime() - lastOwnVrOnMs
                    when {
                        status != 1 -> AppLog.i("== MODULE_CONTROL VR status=$status: игнор")
                        sinceOwn < VR_ECHO_MS -> AppLog.i("== MODULE_CONTROL VR=1 через $sinceOwn мс после нашего VR=1: эхо, игнор")
                        else -> bridge.onCarlifeVoiceKey()
                    }
                }
            }
            Msg.BT_HFP_CONNECTION -> {
                // поле 1: 2 = телефон подключился к ГУ по BT, 0 = отключился; после подключения AA сам запускает автоплей
                val state = PbReader.parse(m.body)?.let { PbReader.int(it, 1) }
                bridge.onHuBtHfpConnection(state)
            }
            Msg.MD_EXIT -> close("ГУ прислало MD_EXIT")
            else -> Unit // всё остальное только в логе
        }
    }

    /** Авто: целевой fps транскодера = max(fps ГУ, 10) на лету; фиксированная настройка: только ответ DONE. Passthrough не трогаем (выброс P-кадров ломает поток). */
    private fun onFrameRateChange(fps: Int) {
        if (!bridge.transcodeEnabled) {
            AppLog.i("видео: FRAME_RATE_CHANGE fps=$fps, passthrough: частота не регулируется, энкодер AA не трогаю")
            return
        }
        val fixed = cfg.fpsSetting.fixed
        if (fixed != null) {
            AppLog.i("видео: FRAME_RATE_CHANGE fps=$fps, настройка фиксирована на $fixed: отвечаю DONE, частоту не меняю")
            return
        }
        val applied = maxOf(fps, Handshake.MIN_APPLIED_FPS)
        val old = targetFps
        targetFps = applied
        val live = bridge.setTargetFps(applied)
        AppLog.i("видео: FRAME_RATE_CHANGE fps=$fps, целевой fps $old -> $applied${if (live) "" else " (транскодер ещё не создан, применится при старте)"}")
    }

    private fun onVideoInit(m: CmdMsg) {
        val f = PbReader.parse(m.body)
        val rw = f?.let { PbReader.int(it, 1) } ?: 0
        val rh = f?.let { PbReader.int(it, 2) } ?: 0
        val rf = f?.let { PbReader.int(it, 3) } ?: 0
        requested = "${rw}x$rh fps=$rf"
        AppLog.i("== ГУ ПРОСИТ РАЗРЕШЕНИЕ: ${rw}x$rh fps=$rf")
        WLog.ev(WLog.HS, "VIDEO_ENCODER_INIT", "size" to "${rw}x$rh", "fps" to rf)
        if (videoInited && pendingW > 0) {
            // как VideoMsgHandler (:84-88): повторный INIT -> только INIT_DONE с прежним размером
            AppLog.i("== повторный INIT, отвечаю INIT_DONE ${pendingW}x$pendingH fps=$pendingFps")
            sendCmd(Msg.VIDEO_ENCODER_INIT_DONE, Msgs.videoEncoderInfo(pendingW, pendingH, pendingFps))
            return
        }
        // размер задаёт видео-режим (по умолчанию 1280x720 fps 30: ГУ Honda принимает и показывает целиком, проверено в машине)
        val (w, h, why) = Triple(mode.outW, mode.outH, "мост: режим ${mode.name}")
        val fps = Handshake.chooseFps(cfg.fpsSetting, rf)
        pendingW = w
        pendingH = h
        pendingFps = fps
        targetFps = fps
        videoInited = true
        chosen = "${w}x$h ($why)"
        AppLog.i("== ОТВЕЧАЮ INIT_DONE ${w}x$h fps=$fps ($why; ГУ просит fps=$rf, настройка ${cfg.fpsSetting.name}${if (bridge.transcodeEnabled) "" else ", passthrough: частота не регулируется"})")
        CarLifeState.video = "ГУ просит $requested, кодирую ${w}x$h"
        sendCmd(Msg.VIDEO_ENCODER_INIT_DONE, Msgs.videoEncoderInfo(w, h, fps))
        sendCmd(Msg.FOREGROUND, null)
        // VW Tharu XR 2026 (CarLife 2.1) после INIT_DONE START не шлёт, ждёт видео и через ~5.5 с рвёт USB
        try {
            sched.schedule({
                if (!closed.get() && !carlifeStarted) {
                    AppLog.i("== START не пришёл за ${Handshake.START_FALLBACK_MS} мс после INIT_DONE: стартую видео сам")
                    onVideoStart()
                }
            }, Handshake.START_FALLBACK_MS, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // сессия уже закрывается
        }
    }

    private var desayLogged = false

    private fun logDesay() {
        if (!bridge.huDesayG6S || desayLogged) return
        desayLogged = true
        AppLog.i("== ГУ Desay G6S: маршрутный NAVI=1 выключен")
    }

    private fun onFeatureConfig(m: CmdMsg) {
        val fc = FeatureConfig.parse(m.body)
        if (fc == null) {
            AppLog.i("== FEATURE_CONFIG не разобрался: ${Hex.compact(m.body, 64)}")
            return
        }
        huFeature = fc.items.joinToString(" ") { "${it.first}=${it.second}" }
        bridge.onHuFeatureConfig(fc.value("AUDIO_TRANSMISSION_MODE"))
        AppLog.i("== HU FEATURE_CONFIG (${fc.items.size} ключей), btAudio=${fc.huBtAudioSupport} btName=${fc.huBtName} btMAC=${fc.huBtMac}")
        for ((k, v) in fc.items) AppLog.i("   feature $k = $v")
        val enc = fc.value(FEATURE_CONTENT_ENCRYPTION)
        encryptionNeeded = enc?.let { it == 1 }
        AppLog.i("== CONTENT_ENCRYPTION=${enc ?: "нет в списке"}, CONNECT_TYPE=${fc.value("CONNECT_TYPE") ?: "нет"}")
        WLog.ev(WLog.HS, "FEATURE_CONFIG", "keys" to fc.items.size, "CONNECT_TYPE" to fc.value("CONNECT_TYPE"), "CONTENT_ENCRYPTION" to enc)
        if (enc == 1) {
            AppLog.i("== !!! ГУ ТРЕБУЕТ ШИФРОВАНИЕ КАНАЛА (CONTENT_ENCRYPTION=1): нужна цепочка RSA/AES (0x1006A..0x1006E), пока не реализована")
        }
    }

    /** START: гейт AA сбрасывается, заглушка (если активна) стартует с SPS+PPS+IDR. */
    private fun onVideoStart() {
        paused = false
        if (pendingW <= 0) {
            pendingW = mode.outW
            pendingH = mode.outH
            chosen = "${mode.outW}x${mode.outH} (START без INIT, мост, режим ${mode.name})"
        }
        carlifeStarted = true
        synchronized(videoLock) {
            csdPending = true
            lastVideoTx = SystemClock.elapsedRealtime()
        }
        CarLifeState.status = "видео: мост"
        bridge.onCarlifeStart()
        if (bannerText != null) startOrUpdatePlaceholder()
    }

    private fun startOrUpdatePlaceholder() {
        synchronized(srcLock) {
            val text = bannerText ?: return
            val cur = source
            if (cur != null) {
                cur.banner = text
                cur.requestKeyFrame()
                return
            }
            val s = VideoSource(pendingW, pendingH, pendingFps, if (air) bitratePolicy.current else mode.bitrate, this)
            s.banner = text
            try {
                s.start()
                source = s
                AppLog.i("BRIDGE заглушка: энкодер ${s.encoderName} ${pendingW}x$pendingH, текст '$text'")
            } catch (e: Exception) {
                AppLog.e("не удалось запустить энкодер заглушки", e)
                s.stop()
            }
        }
    }

    override fun sendVideo(payload: ByteArray): Boolean {
        if (closed.get() || paused) return false
        synchronized(videoLock) {
            if (videoOverflow()) return false
            lastVideoTx = SystemClock.elapsedRealtime()
            send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_DATA, unixSeconds(), withFixedSps(payload)))
            return true
        }
    }

    override fun sendVideoTakeover(payload: ByteArray): Boolean {
        if (closed.get() || paused) return false
        synchronized(videoLock) {
            if (videoOverflow()) return false
            // под тем же замком, что и onFrame заглушки: после AA-IDR ни один её кадр уже не уйдёт
            acceptEncoder = false
            lastVideoTx = SystemClock.elapsedRealtime()
            send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_DATA, unixSeconds(), withFixedSps(payload)))
            return true
        }
    }

    /** Весь H.264 в ГУ (мост, перехват, заглушка) идёт через AnnexB.fixSps: подпись SPS Exynos ГУ не принимает. */
    private fun withFixedSps(payload: ByteArray): ByteArray {
        val out = AnnexB.fixSps(payload, pendingW, pendingH)
        if (out !== payload && !spsFixLogged) {
            spsFixLogged = true
            val i = payload.indices.firstOrNull { payload[it] != out[it] } ?: return out
            val from = maxOf(0, i - 4)
            AppLog.i("видео: SPS в ГУ исправлен под ${pendingW}x$pendingH: ${Hex.compact(payload.copyOfRange(from, i + 4), 8)}" +
                " -> ${Hex.compact(out.copyOfRange(from, i + 4), 8)}")
        }
        return out
    }

    /**
     * Видео-очередь переполнена: уже стоящих кадров больше 30 или их байт больше ~500 мс слива (пока слив не измерен - 512 КБ).
     * Проверяется до добавления нового кадра: в пустую очередь IDR принимается всегда. Отказ = вызывающий выбрасывает кадр
     * и ждёт IDR (мост: outGotIdr=false; заглушка: csdPending=true), P после потерянного кадра слать нельзя.
     */
    private fun videoOverflow(): Boolean {
        val over = VideoQueueLimit.overflow(transport.queuedVideoFrames, transport.queuedVideoBytes, transport.drainBytesPerSec)
        if (over) {
            lastOverflowMs = SystemClock.elapsedRealtime()
            overflows++
        }
        return over
    }

    override fun videoStats(): LinkVideoStats {
        val bytes = transport.queuedVideoBytes
        val rate = transport.drainBytesPerSec
        return LinkVideoStats(rate, VideoQueueLimit.backlogMs(bytes, rate), transport.queuedVideoFrames, bytes)
    }

    /**
     * Адаптация битрейта энкодера (раз в 100 мс): бэклог и слив -> BitratePolicy -> MediaCodec.setParameters на лету.
     * Только перекодирование: в passthrough H.264 AA идёт как есть, битрейт не менялся бы.
     */
    private fun adaptBitrate() {
        try {
            if (closed.get() || !carlifeStarted) return
            if (!bridge.transcodeEnabled) {
                if (!passthroughBitrateLogged) {
                    passthroughBitrateLogged = true
                    AppLog.i("видео: passthrough, адаптация битрейта недоступна (H.264 AA идёт как есть)")
                }
                return
            }
            // на воздухе политика ведёт и заглушку (CBR на потолке, пока AA стартует); на USB заглушку не трогаем
            val placeholder = if (air && !bridge.isTranscoding) source else null
            if (!bridge.isTranscoding && placeholder == null) return
            val now = SystemClock.elapsedRealtime()
            val rate = transport.drainBytesPerSec
            val qf = transport.queuedVideoFrames
            if (qf > maxQueueFrames) maxQueueFrames = qf
            val real = VideoQueueLimit.backlogMs(transport.queuedVideoBytes, rate)
            val sinceOverflow = lastOverflowMs.let { if (it == NEVER_MS) -1L else now - it }
            applyVwCeiling()
            val old = bitratePolicy.current
            val bps = bitratePolicy.update(now, VideoQueueLimit.policyBacklogMs(real, sinceOverflow), rate) ?: return
            if (placeholder?.setBitrate(bps) ?: bridge.setEncoderBitrate(bps)) {
                val who = if (placeholder != null) "заглушки" else "энкодера"
                AppLog.i("видео: битрейт $who ${old / 1000} -> ${bps / 1000} кбит/с (${bitratePolicy.lastReason}; потолок ${bitratePolicy.ceilingBps / 1000})")
                if (air) WLog.ev(WLog.VIDEO, "bitrate", "fromKbps" to old / 1000, "toKbps" to bps / 1000,
                    "src" to if (placeholder != null) "placeholder" else "aa", "why" to bitratePolicy.lastReason)
            }
        } catch (e: Exception) {
            AppLog.e("адаптация битрейта", e)
        }
    }

    /**
     * VW ID (h196): ГУ рвёт USB EIO на IDR > ~600 КиБ. Потолок перекодирования 2 Мбит/с под флагом ГУ (флаг мог прийти
     * после старта энкодера - битрейт снижается на лету). С 4 Мбит/с сессия рвалась в первые 10-15 с, пока страховка
     * опускала потолок; за поездку он сам сходился к 1,7 Мбит/с. Остальные ГУ не затрагиваются.
     */
    private fun applyVwCeiling() {
        if (!bridge.huVw20082138) return
        val old = bitratePolicy.current
        if (!bitratePolicy.lowerCeiling(VW_20082138_CEILING_BPS)) return
        AppLog.i("VW 20082138: потолок битрейта ${bitratePolicy.ceilingBps / 1000} кбит/с (лимит размера IDR)")
        if (bitratePolicy.current != old) bridge.setEncoderBitrate(bitratePolicy.current)
    }

    /** Страховка VW ID: IDR > 450 КБ - потолок ×0,75 (не ниже 1,5 Мбит/с), не чаще раза в секунду; кадр уже ушёл. */
    override fun onEncodedKeyFrame(size: Int) {
        if (size <= VW_20082138_IDR_LIMIT) return
        val now = SystemClock.elapsedRealtime()
        if (vwIdrCutMs != NEVER_MS && now - vwIdrCutMs < VW_20082138_CUT_INTERVAL_MS) return
        applyVwCeiling()
        val old = bitratePolicy.current
        if (!bitratePolicy.lowerCeiling(maxOf(VW_20082138_MIN_BPS, (bitratePolicy.ceilingBps * 0.75).toInt()))) return
        vwIdrCutMs = now
        AppLog.i("VW 20082138: IDR $size Б > $VW_20082138_IDR_LIMIT, потолок битрейта -> ${bitratePolicy.ceilingBps}")
        if (bitratePolicy.current != old) bridge.setEncoderBitrate(bitratePolicy.current)
    }

    /** Аудио моста: канал MEDIA/TTS, заголовок 12 байт; time = millis & 0xFFFFFFFF в DATA, 0 в INIT/STOP (как Baidu). */
    override fun sendAudio(channel: Int, type: Int, payload: ByteArray): Boolean {
        if (closed.get()) return false
        if (payload.isNotEmpty() && transport.queueSize(channel) > MAX_AUDIO_QUEUE) return false   // звук не должен душить видео в очереди
        val isData = type == CarlifeAudio.MEDIA_DATA || type == CarlifeAudio.TTS_DATA
        val time = if (isData) (System.currentTimeMillis() and 0xFFFFFFFFL).toInt() else 0
        send(channel, Wire.dataInner(type, time, payload))
        return true
    }

    /** MODULE_STATUS об одном модуле (NAVI перед TTS, MUSIC при MEDIA INIT/PAUSE), как Baidu ModuleStatusManage. */
    override fun sendModuleStatus(module: Int, status: Int): Boolean {
        if (closed.get()) return false
        sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(module, status))
        return true
    }

    /** CMD GO_TO_DESKTOP (MD -> ГУ, пустое тело): ГУ показывает свой рабочий стол. */
    override fun goToDesktop() {
        if (closed.get()) return
        AppLog.i("-> ГУ: GO_TO_DESKTOP")
        sendCmd(Msg.GO_TO_DESKTOP, null)
    }

    /**
     * Запись микрофона на ГУ как у Baidu MD: старт = MIC_RECORD_RECOG_START (VrManager.sendStartRecogMsgToVehicle)
     * и MODULE_STATUS VR=1 (prepareShowVr); стоп = MIC_RECORD_END (sendRecordEndMsgToVehicle) и VR=0 (prepareCloseVr).
     */
    override fun requestMicRecord(start: Boolean): Boolean {
        if (closed.get()) return false
        if (start) {
            micRxPackets = 0
            micRxBytes = 0
            AppLog.i("MIC -> ГУ: MIC_RECORD_RECOG_START${if (cfg.micVrStatus) " + MODULE_STATUS VR=1" else ""}")
            sendCmd(Msg.MIC_RECORD_RECOG_START, null)
            if (cfg.micVrStatus) {
                lastOwnVrOnMs = SystemClock.elapsedRealtime()
                sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(MODULE_VR, 1))
            }
        } else {
            AppLog.i("MIC -> ГУ: MIC_RECORD_END${if (cfg.micVrStatus) " + MODULE_STATUS VR=0" else ""}; с ГУ принято $micRxPackets пакетов, $micRxBytes Б")
            sendCmd(Msg.MIC_RECORD_END, null)
            if (cfg.micVrStatus) sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(MODULE_VR, 0))
        }
        return true
    }

    // ---------------- метаданные AA -> CarLife ----------------
    // Все три сообщения идут через одну нить metaExec: порядок MEDIA_INFO -> PROGRESS сохраняется, а перекодирование
    // обложки и рисование стрелки не держат монитор моста (зовут из нити AA-сессии под ним).

    private fun metaSubmit(what: String, task: () -> Unit): Boolean {
        if (closed.get()) return false
        return try {
            metaExec.execute {
                try {
                    if (!closed.get()) task()
                } catch (e: Throwable) {
                    AppLog.e("метаданные: $what", e)
                }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            false
        }
    }

    override fun sendMediaInfo(info: CarlifeMediaInfo): Boolean {
        if (!cfg.metaMusic) {
            if (!metaMusicOffLogged) AppLog.i("метаданные: музыка выключена в настройках, MEDIA_INFO не шлю")
            metaMusicOffLogged = true
            return false
        }
        return metaSubmit("MEDIA_INFO") {
            val art = info.art
            val jpeg = when {
                art == null || art.isEmpty() -> ByteArray(0)
                art.contentEquals(lastArtSrc) -> lastArtJpeg
                else -> {
                    val t = SystemClock.elapsedRealtime()
                    val j = MetaImages.albumJpeg(art) ?: ByteArray(0)
                    AppLog.i("метаданные: обложка ${PbText.blob(art)} -> ${PbText.blob(j)} за ${SystemClock.elapsedRealtime() - t} мс", ui = false)
                    lastArtSrc = art
                    lastArtJpeg = j
                    j
                }
            }
            sendCmd(Msg.MEDIA_INFO, Msgs.mediaInfo(info, jpeg))
        }
    }

    override fun sendMediaProgress(progressBar: Int): Boolean {
        if (!cfg.metaMusic) return false
        return metaSubmit("MEDIA_PROGRESS_BAR") { sendCmd(Msg.MEDIA_PROGRESS_BAR, Msgs.mediaProgress(progressBar)) }
    }

    override fun sendNaviTurn(info: NaviTurnInfo): Boolean {
        if (!cfg.metaNavi) return false
        return metaSubmit("NAVI_NEXT_TURN_INFO") {
            // конец маршрута (action 3) - иконка пустая, как Baidu onNaviStateChange(false)
            val icon = if (!cfg.naviField6.icon || info.action == 3) ByteArray(0) else turnIcons.getOrPut(info.nextTurn) {
                MetaImages.turnIcon(info.nextTurn).also {
                    AppLog.i("метаданные: стрелка nextTurn=${info.nextTurn} ${PbText.blob(it)}", ui = false)
                }
            }
            sendCmd(Msg.NAVI_NEXT_TURN_INFO, Msgs.naviNextTurn(info, cfg.naviField6, icon))
        }
    }

    /** Транскодер моста: декодер AA (кадр режима) -> кроп -> Surface энкодера (выход режима). null, если MediaCodec не поднялся. */
    override fun createTranscoder(out: TranscodeOutput): VideoTranscoder? = try {
        applyVwCeiling()
        AaTranscoder(
            mode.outW, mode.outH, targetFps, bitratePolicy.current, out, { AppLog.i(it, false) },
            srcWidth = mode.aaW, srcHeight = mode.aaH, crop = mode.crop,
        ).also { it.start() }
    } catch (e: Exception) {
        AppLog.e("BRIDGE транскодер не создан", e)
        null
    }

    override fun showPlaceholder(text: String) {
        bannerText = text
        synchronized(videoLock) {
            if (!acceptEncoder) csdPending = true
            acceptEncoder = true
        }
        if (carlifeStarted && !closed.get()) {
            synchronized(videoLock) { csdPending = true }
            startOrUpdatePlaceholder()
        }
    }

    override fun placeholderSnapshot(): android.graphics.Bitmap? = try {
        synchronized(srcLock) { source }?.snapshot()
    } catch (e: Exception) {
        AppLog.e("снимок заглушки для перехода", e)
        null
    }

    override fun hidePlaceholder() {
        synchronized(videoLock) { acceptEncoder = false }
        bannerText = null
        val old = synchronized(srcLock) {
            val o = source
            source = null
            o
        }
        if (old != null) Thread({ old.stop() }, "cl-placeholder-stop").start()
    }

    // ---------------- TOUCH ----------------

    private fun onTouch(m: CmdMsg) {
        if (m.type == Msg.TOUCH_CAR_HARD_KEY_CODE) {
            val code = PbReader.parse(m.body)?.let { PbReader.int(it, 1) }
            AppLog.i("== HARD KEY keycode=$code")
            if (code != null) bridge.onCarlifeHardKey(code)
            return
        }
        val t = CarlifeTouch.parse(m.type, m.body) ?: return
        setTouchMode(
            if (m.type == Msg.TOUCH_ACTION) "TOUCH_ACTION (прозрачный, TRANSPARENT_SEND_TOUCH_EVENT=true)"
            else "жестовый (отдельные коды 0x68002..0x6800D)"
        )
        touchCount++
        val n = t.fingers
        if (n > 1 && maxFingers < 2) AppLog.i("== МУЛЬТИТАЧ: ГУ шлёт второй палец (action=${t.action} тело=${m.body.size} байт)")
        maxFingers = maxOf(maxFingers, n)
        // для чек-листа щипка: сырой action, пальцы, длина тела; MOVE только при смене числа пальцев, иначе лог раздует
        if (t.masked != 2 || n != lastMoveFingers) {
            AppLog.i("TOUCH action=${t.action} пальцев=$n тело=${m.body.size} байт", false)
            lastMoveFingers = if (t.masked == 2) n else 0
        }
        if (t.masked in TOUCH_TO_AA) bridge.onCarlifeTouch(t.action, t.x, t.y, t.px, t.py)
        CarLifeState.touch = "$touchMode; событий=$touchCount; пальцев макс=$maxFingers; последний act=${t.action} x=${t.x} y=${t.y}" +
            (if (n > 1) " px=${t.px} py=${t.py}" else "")
    }

    private fun setTouchMode(mode: String) {
        if (touchMode != mode) {
            touchMode = mode
            AppLog.i("== РЕЖИМ ТАЧА: $mode")
        }
    }

    // ---------------- запись ----------------

    private fun sendCmd(type: Int, body: ByteArray?) = send(Ch.CMD, Wire.cmdInner(type, body))

    private fun send(chan: Int, inner: ByteArray) {
        if (closed.get()) return
        transport.send(chan, inner)
    }

    /** После записи в транспорт (нить писателя). */
    private fun logTx(chan: Int, inner: ByteArray) {
        if (Ch.isCmdStyle(chan)) {
            val m = Wire.parseCmd(inner) ?: return
            val decoded = if (m.body.isEmpty()) null else PbText.render(m.body, Schemas.byType[m.type])
            // прогресс трека - раз в секунду, обновление дистанции (action 2) - тоже: в файл, не в UI
            val quiet = m.type == Msg.MEDIA_PROGRESS_BAR ||
                (m.type == Msg.NAVI_NEXT_TURN_INFO && PbReader.parse(m.body)?.let { PbReader.int(it, 1) } == 2)
            AppLog.msg("TX", chan, inner.size, m.type, m.declaredLen, decoded, inner, ui = !quiet)
        } else {
            val m = Wire.parseData(inner) ?: return
            if (chan != Ch.VIDEO) { // аудио моста: INIT/STOP всегда, DATA - первые и каждый 200-й
                audioTx++
                if (m.data.size <= 16 || audioTx <= 3 || audioTx % 200 == 0L) {
                    AppLog.i(
                        "TX ${Ch.name(chan)} ${Msg.hex(m.type)} ${Msg.name(m.type)} len=${m.data.size} time=${m.time} #$audioTx" +
                            (if (m.data.size <= 16) " ${Hex.compact(m.data, 16)}" else ""), ui = false
                    )
                }
                return
            }
            if (m.data.isEmpty()) { // keepalive на PAUSE: пустой VIDEO_DATA (Desay) или VIDEO_HEARTBEAT (VW 20082120)
                vBeats++
                if (vBeats <= 3) AppLog.msg("TX", chan, inner.size, m.type, m.declaredLen, null, inner)
                return
            }
            vFrames++
            vBytes += m.data.size
            if (vFrames == 1L) WLog.ev(WLog.HS, "first-video", "bytes" to m.data.size)
            val key = AnnexB.nalTypes(m.data).contains(5)
            if (key) vKeys++
            val nals = AnnexB.nalTypes(m.data)
            if (vFrames <= 5 || key) {
                AppLog.msg("TX", chan, inner.size, m.type, m.declaredLen, "nal=$nals", inner, ui = vFrames <= 5)
            }
        }
    }

    // ---------------- видео из энкодера ----------------

    override fun onConfig(csd: ByteArray) {
        this.csd = csd
        val types = AnnexB.nalTypes(csd)
        var prof = ""
        if (csd.size > 8 && types.firstOrNull() == 7) {
            // [00 00 00 01][SPS header][profile_idc][constraints][level_idc]
            prof = " profile_idc=${csd[5].toInt() and 0xFF} constraints=0x%02X level_idc=${csd[7].toInt() and 0xFF}".format(csd[6].toInt() and 0xFF)
        }
        AppLog.i("энкодер: codec config ${csd.size} байт nal=$types$prof")
        AppLog.detail(Hex.dump(csd, 128))
    }

    override fun onFrame(data: ByteArray, key: Boolean) {
        if (closed.get() || paused) return
        synchronized(videoLock) {
            if (!acceptEncoder) return   // заглушка скрыта (идёт AA-видео): остаток кадров кодера не нужен
            onEncoderFrame(data, key)
        }
    }

    private fun onEncoderFrame(data: ByteArray, key: Boolean) {
        if (!key && csdPending) {
            vDropped++
            return // до первого IDR ничего не шлём (первый пакет обязан начинаться с SPS)
        }
        if (videoOverflow()) {
            // кадр потерян: P после него слать нельзя, снова ждём IDR (csdPending), запрос IDR не чаще раза в 1.5 с
            vDropped++
            csdPending = true
            if (placeholderIdr.tryRequest(SystemClock.elapsedRealtime())) source?.requestKeyFrame()
            return
        }
        var payload = data
        if (key) {
            val c = csd
            if (csdPending && c != null && !AnnexB.startsWithSps(data)) payload = AnnexB.glue(c, data)
            csdPending = false
        }
        lastVideoTx = SystemClock.elapsedRealtime()
        send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_DATA, unixSeconds(), withFixedSps(payload)))
    }

    override fun onError(msg: String, t: Throwable?) {
        AppLog.e(msg, t)
        CarLifeState.status = "ОШИБКА: $msg: ${t?.message}"
    }

    // ---------------- таймер ----------------

    private var lastStatsAt = 0L
    private var lastStatsFrames = 0L

    // Пустых keepalive при идущем видео НЕТ: кадры обеспечивает энкодер (repeat-previous-frame-after 100 мс).
    // На PAUSE (ГУ в своём меню/на своём источнике) канал иначе молчит:
    // - Desay G6S (Mazda CX-5) через ~22 с тишины рвёт USB (EIO, лог Ивана 2026-10-12): пустой VIDEO_DATA 0x20001,
    //   как Baidu sendEmptyPacketBackground, раз в 2 с по USB и раз в ~1 с на воздухе (ГУ на Wi-Fi рвут видео-сокет быстрее);
    // - VW channel 20082120 рвёт канал через 3,6-4 с после PAUSE даже при 0x20001 раз в 1-2 с (h93, логи 2026-10-15 USB
    //   и 2026-10-16 Wi-Fi), реконнект с FOREGROUND выдёргивал юзера из меню ГУ в CarLife: VIDEO_HEARTBEAT 0x20002 сразу
    //   и раз в 100 мс, как Baidu Recorder.sendEmptyPacketBackground (~50 мс).
    private fun pauseHeartbeat() {
        if (closed.get() || !paused || !PAUSE_KEEPALIVE_ENABLED) return
        val (type, period) =
            if (bridge.huVw20082120) Msg.VIDEO_HEARTBEAT to PAUSE_HEARTBEAT_MS else Msg.VIDEO_DATA to pauseKeepaliveMs
        val now = SystemClock.elapsedRealtime()
        if (now - lastVideoTx < period) return
        if (vBeats == 0L) AppLog.i("видео: PAUSE, шлю пустой ${Msg.name(type)} раз в $period мс, чтобы ГУ не рвало USB")
        lastVideoTx = now
        send(Ch.VIDEO, Wire.dataInner(type, unixSeconds(), ByteArray(0)))
    }

    private fun tick() {
        if (closed.get()) return
        val now = SystemClock.elapsedRealtime()
        if (transport.rxBytes == 0L && now - t0 > SILENCE_MS && (now - t0) % 10_000 < 1000) {
            AppLog.i("ГУ не прислало ни одного байта за ${(now - t0) / 1000} с (ждём HU_PROTOCOL_VERSION)")
            CarLifeState.status = "ГУ молчит, байт от него нет"
        }
        if (now - lastStatsAt >= 5000 && (vFrames > 0 || vBeats > 0)) {
            AppLog.i("видео: отправлено кадров=$vFrames (ключевых=$vKeys) байт=$vBytes heartbeat=$vBeats сброшено=$vDropped очередь=${transport.queueSize(Ch.VIDEO)} (+${vFrames - lastStatsFrames} за ${(now - lastStatsAt) / 1000}с)")
            lastStatsAt = now
            lastStatsFrames = vFrames
            CarLifeState.video = "$chosen; кадров=$vFrames hb=$vBeats"
        }
    }

    private fun logSummary(reason: String) {
        val sb = StringBuilder("== РЕЗЮМЕ СЕССИИ (${(SystemClock.elapsedRealtime() - t0) / 1000} с, причина: $reason)\n")
        sb.append("  принято байт: ${transport.rxBytes}\n")
        sb.append("  версия протокола ГУ: $huVersion\n")
        sb.append("  HU_AUTHEN_REQUEST: ${if (sawAuthRequest) "ПРИШЁЛ randomValue=$authRandom" else "не приходил"}\n")
        sb.append("  HU_AUTHEN_RESULT: ${huAuthResult ?: "не приходил"}\n")
        sb.append("  запрошенное разрешение: $requested; ответ: $chosen\n")
        sb.append("  тач: $touchMode, событий=$touchCount\n")
        sb.append("  видео: кадров=$vFrames ключевых=$vKeys heartbeat=$vBeats сброшено=$vDropped\n")
        sb.append("  HU_INFO: ${huInfo ?: "не приходил"}\n")
        sb.append("  принятые типы: ")
        for ((t, c) in rxByType) sb.append("${Msg.hex(t)} ${Msg.name(t)} x$c; ")
        AppLog.i(sb.toString().trimEnd(), ui = false)
        AppLog.i("резюме записано в лог (сессия ${reason})")
    }

    private fun deviceInfo(): DeviceInfo {
        // SDK_INT >= 33: как в 8.6.7 врём "29"/29
        val (sdk, sdkInt) = DeviceInfo.reportedSdk(Build.VERSION.SDK_INT)
        return DeviceInfo(
            os = "Android",
            board = Build.BOARD,
            bootloader = Build.BOOTLOADER,
            brand = Build.BRAND,
            cpuAbi = Build.SUPPORTED_ABIS.getOrElse(0) { "" },
            cpuAbi2 = Build.SUPPORTED_ABIS.getOrElse(1) { "" },
            device = Build.DEVICE,
            display = Build.DISPLAY,
            fingerprint = Build.FINGERPRINT,
            hardware = Build.HARDWARE,
            host = Build.HOST,
            cid = Build.ID,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            product = Build.PRODUCT,
            serial = "unknown",
            codename = Build.VERSION.CODENAME,
            incremental = Build.VERSION.INCREMENTAL,
            release = Build.VERSION.RELEASE,
            sdk = sdk,
            sdkInt = sdkInt,
            btaddress = "unknown", // BT-адрес нам недоступен; Baidu шлёт "unknown", если не получил
            carlifeVersion = Handshake.MD_VERSION,
            carlifeType = Handshake.MD_TYPE_CARLIFE_APP,
        )
    }

    companion object {
        const val FPS = Handshake.DEFAULT_FPS

        /** action&255 тача, которые мост переводит в AA: DOWN, UP, MOVE, POINTER_DOWN, POINTER_UP. */
        private val TOUCH_TO_AA = setOf(0, 1, 2, 5, 6)
        private const val MAX_AUDIO_QUEUE = 180       // звук не принимается, если в общей очереди больше пакетов
        private const val BITRATE_POLL_MS = 100L
        private const val VW_20082138_CEILING_BPS = 2_000_000
        private const val VW_20082138_MIN_BPS = 1_500_000
        private const val VW_20082138_IDR_LIMIT = 450_000
        private const val VW_20082138_CUT_INTERVAL_MS = 1000L
        private const val NEVER_MS = Long.MIN_VALUE
        private const val SILENCE_MS = 10_000L
        private const val PAUSE_KEEPALIVE_MS = 2_000L  // Desay G6S: пустой VIDEO_DATA
        private const val PAUSE_HEARTBEAT_MS = 100L    // VW 20082120: пустой VIDEO_HEARTBEAT

        /** На воздухе раз в ~1 с: ГУ на Wi-Fi рвут видео-сокет быстрее (план 5.2 п.4). */
        private const val PAUSE_KEEPALIVE_AIR_MS = 900L

        /** Keepalive на PAUSE: пустой VIDEO_DATA, у VW 20082120 VIDEO_HEARTBEAT. */
        @Volatile @JvmStatic var PAUSE_KEEPALIVE_ENABLED = true
        private const val FEATURE_CONTENT_ENCRYPTION = "CONTENT_ENCRYPTION"

        /** moduleID подписки CARLIFE_DATA_SUBSCRIBE на навигацию (CarlifeDataSubscribe: 0 навигация, 2 музыка). */
        private const val SUB_NAVI = 0

        /** moduleID голосового помощника в MODULE_STATUS (ModuleStatusManage: 4 = VR). */
        private const val MODULE_VR = 4
        private const val VR_ECHO_MS = 1500L

        /** SkinDisplayModeManager.getDisplayModeInt: 0 светлая, 1 тёмная, 2 авто. */
        private const val THEME_LIGHT = 0

        /** Поле time видеопакета: unix-секунды (Recorder.sendVideoData :930-962). */
        private fun unixSeconds() = (System.currentTimeMillis() / 1000).toInt()
    }
}
