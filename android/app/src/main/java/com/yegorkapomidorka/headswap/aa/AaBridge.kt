package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.AnnexB
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Что мост требует от CarLife-стороны (реализует CarLifeSession). */
interface CarlifeLink {
    /** Одно видео-сообщение CarLife (0x20001, Annex-B). false = не принято (очередь переполнена, сессия закрыта). */
    fun sendVideo(payload: ByteArray): Boolean

    /**
     * Первый IDR из AA вместо заглушки: отправка и отключение кадров заглушки атомарно. Иначе P-кадр заглушки,
     * проскочивший между sendVideo и hidePlaceholder, ложится на AA-IDR и ГУ рисует полосы до следующего IDR.
     * Заглушку останавливать всё равно через hidePlaceholder.
     */
    fun sendVideoTakeover(payload: ByteArray): Boolean = sendVideo(payload)

    /** Текущий кадр заглушки (null = её нет): с него перекодирование начинает переход в картинку AA. */
    fun placeholderSnapshot(): android.graphics.Bitmap? = null

    /** Показывать на ГУ заглушку с текстом (кодирует VideoSource); можно звать до CarLife START - запомнится. */
    fun showPlaceholder(text: String)

    /** Остановить заглушку: дальше видео идёт из AA (sendVideo). */
    fun hidePlaceholder()

    /** Аудио-сообщение CarLife: канал 3 MEDIA / 4 TTS, тип (INIT/DATA/STOP), тело. false = отброшено. */
    fun sendAudio(channel: Int, type: Int, payload: ByteArray): Boolean

    /** MODULE_STATUS об одном модуле CarLife (CarlifeAudio.MODULE_NAVI / MODULE_MUSIC / ...), как Baidu ModuleStatusManage. false = не ушло. */
    fun sendModuleStatus(module: Int, status: Int): Boolean

    /** Создать и запустить транскодер (AA H.264 -> декодер -> энкодер). null = MediaCodec не поднялся, работаем passthrough. */
    fun createTranscoder(out: TranscodeOutput): VideoTranscoder? = null

    /**
     * Попросить ГУ начать (true) / закончить (false) запись микрофона, как Baidu MD (VrManager): CMD
     * MIC_RECORD_RECOG_START / MIC_RECORD_END (+ MODULE_STATUS VR). Данные придут в AaBridgeCore.onCarlifeMicData.
     * false = сообщение не ушло (сессия закрыта).
     */
    fun requestMicRecord(start: Boolean): Boolean = false

    /** Ключевой кадр энкодера (размер в байтах) ушёл в CarLife; зовётся только на ГУ с лимитом размера IDR (VW ID, h196). */
    fun onEncodedKeyFrame(size: Int) {}

    /** Состояние видео-очереди (слив, бэклог, длина) для статистики моста; null = нет данных. */
    fun videoStats(): LinkVideoStats? = null

    /** CMD MEDIA_INFO (0x10035); обложку (PNG из AA) перекодирует реализация, не задерживая вызывающего. false = не ушло. */
    fun sendMediaInfo(info: CarlifeMediaInfo): Boolean = false

    /** CMD MEDIA_PROGRESS_BAR (0x10036). */
    fun sendMediaProgress(progressBar: Int): Boolean = false

    /** CMD NAVI_NEXT_TURN_INFO (0x10030); поле 6 (иконка / time) по настройке реализации. */
    fun sendNaviTurn(info: NaviTurnInfo): Boolean = false

    /** CMD GO_TO_DESKTOP (0x10021): ГУ уходит на свой рабочий стол (плитка «Выйти» в AA). */
    fun goToDesktop() {}
}

/** Ссылка на мёртвую CarLife-сессию (USB оборван): всё отбрасывает. */
private object NoLink : CarlifeLink {
    override fun sendVideo(payload: ByteArray) = false
    override fun showPlaceholder(text: String) {}
    override fun hidePlaceholder() {}
    override fun sendAudio(channel: Int, type: Int, payload: ByteArray) = false
    override fun sendModuleStatus(module: Int, status: Int) = false
    override fun goToDesktop() {}
}

/**
 * Логика моста AA -> CarLife без Android и сокетов: приём видео от AA как AaVideoSink, правила первого
 * сообщения для CarLife (SPS+PPS+IDR в одном), переключение заглушка/AA, маппинг тачей, запрос ключевого кадра.
 */
class AaBridgeCore(
    private val log: (String, Boolean) -> Unit,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val clockUs: () -> Long = { System.nanoTime() / 1_000 },
    micSchedule: ((Long, Runnable) -> MicTimerHandle)? = null,
    audioSchedule: ((Long, Runnable) -> MicTimerHandle)? = null,
) : AaVideoSink, AaAudioSink, AaMicSink, AaMetaSink, TranscodeOutput {
    enum class Source { PLACEHOLDER, AA }

    @Volatile var peer: AaPeer? = null
        set(v) {
            synchronized(touchLock) {
                field = v
                fingers.clear()
            }
        }
    @Volatile private var link: CarlifeLink = NoLink

    @Volatile private var carlifeStarted = false
    private var aaConfig: ByteArray? = null
    private var aaVideoSeen = false
    private var gotIdr = false
    private var active = Source.PLACEHOLDER
    private var placeholderText = TEXT_START_SERVER
    private var waitSince = 0L
    private var lastKeyframeRequest = NEVER

    var dropped = 0L
        private set
    var sent = 0L
        private set
    var idrSent = 0L
        private set
    var keyframeRequests = 0
        private set
    private var dropLogged = 0L
    private var touchDown = false

    // --- видео: перекодирование (по умолчанию в приложении) или passthrough ---
    /** Режим видео моста: true = AA -> декодер -> энкодер (кадры повторяются на статике), false = H.264 как есть. */
    @Volatile var transcodeEnabled = false

    /**
     * Разрешение видео, снятое один раз при создании хоста (уже с учётом passthrough): им построена AA (SDR),
     * его же берут CarLife-сессия (INIT_DONE, энкодер) и маппинг тача. Prefs отсюда не читаются.
     */
    @Volatile var videoMode: VideoMode = VideoMode.DEFAULT

    /** Ручной DPI интерфейса AA, снятый вместе с videoMode (AaDensity.AUTO = density режима). Только SDR, на кроп и тач не влияет. */
    @Volatile var densityOverride: Int = AaDensity.AUTO
    private var transcoder: VideoTranscoder? = null
    private var encCsd: ByteArray? = null
    private var outGotIdr = false
    private val idrThrottle = IdrThrottle()            // запросы IDR при переполнении очереди CarLife
    private var overflowIdrRequests = 0L
    private var lastStatsIdrRequests = 0L
    private var outputEnabled = false
    private var lastAaFrameAt = 0L
    private var lastStatsAt = 0L
    private var lastStatsSent = 0L
    private var lastStatsAaFrames = 0L
    private var aaFrames = 0L
    private var encDropped = 0L
    val isTranscoding: Boolean @Synchronized get() = transcoder != null

    // --- звук AA -> CarLife ---
    /** Переключатель UI «Звук по USB». */
    @Volatile var usbAudioEnabled = true

    /** Ждать ли FEATURE_CONFIG ГУ перед подключением к AA (чтобы решить, объявлять ли аудио-синки). */
    @Volatile var expectFeatureConfig = false
    @Volatile var featureConfigSeen = false
    @Volatile private var btAudio = false
    @Volatile var huDesayG6S = false           // ГУ Desay G6S (Mazda CX-5): NAVI=1 глушит штатное радио, маршрутный NAVI не шлём
        private set
    @Volatile var huVw20082120 = false         // VW (STATISTIC_INFO channel 20082120, h93): без heartbeat рвёт USB через ~3,7 с PAUSE
        private set
    @Volatile var huVw20082138 = false         // VW ID (channel 20082138, "VW BT 1538", h196): IDR > ~600 КиБ рвёт USB EIO
        private set
    @Volatile var huHyundaiCn7 = false         // ГУ Hyundai CN7 (product wp_cn7cpe_cn, h218): в режиме «Авто» музыка 32 кГц с ресэмплом 48→32
        private set
    @Volatile var musicRateProvider: () -> MusicRate = { MusicRate.DEFAULT }
    private var resampler: MusicResampler? = null    // null при 48000: PCM идёт как есть
    private var mediaRateLogged = MEDIA_RATE
    private val audioInited = HashMap<Int, Boolean>()
    private val audioLogged = HashMap<Int, Long>()

    /** Эквалайзер музыки (только CarLife MEDIA); настройки читаются на каждом буфере, меняются из UI на лету. */
    private val equalizer = Equalizer(48000, 2)
    var eqProvider: () -> EqSettings = { EqLive.settings }
    private var eqLogged: EqSettings? = null
    var audioBytes = 0L
        private set
    var audioDropped = 0L
        private set

    // Серийность аудио, по MD Baidu: NAVI перед TTS, MEDIA отпускается PAUSE, TTS_STOP с задержкой.
    private val audioTimer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aa-audio-timer").also { it.isDaemon = true } }
    }
    private val audioSched: (Long, Runnable) -> MicTimerHandle = audioSchedule ?: { delayMs, task ->
        val f = audioTimer.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        MicTimerHandle { f.cancel(false) }
    }

    /** Одноразовый отменяемый таймер; задача идёт под блокировкой ядра, отменённая (даже уже вставшая в очередь) не выполняется. */
    private inner class AudioTimer {
        private var gen = 0
        private var handle: MicTimerHandle? = null
        val pending: Boolean get() = handle != null

        fun start(delayMs: Long, task: () -> Unit) {
            cancel()
            val g = gen
            handle = audioSched(delayMs, Runnable {
                synchronized(this@AaBridgeCore) {
                    if (g == gen && handle != null) {
                        handle = null
                        task()
                    }
                }
            })
        }

        fun cancel(): Boolean {
            val h = handle ?: return false
            handle = null
            gen++
            h.cancel()
            return true
        }
    }

    private val ttsStopTimer = AudioTimer()
    private val mediaReleaseTimer = AudioTimer()
    private val voiceExitTimer = AudioTimer()
    private var carlifePaused = false          // VIDEO_ENCODER_PAUSE после последнего START
    private var exitPending = false            // «Выйти» в AA: GO_TO_DESKTOP ушёл, ждём PAUSE от ГУ, чтобы отдать AA NATIVE
    private var huPausedAa = false             // плеер AA поставили на паузу, когда ГУ ушло на свой источник; MEDIA не пересылаем до play
    private var resumeOnHuMusic = false        // паузу поставили мы (127 при игравшем AA): на MUSIC=1 от ГУ можно вернуть play
    private var lastHuResumeMs = NEVER         // когда по MUSIC=1 от ГУ слали AA KEYCODE 126
    @Volatile private var lastUserInputMs = NEVER   // последний тач DOWN / клавиша / шайба / голос с ГУ
    private var lastHfpConnectMs = NEVER       // ГУ прислало BT_HFP_CONNECTION=connected (дальше AA сам запускает автоплей по BT)
    private var lastPauseKeyMs = NEVER         // когда последний раз слали AA KEYCODE 127
    private var naviSent = false               // MODULE_STATUS NAVI=1 ушёл (держит TTS и/или маршрут)
    private var ttsNaviHeld = false            // NAVI=1 нужен ради TTS (до stopAllAudio)
    private var routeNaviHeld = false          // NAVI=1 нужен ради маршрута (MetaRelay)
    private var mediaPaused = false            // MEDIA на ГУ стоит на PAUSE (STOP ещё не слали)
    private var mediaGuard = false             // TTS_STOP уже ушёл, ждём +300 мс до управляющих пакетов MEDIA
    private var pendingMediaPause = false      // желаемое состояние MEDIA = пауза, применим после TTS
    private var voice = false
    private var mediaGateDrops = 0L
    private var ttsPackets = 0

    /** MEDIA инициализирован на ГУ и играет (для ответа на MODULE_CONTROL MUSIC). */
    val isMediaInited: Boolean @Synchronized get() = audioInited[CarlifeAudio.CH_MEDIA] == true

    /** Ответ на MODULE_CONTROL MUSIC: звук по USB - по MEDIA INIT, иначе по состоянию плеера AA (ch9). */
    val musicActive: Boolean @Synchronized get() = isMediaInited || (!audioViaUsb && meta.playing)

    // --- метаданные AA (ch9/ch10) -> CarLife MEDIA_INFO / PROGRESS / NAVI_NEXT_TURN_INFO ---
    private val meta = MetaRelay(
        log = log,
        clockMs = clockMs,
        link = { link },
        ready = { carlifeStarted && link !== NoLink },
        navHold = { on -> routeNavi(on) },
        musicState = { on -> musicByState(on) },
    )

    val metaRelay: MetaRelay get() = meta

    /** Звук идёт по USB: включён в UI и ГУ не просит Bluetooth. Читает AapOptions.sdProvider (какие синки объявлять). */
    val audioViaUsb: Boolean get() = usbAudioEnabled && !btAudio

    val activeSource: Source @Synchronized get() = active
    val currentPlaceholderText: String @Synchronized get() = placeholderText
    val isCarlifeStarted: Boolean @Synchronized get() = carlifeStarted

    /** Есть живая (не закрытая) CarLife-сессия: USB подключён. Читается без блокировки. */
    val hasCarlifeLink: Boolean get() = link !== NoLink

    /**
     * Сменить текст заглушки на ГУ («Запускаю сервер...», «Разблокируйте телефон»); когда уже идёт видео AA, не трогаем.
     * Запоминается и для будущей CarLife-сессии.
     */
    @Synchronized
    fun showStatus(text: String) {
        if (active == Source.AA) return
        placeholderText = text
        link.showPlaceholder(text)
        log("BRIDGE заглушка: '$text'", true)
    }

    // --- микрофон AA: запрос открыл телефон, звук берём у ГУ (CarLife VR) или с микрофона телефона ---
    /** Режим микрофона читается в момент каждого open: переключатель в UI действует сразу. */
    @Volatile var micModeProvider: () -> MicMode = { MicMode.AUTO }

    /** Микрофон телефона (AudioRecord); null = нет. Один экземпляр на всё время: router вызывает start/stop по очереди. */
    @Volatile var phoneMic: PhoneMic? = null

    private val micTimer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aa-mic-timer").also { it.isDaemon = true } }
    }

    private val mic = AaMicRouter(
        log = log,
        mode = { micModeProvider() },
        huLinkUp = { carlifeStarted && link !== NoLink },
        huRecord = { start -> link.requestMicRecord(start) },
        phone = { phoneMic },
        toAa = { pcm, ts -> peer?.sendMicPcm(pcm, ts) },
        schedule = micSchedule ?: { delayMs, task ->
            val f = micTimer.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            MicTimerHandle { f.cancel(false) }
        },
        nowMs = clockMs,
        nowUs = clockUs,
    )

    override fun onMicOpen(): Boolean = mic.open()

    override fun onMicClose(why: String) = mic.close(why)

    /** PCM микрофона ГУ (VR 0x00058001, тело сообщения) -> AA. */
    fun onCarlifeMicData(pcm: ByteArray) = mic.onHuData(pcm)

    /**
     * Вызвать при старте CarLife-сессии: заглушка. Если AA уже жива и играет (USB вернулся в grace-окно),
     * текст «ждём кадр» вместо «запустите сервер», а ключевой кадр уйдёт на CarLife START.
     */
    @Synchronized
    fun attach(link: CarlifeLink) {
        this.link = link
        active = Source.PLACEHOLDER
        carlifeStarted = false
        carlifePaused = false
        exitPending = false
        huPausedAa = false
        resumeOnHuMusic = false
        lastHuResumeMs = NEVER
        gotIdr = false
        huDesayG6S = false                     // HU_INFO / STATISTIC_INFO придут заново в этой сессии
        huVw20082120 = false                   // HU_INFO / STATISTIC_INFO придут заново в этой сессии
        huVw20082138 = false                   // HU_INFO / STATISTIC_INFO придут заново в этой сессии
        huHyundaiCn7 = false                   // HU_INFO придёт заново в этой сессии
        mediaRateLogged = MEDIA_RATE
        resampler?.reset()
        if (aaVideoSeen) placeholderText = TEXT_WAIT_FRAME
        meta.onAttach()
        link.showPlaceholder(placeholderText)
        log("BRIDGE CarLife-сессия подключена: источник = заглушка '$placeholderText' (AA ${if (aaVideoSeen) "жива" else "нет видео"})", true)
    }

    // --- события CarLife ---
    @Synchronized
    fun onCarlifeStart() {
        // Desay G6S (Mazda) шлёт START без PAUSE на каждое нажатие NAVI/MEDIA: звук уже идёт, повторный INIT
        // посреди трека рвёт его на ГУ (лог 2026-10-11). Звук сбрасываем только на первом START и после PAUSE.
        val warm = carlifeStarted && !carlifePaused
        carlifeStarted = true
        carlifePaused = false
        if (warm) log("BRIDGE CarLife START без PAUSE: звук не трогаю, INIT не повторяю", true)
        else resetAudio()                      // новый START: MEDIA/TTS init заново на первом пакете
        gotIdr = false
        waitSince = if (aaVideoSeen) clockMs() - KEYFRAME_WAIT_MS else 0L
        lastKeyframeRequest = NEVER
        log("BRIDGE CarLife START: источник=$active, AA видео ${if (aaVideoSeen) "идёт, запрашиваю ключевой кадр" else "ещё нет"}", true)
        if (transcodeEnabled) startTranscoder()
        else log("BRIDGE видео: режим passthrough (H.264 AA как есть)", true)
        if (aaVideoSeen) {
            // AA уже играет (тёплая сессия): не ждать тик, сразу цикл NATIVE -> PROJECTED (декодеру нужен IDR от AA)
            lastKeyframeRequest = clockMs()
            keyframeRequests++
            peer?.requestKeyFrame()
        }
        if (!warm) meta.onCarlifeStart()
        // play на START не шлём: звук CarLife сразу отбирает у ГУ радио, а слушать радио под навигацию - сценарий (h33).
        // Вернуть музыку - только по явному MUSIC=1 от ГУ (onCarlifeMusicControl).
        if (huPausedAa) log("BRIDGE CarLife START: AA на паузе после ухода ГУ на свой источник, музыку не запускаю", true)
    }

    /**
     * MODULE_CONTROL MUSIC от ГУ; ответ - MODULE_STATUS MUSIC. status=0 - ГУ ушло на свой источник (радио): Desay G6S
     * (Mazda) шлёт его после PAUSE, другие ГУ - без PAUSE. Плеер AA на паузу, хвост его буфера не пересылаем до
     * следующего play, отвечаем MUSIC=0 - иначе ГУ видит CarLife-музыку активной и спорит с радио.
     * Хвост держим и когда AA уже на паузе (Honda: MUSIC=0 после паузы ассистентом, хвост буфера давал MEDIA INIT
     * через 40 мс), и MUSIC=0 отвечаем, даже если MEDIA PAUSE отложен до конца TTS (лог Егора 2026-10-13).
     *
     * status=1 - Desay G6S вернулся на CarLife-музыку (пачка 10-20 MUSIC=1 рядом с START). После нашей паузы это выбор
     * пользователя на ГУ: AA KEYCODE 126 и MUSIC=1 на всю пачку (лог h214 2026-10-17). Простой возврат на карту
     * (NAVI) приходит без MUSIC=1 - там радио не трогаем. Свежая сессия тоже шлёт MUSIC=1, но паузы нашей там нет.
     */
    @Synchronized
    fun onCarlifeMusicControl(status: Int?): Boolean {
        if (HU_MUSIC_FOLLOW_ENABLED && status == 0 && carlifeStarted) {
            val p = peer
            if (meta.playing && !huPausedAa && p != null) {
                log("BRIDGE ГУ ушло на свой источник (MUSIC=0${if (carlifePaused) " после PAUSE" else ""}): AA KEYCODE $KEYCODE_MEDIA_PAUSE, MEDIA на паузу", true)
                sendPauseKey(p)
                resumeOnHuMusic = true
            } else if (!huPausedAa) {
                log("BRIDGE ГУ ушло на свой источник (MUSIC=0), AA не играет: хвост MEDIA не пересылаю до play", true)
                resumeOnHuMusic = false
            }
            huPausedAa = true
            requestMediaPause("ГУ MODULE_CONTROL MUSIC=0")
            return false
        }
        if (HU_MUSIC_RESUME_ENABLED && status == 1 && huDesayG6S) {
            val now = clockMs()
            val p = peer
            if (huPausedAa && resumeOnHuMusic && p != null) {
                huPausedAa = false
                resumeOnHuMusic = false
                lastHuResumeMs = now
                lastUserInputMs = now              // выбор источника на ГУ - ввод пользователя, BT-гард его не перебивает
                log("BRIDGE ГУ вернулось на CarLife-музыку (MUSIC=1) после нашей паузы: AA KEYCODE $KEYCODE_MEDIA_PLAY", true)
                p.sendKey(KEYCODE_MEDIA_PLAY)
                return true
            }
            if (now - lastHuResumeMs < HU_RESUME_ANSWER_MS) return true   // пачка MUSIC=1, пока AA раскачивается
        }
        return musicActive
    }

    private fun sendPauseKey(p: AaPeer) {
        lastPauseKeyMs = clockMs()
        p.sendKey(KEYCODE_MEDIA_PAUSE)
    }

    /** BT_HFP_CONNECTION от ГУ: state=2 - телефон подключился к ГУ по Bluetooth (0 - отключился). */
    @Synchronized
    fun onHuBtHfpConnection(state: Int?) {
        if (state == 2) {
            lastHfpConnectMs = clockMs()
            log("BRIDGE ГУ: BT HFP подключён (автоплей AA в ближайшие $BT_AUTOPLAY_WINDOW_MS мс не снимет паузу)", true)
        } else {
            log("BRIDGE ГУ: BT HFP состояние=$state", true)
        }
    }

    /** AA снова заиграл сам (play пользователя): звук опять пересылаем. После BT HFP без ввода с ГУ это автоплей - паузу держим. */
    private fun releaseHuPause(why: String) {
        if (!huPausedAa) return
        val now = clockMs()
        val p = peer
        if (BT_AUTOPLAY_GUARD_ENABLED && p != null && now - lastHfpConnectMs < BT_AUTOPLAY_WINDOW_MS &&
            now - lastUserInputMs > USER_INPUT_WINDOW_MS) {
            if (now - lastPauseKeyMs >= REPAUSE_MIN_MS) {
                log("BRIDGE $why через ${now - lastHfpConnectMs} мс после BT HFP без ввода пользователя: автоплей, снова KEYCODE $KEYCODE_MEDIA_PAUSE", true)
                sendPauseKey(p)
            }
            return
        }
        huPausedAa = false
        resumeOnHuMusic = false
        log("BRIDGE $why: MEDIA снова пересылаю", true)
    }

    private fun ago(t: Long): String = if (t == NEVER) "не было" else "${clockMs() - t} мс назад"

    /** CARLIFE_DATA_SUBSCRIBE_START (on=true) / STOP от ГУ; moduleID подписки: 0 навигация, 2 музыка. */
    @Synchronized
    fun onCarlifeSubscribe(moduleIds: List<Int>, on: Boolean) {
        for (id in moduleIds) meta.onSubscribe(id, on)
    }

    /** VIDEO_ENCODER_PAUSE от ГУ: следующий START - полный, со сбросом звука. */
    @Synchronized
    fun onCarlifePause() {
        carlifePaused = true
        if (exitPending) {
            // ГУ ушёл с CarLife после нашего GO_TO_DESKTOP: говорим AA, что проекции нет (NATIVE), возврат - через START
            exitPending = false
            log("BRIDGE ГУ вышел из CarLife (PAUSE) после «Выйти»: AA VideoFocus NATIVE", true)
            peer?.sendVideoFocusNative()
        }
    }

    /** Плитка «Выйти» в AA: VIDEO_FOCUS_REQUEST NATIVE. Просим ГУ на рабочий стол; NATIVE для AA - когда ГУ пришлёт PAUSE. */
    @Synchronized
    override fun onAaNativeFocusRequest() {
        if (link === NoLink || !carlifeStarted) {
            log("BRIDGE AA: «Выйти» (VIDEO_FOCUS NATIVE) проигнорирован: CarLife ${if (link === NoLink) "не подключён" else "ещё не START"}", true)
            return
        }
        log("BRIDGE AA: «Выйти» (VIDEO_FOCUS NATIVE) -> CarLife GO_TO_DESKTOP", true)
        exitPending = true
        link.goToDesktop()
        // AA ждёт ответа на NATIVE с чёрной областью лаунчера. Desay G6S (Mazda) не шлёт ни PAUSE на выходе, ни START
        // на возврате (лог 2026-10-12), так что ответ сразу: цикл NATIVE -> PROJECTED возвращает AA на главный экран.
        lastKeyframeRequest = clockMs()
        keyframeRequests++
        peer?.requestKeyFrame()
    }

    /** FEATURE_CONFIG ГУ разобран; mode = значение AUDIO_TRANSMISSION_MODE (null - ключа нет). */
    fun onHuFeatureConfig(audioTransmissionMode: Int?) {
        featureConfigSeen = true
        if (audioTransmissionMode == CarlifeAudio.AUDIO_MODE_BT) {
            btAudio = true
            log("BRIDGE !!!!!! ГУ: AUDIO_TRANSMISSION_MODE=1 (звук по Bluetooth). Звук по USB НЕ шлю, AA-звук остаётся на телефоне (синки MEDIA/SPEECH не объявляю)", true)
        } else {
            log("BRIDGE AUDIO_TRANSMISSION_MODE=${audioTransmissionMode ?: "нет в списке"}: звук по USB ${if (usbAudioEnabled) "включён" else "выключен в UI"}", true)
        }
    }

    /**
     * HU_INFO ГУ разобран: Desay G6S опознаём по производителю и модели (другие ГУ Desay SV под запрет не попадают),
     * Hyundai CN7 - по product.
     */
    fun onHuInfo(manufacturer: String?, model: String?, product: String? = null) {
        if (manufacturer == DESAY_MANUFACTURER && model == DESAY_G6S_MODEL) huDesayG6S = true
        if (manufacturer == VW_20082138_MANUFACTURER) huVw20082138 = true
        if (product == HYUNDAI_CN7_PRODUCT) huHyundaiCn7 = true
    }

    /** STATISTIC_INFO ГУ разобран: запасной признак Desay G6S и признак VW 20082120 - канал сборки прошивки. */
    fun onHuStatistic(channel: String?) {
        if (channel == DESAY_G6S_CHANNEL) huDesayG6S = true
        if (channel == VW_20082120_CHANNEL) huVw20082120 = true
        if (channel == VW_20082138_CHANNEL) huVw20082138 = true
    }

    /** CarLife-сессия закрыта (USB EIO / detach / Стоп). AA не трогаем; from - закрываемая ссылка, чужую не отцепляем. */
    fun onCarlifeClosed(from: CarlifeLink? = null) {
        if (closeCarlife(from)) mic.onCarlifeLost()      // вне блокировки ядра: роутер микрофона берёт свою
    }

    @Synchronized
    private fun closeCarlife(from: CarlifeLink?): Boolean {
        if (from != null && from !== link) {
            log("BRIDGE закрытие старой CarLife-сессии, текущая уже другая: игнор", false)
            return false
        }
        carlifeStarted = false
        exitPending = false
        gotIdr = false
        resetAudio()
        stopTranscoderAsync()
        active = Source.PLACEHOLDER
        link = NoLink
        synchronized(touchLock) { fingers.clear() }
        log("BRIDGE CarLife закрыт (AA ${if (aaVideoSeen) "жива" else "без видео"}, держим)", true)
        return true
    }

    /** Палец CarLife-тача: id для AA и последние координаты кадра ГУ. */
    private class Finger(val id: Int, var x: Int, var y: Int) {
        fun set(nx: Int, ny: Int) {
            x = nx
            y = ny
        }
    }

    private val touchLock = Any()

    /** Активные пальцы в порядке индекса MotionEvent ГУ: x,y TOUCH_ACTION - индекс 0, pointerx/pointery - индекс 1. */
    private val fingers = ArrayList<Finger>(2)

    /**
     * TOUCH_ACTION ГУ -> AA. action как у MotionEvent: action&255 = 0 DOWN, 1 UP, 2 MOVE, 5 POINTER_DOWN, 6 POINTER_UP,
     * action>>8 = индекс пальца. Второй палец - в (px, py); 0,0 = координат нет (тогда последние известные).
     * До двух пальцев; id оставшегося пальца после POINTER_UP не меняется. Координаты кадра ГУ -> кадр AA через
     * [VideoMode.mapTouch] (без margin-ов 1:1). Один палец уходит в AA так же, как до мультитача (id 0, action_index 0).
     */
    fun onCarlifeTouch(action: Int, x: Int, y: Int, px: Int = 0, py: Int = 0) = synchronized(touchLock) {
        val act = action and 0xFF
        val idx = action ushr 8
        if (act == AapMessages.TOUCH_DOWN || act == AapMessages.TOUCH_POINTER_DOWN) lastUserInputMs = clockMs()
        val name = TOUCH_NAMES[act]
        if (name == null) {
            log("BRIDGE тач action=$action x=$x y=$y не передаю (нет аналога в AA)", false)
            return@synchronized
        }
        val p = peer
        if (p == null) {
            log("BRIDGE тач $name $x,$y проигнорирован: AA не подключён", false)
            fingers.clear()
            return@synchronized
        }
        val second = px != 0 || py != 0
        when (act) {
            AapMessages.TOUCH_DOWN -> {
                fingers.clear()
                fingers.add(Finger(0, x, y))
                sendFingers(p, act, 0, name)
            }
            AapMessages.TOUCH_MOVE -> {
                if (fingers.isEmpty()) fingers.add(Finger(0, x, y))   // DOWN потерян: как раньше, один палец id 0
                fingers[0].set(x, y)
                if (fingers.size > 1 && second) fingers[1].set(px, py)
                sendFingers(p, act, 0, name)
            }
            AapMessages.TOUCH_POINTER_DOWN -> {
                if (fingers.size >= 2 || idx > 1) {
                    log("BRIDGE тач $name action=$action: больше двух пальцев не передаю", false)
                    return@synchronized
                }
                if (fingers.isEmpty()) fingers.add(Finger(0, x, y))
                val pos = idx.coerceAtMost(fingers.size)
                val fresh = Finger(if (fingers[0].id == 0) 1 else 0, x, y)
                fingers.add(pos, fresh)
                fingers[0].set(x, y)
                if (second) fingers[1].set(px, py) else if (fingers[1] === fresh) fresh.set(x, y)
                sendFingers(p, act, pos, name)
            }
            AapMessages.TOUCH_POINTER_UP -> {
                if (fingers.size < 2 || idx > 1) {
                    log("BRIDGE тач $name action=$action: пальцев ${fingers.size}, не передаю", false)
                    return@synchronized
                }
                fingers[0].set(x, y)
                if (second) fingers[1].set(px, py)
                sendFingers(p, act, idx, name)
                fingers.removeAt(idx)
            }
            else -> {                                              // UP последнего пальца
                if (fingers.size > 1) {                            // POINTER_UP потерян: сначала снимаем второй палец
                    sendFingers(p, AapMessages.TOUCH_POINTER_UP, 1, "POINTER_UP")
                    fingers.removeAt(1)
                }
                if (fingers.isEmpty()) fingers.add(Finger(0, x, y))
                fingers[0].set(x, y)
                sendFingers(p, act, 0, name)
                fingers.clear()
            }
        }
    }

    /** Все активные пальцы -> AA одним событием; лог как до мультитача для одного пальца. */
    private fun sendFingers(p: AaPeer, act: Int, index: Int, name: String) {
        val mode = videoMode
        val points = fingers.map { f ->
            val (ax, ay) = mode.mapTouch(f.x, f.y)
            TouchPoint(f.id, ax, ay)
        }
        val ui = act != AapMessages.TOUCH_MOVE
        if (points.size == 1) {
            val f = fingers[0]
            val a = points[0]
            if (a.x == f.x && a.y == f.y) log("BRIDGE тач $name ${f.x},${f.y} -> AA", ui)
            else log("BRIDGE тач $name ГУ ${f.x},${f.y} -> AA ${a.x},${a.y} (${mode.name})", ui)
        } else {
            val gu = fingers.joinToString(" ") { "#${it.id} ${it.x},${it.y}" }
            val aa = points.joinToString(" ") { "#${it.id} ${it.x},${it.y}" }
            log("BRIDGE тач $name[$index] пальцев ${points.size}: ГУ $gu -> AA $aa (${mode.name})", ui)
        }
        p.sendTouch(act, index, points)
    }

    /** TOUCH_CAR_HARD_KEY_CODE ГУ (кнопки руля) -> AA: нажатие Android KeyEvent по [HARD_KEY_TO_ANDROID]. */
    fun onCarlifeHardKey(code: Int) {
        lastUserInputMs = clockMs()
        HARD_KEY_TO_ROTARY[code]?.let { delta ->
            val p = peer
            if (p == null) {
                log("BRIDGE шайба keycode=$code проигнорирована: AA не подключён", true)
                return
            }
            log("BRIDGE шайба keycode=$code -> AA rotary $delta", true)
            p.sendRotary(delta)
            return
        }
        val android = HARD_KEY_TO_ANDROID[code]
        if (android == null) {
            log("BRIDGE руль keycode=$code: нет аналога в AA, игнор", true)
            return
        }
        val p = peer
        if (p == null) {
            log("BRIDGE руль keycode=$code проигнорирован: AA не подключён", true)
            return
        }
        log("BRIDGE руль keycode=$code -> AA KEYCODE $android", true)
        p.sendKey(android)
    }

    /** MODULE_CONTROL VR=1 от ГУ (кнопка голоса) -> AA KEYCODE_SEARCH: AA открывает ассистента и сам просит микрофон. */
    fun onCarlifeVoiceKey() {
        lastUserInputMs = clockMs()
        val p = peer
        if (p == null) {
            log("BRIDGE кнопка голоса проигнорирована: AA не подключён", true)
            return
        }
        log("BRIDGE кнопка голоса (MODULE_CONTROL VR=1) -> AA KEYCODE $KEYCODE_SEARCH", true)
        p.sendKey(KEYCODE_SEARCH)
    }

    // --- события AA ---
    @Synchronized
    fun onAaConnecting() {
        resetTranscodeForAa()
        gotIdr = false
        aaConfig = null
        aaVideoSeen = false
    }

    @Synchronized
    fun onAaEnded(established: Boolean) {
        meta.onAaEnded()
        stopAllAudio("AA завершился")
        resetTranscodeForAa()
        val wasAa = active == Source.AA
        gotIdr = false
        aaConfig = null
        aaVideoSeen = false
        if (established || wasAa) placeholderText = TEXT_AA_LOST
        if (wasAa || established) {
            active = Source.PLACEHOLDER
            log("BRIDGE AA завершился: источник -> заглушка '$placeholderText'", true)
            link.showPlaceholder(placeholderText)
        }
    }

    // --- звук AA ---
    private fun carlifeChannel(aaChan: Int): Int? = when (aaChan) {
        AapMessages.CH_AUDIO_MEDIA -> CarlifeAudio.CH_MEDIA
        AapMessages.CH_AUDIO_SPEECH -> CarlifeAudio.CH_TTS      // навигационные подсказки / голос
        else -> null                                              // SYSTEM (5): сигналы AA на ГУ не шлём
    }

    private fun chName(ch: Int) = if (ch == CarlifeAudio.CH_MEDIA) "MEDIA" else "TTS"

    @Synchronized
    override fun onAudioStart(chan: Int) {
        log("BRIDGE AA audio START ch$chan (${AapMessages.channelName(chan)}) -> CarLife ${carlifeChannel(chan)?.let { chName(it) } ?: "не пересылается"}", true)
        if (carlifeChannel(chan) == CarlifeAudio.CH_MEDIA) releaseHuPause("AA MEDIA START")
        if (carlifeChannel(chan) == CarlifeAudio.CH_TTS && ttsStopTimer.cancel()) {
            log("BRIDGE TTS STOP отменён: SPEECH START до истечения ${TTS_STOP_DELAY_MS} мс, INIT не повторяю", true)
        }
    }

    @Synchronized
    override fun onAudioData(chan: Int, pcm: ByteArray, ptsUs: Long) {
        val ch = carlifeChannel(chan)
        if (ch == null || !audioViaUsb || !carlifeStarted) {
            audioDropped++
            if (audioDropped <= 2 || audioDropped % 500 == 0L) {
                val why = when {
                    ch == null -> "SYSTEM-канал"
                    !audioViaUsb -> "звук по USB выключен/BT"
                    else -> "CarLife ещё не START"
                }
                log("BRIDGE AA audio ch$chan ${pcm.size} Б не пересылаю ($why), всего отброшено $audioDropped", false)
            }
            return
        }
        if (ch == CarlifeAudio.CH_TTS) {
            if (ttsStopTimer.cancel()) log("BRIDGE TTS STOP отменён: данные ch$chan до истечения ${TTS_STOP_DELAY_MS} мс, INIT не повторяю", true)
        } else if (!admitMedia()) {
            return
        }
        if (audioInited[ch] != true) {
            val setting = if (ch == CarlifeAudio.CH_MEDIA) musicRateProvider() else MusicRate.AUTO
            val rate = if (ch != CarlifeAudio.CH_MEDIA) 16000 else setting.hz ?: if (huHyundaiCn7) MEDIA_RATE_CN7 else MEDIA_RATE
            val channels = if (ch == CarlifeAudio.CH_MEDIA) 2 else 1
            if (ch == CarlifeAudio.CH_TTS && NAVI_STATUS_ENABLED) {
                ttsNaviHeld = true
                // как MD: перед TTS шлёт MODULE_STATUS NAVI=1 и держит его
                if (!naviSent && link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 1)) {
                    naviSent = true
                    log("BRIDGE CarLife MODULE_STATUS NAVI=1 (перед TTS INIT)", true)
                }
            }
            if (!link.sendAudio(ch, CarlifeAudio.initType(ch), CarlifeAudio.initBody(rate, channels, 16))) {
                audioDropped++
                return
            }
            audioInited[ch] = true
            log("BRIDGE CarLife ${chName(ch)} INIT ${rate}Гц ${channels}к 16бит (0x%08x)".format(CarlifeAudio.initType(ch)), true)
            if (ch == CarlifeAudio.CH_TTS) {
                ttsPackets = 0
                mediaGuard = false
                mediaReleaseTimer.cancel()
            } else {
                mediaPaused = false
                mediaGateDrops = 0
                // частота фиксируется на MEDIA INIT; каждый новый INIT - поток с чистого листа
                resampler = if (rate == MEDIA_RATE) null else resampler?.takeIf { it.outRate == rate }?.also { it.reset() } ?: MusicResampler(rate)
                if (rate != MEDIA_RATE && rate != mediaRateLogged) {
                    val why = if (setting.hz != null) "настройка" else "авто: Hyundai CN7"
                    log("BRIDGE музыка $rate Гц, ресэмпл $MEDIA_RATE→$rate ($why)", true)
                }
                mediaRateLogged = rate
                musicStatus(1)
            }
        }
        var out = pcm
        if (ch == CarlifeAudio.CH_MEDIA) {
            val eq = eqProvider()
            if (eq != eqLogged) {
                eqLogged = eq
                log("BRIDGE EQ: ${if (eq.isFlat) "без обработки" else "полосы ${eq.bandsCsv()} дБ, preamp ${eq.preamp} дБ"}", false)
            }
            equalizer.update(eq)
            equalizer.process(pcm)
            val rs = resampler
            if (rs != null) {
                out = rs.process(pcm)
                if (out.isEmpty()) return
            }
        }
        var off = 0
        while (off < out.size) {
            val n = minOf(CarlifeAudio.MAX_CHUNK, out.size - off)
            val chunk = if (off == 0 && n == out.size) out else out.copyOfRange(off, off + n)
            if (!link.sendAudio(ch, CarlifeAudio.dataType(ch), chunk)) {
                audioDropped++
            } else {
                audioBytes += n
                AaState.audioBytes = audioBytes
            }
            off += n
        }
        val c = (audioLogged[ch] ?: 0L) + 1
        audioLogged[ch] = c
        if (c <= 2 || c % 200 == 0L) log("BRIDGE AA audio ch$chan ${pcm.size} Б -> CarLife ${chName(ch)} (#$c, всего ${audioBytes / 1024} КиБ)", false)
        if (ch == CarlifeAudio.CH_TTS && ++ttsPackets <= TTS_DIAG_PACKETS) {
            val (peak, rms) = CarlifeAudio.pcm16Stats(pcm)
            log("BRIDGE TTS пакет #$ttsPackets: ${pcm.size} Б, peak=$peak rms=%.1f${if (peak == 0) " (ВСЕ НУЛИ)" else ""}".format(rms), false)
        }
    }

    @Synchronized
    override fun onAudioStop(chan: Int) {
        val ch = carlifeChannel(chan) ?: return
        log("BRIDGE AA audio STOP ch$chan", true)
        if (ch == CarlifeAudio.CH_MEDIA) {
            requestMediaPause("AA STOP ch$chan")
        } else if (TTS_DEBOUNCE_ENABLED) {
            if (audioInited[ch] == true && !ttsStopTimer.pending) {
                log("BRIDGE CarLife TTS STOP отложен на ${TTS_STOP_DELAY_MS} мс", true)
                ttsStopTimer.start(TTS_STOP_DELAY_MS) { ttsStopNow() }
            }
        } else {
            stopAudio(ch)
        }
    }

    // --- голосовая сессия Ассистента: MEDIA на паузу, данные не пересылаем; выход со сглаживанием пачки 2/2/1/2/2 ---
    @Synchronized
    override fun onVoiceSession(status: Int) {
        if (!VOICE_SESSION_ENABLED) return
        when (status) {
            1 -> {
                val cancelled = voiceExitTimer.cancel()
                if (!voice) {
                    voice = true
                    log("BRIDGE голосовая сессия: старт, MEDIA на паузу, данные MEDIA не пересылаю", true)
                    requestMediaPause("голосовая сессия")
                } else if (cancelled) {
                    log("BRIDGE голосовая сессия: выход отменён (снова status=1)", true)
                }
            }
            2 -> if (voice && !voiceExitTimer.pending) {
                voiceExitTimer.start(VOICE_EXIT_DELAY_MS) {
                    voice = false
                    log("BRIDGE голосовая сессия: выход, MEDIA поднимется через INIT на первых данных", true)
                }
            }
            else -> log("BRIDGE голосовая сессия: status=$status не знаю, игнор", false)
        }
    }

    // --- управляющие пакеты MEDIA (PAUSE) идут по очереди: не пока TTS инициализирован или до TTS_STOP+300 мс ---
    private fun mediaBlocked() = TTS_DEBOUNCE_ENABLED && (audioInited[CarlifeAudio.CH_TTS] == true || mediaGuard)

    /** Желаемое состояние MEDIA = пауза: сразу или, пока занят TTS, после TTS_STOP+300 мс. */
    private fun requestMediaPause(why: String) {
        if (audioInited[CarlifeAudio.CH_MEDIA] != true) {
            pendingMediaPause = false
            return
        }
        if (mediaBlocked()) {
            pendingMediaPause = true
            log("BRIDGE MEDIA пауза отложена ($why): TTS занят, применю после TTS_STOP+${MEDIA_AFTER_TTS_MS} мс", true)
            return
        }
        pendingMediaPause = false
        pauseMedia(why)
    }

    private fun pauseMedia(why: String) {
        if (!MEDIA_PAUSE_ENABLED) {
            stopAudio(CarlifeAudio.CH_MEDIA)
            return
        }
        audioInited[CarlifeAudio.CH_MEDIA] = false
        mediaPaused = true
        link.sendAudio(CarlifeAudio.CH_MEDIA, CarlifeAudio.MEDIA_PAUSE, ByteArray(0))
        log("BRIDGE CarLife MEDIA PAUSE (0x%08x), $why".format(CarlifeAudio.MEDIA_PAUSE), true)
        musicStatus(0)
    }

    /** Фактический TTS_STOP (таймер debounce); после него MEDIA-пакеты ждут ещё MEDIA_AFTER_TTS_MS. */
    private fun ttsStopNow() {
        stopAudio(CarlifeAudio.CH_TTS)
        mediaGuard = true
        mediaReleaseTimer.start(MEDIA_AFTER_TTS_MS) {
            mediaGuard = false
            if (pendingMediaPause) {
                pendingMediaPause = false
                if (audioInited[CarlifeAudio.CH_MEDIA] == true) pauseMedia("отложенная пауза после TTS")
            }
        }
    }

    /** Пускать ли MEDIA_DATA дальше; нет = данные отброшены (голос / ждём INIT после TTS). */
    private fun admitMedia(): Boolean {
        val why = when {
            VOICE_SESSION_ENABLED && voice -> "идёт голосовая сессия"
            huPausedAa -> "ГУ на своём источнике, AA на паузе (хвост буфера)"
            audioInited[CarlifeAudio.CH_MEDIA] != true && mediaBlocked() -> "TTS занят, MEDIA ждёт INIT"
            else -> null
        }
        if (why != null) {
            mediaGateDrops++
            if (mediaGateDrops == 1L || mediaGateDrops % 500 == 0L) log("BRIDGE AA audio MEDIA данные отброшены: $why (#$mediaGateDrops)", false)
            return false
        }
        if (pendingMediaPause) {
            pendingMediaPause = false
            log("BRIDGE отложенная пауза MEDIA отменена: данные снова идут", true)
        }
        return true
    }

    private fun musicStatus(status: Int) {
        if (MODULE_MUSIC_ENABLED) link.sendModuleStatus(CarlifeAudio.MODULE_MUSIC, status)
    }

    /** Плеер AA сменил состояние (ch9). MUSIC по нему - только когда звук не по USB: иначе им владеет MEDIA INIT/PAUSE. */
    private fun musicByState(playing: Boolean) {
        if (playing) releaseHuPause("AA заиграл")
        else log("BRIDGE AA плеер остановился: наш KEYCODE $KEYCODE_MEDIA_PAUSE ${ago(lastPauseKeyMs)}, ввод пользователя ${ago(lastUserInputMs)}, BT HFP ${ago(lastHfpConnectMs)}", true)
        if (audioViaUsb || !MODULE_MUSIC_ENABLED || !carlifeStarted) return
        if (link.sendModuleStatus(CarlifeAudio.MODULE_MUSIC, if (playing) 1 else 0)) {
            log("BRIDGE CarLife MODULE_STATUS MUSIC=${if (playing) 1 else 0} (плеер AA, звук не по USB)", true)
        }
    }

    /** Маршрут держит / отпускает NAVI. NAVI=0 уходит, только когда его не держит и TTS. */
    private fun routeNavi(on: Boolean) {
        if (!ROUTE_NAVI_STATUS_ENABLED) {
            routeNaviHeld = false
            if (on) log("BRIDGE маршрут: MODULE_STATUS NAVI=1 не шлю (только вокруг TTS)", false)
            return
        }
        if (huDesayG6S) {
            routeNaviHeld = false
            if (on) log("BRIDGE маршрут: MODULE_STATUS NAVI=1 не шлю (Desay G6S: глушит радио)", false)
            return
        }
        routeNaviHeld = on
        if (on && !naviSent) {
            if (link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 1)) {
                naviSent = true
                log("BRIDGE CarLife MODULE_STATUS NAVI=1 (маршрут)", true)
            }
        } else if (!on && naviSent && !ttsNaviHeld) {
            naviSent = false
            link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 0)
            log("BRIDGE CarLife MODULE_STATUS NAVI=0 (маршрут закончен)", true)
        } else if (!on && naviSent) {
            log("BRIDGE маршрут закончен, NAVI=1 держит TTS", false)
        }
    }

    private fun stopAudio(ch: Int) {
        if (ch == CarlifeAudio.CH_TTS) ttsStopTimer.cancel()
        val wasInited = audioInited[ch] == true
        if (wasInited || (ch == CarlifeAudio.CH_MEDIA && mediaPaused)) {
            audioInited[ch] = false
            if (ch == CarlifeAudio.CH_MEDIA) {
                mediaPaused = false
                if (wasInited) musicStatus(0)
            }
            link.sendAudio(ch, CarlifeAudio.stopType(ch), ByteArray(0))
            log("BRIDGE CarLife ${chName(ch)} STOP (0x%08x)".format(CarlifeAudio.stopType(ch)), true)
        }
    }

    /** Сброс звукового состояния CarLife-сессии (START / закрытие): ссылка уже новая или мёртвая, ничего не шлём. */
    private fun resetAudio() {
        audioInited.clear()
        ttsStopTimer.cancel()
        mediaReleaseTimer.cancel()
        naviSent = false
        ttsNaviHeld = false
        mediaPaused = false
        mediaGuard = false
        pendingMediaPause = false
        mediaGateDrops = 0
    }

    private fun stopAllAudio(why: String) {
        ttsStopTimer.cancel()
        mediaReleaseTimer.cancel()
        voiceExitTimer.cancel()
        voice = false
        mediaGuard = false
        pendingMediaPause = false
        if (audioInited.values.any { it } || mediaPaused) log("BRIDGE останавливаю звук CarLife: $why", true)
        stopAudio(CarlifeAudio.CH_MEDIA)
        stopAudio(CarlifeAudio.CH_TTS)
        ttsNaviHeld = false
        if (naviSent && !routeNaviHeld) {
            naviSent = false
            link.sendModuleStatus(CarlifeAudio.MODULE_NAVI, 0)
            log("BRIDGE CarLife MODULE_STATUS NAVI=0", true)
        }
    }

    // --- метаданные AA ---
    @Synchronized
    override fun onMediaStatus(s: AaMediaStatus) = meta.onMediaStatus(s)

    @Synchronized
    override fun onMediaMetadata(m: AaMediaMetadata) = meta.onMediaMetadata(m)

    @Synchronized
    override fun onNavCluster(start: Boolean) = meta.onNavCluster(start)

    @Synchronized
    override fun onNavStatus(status: Int) = meta.onNavStatus(status)

    @Synchronized
    override fun onNavTurn(t: AaNavTurn) = meta.onNavTurn(t)

    @Synchronized
    override fun onNavDistance(d: AaNavDistance) = meta.onNavDistance(d)

    // --- видео AA ---
    @Synchronized
    override fun onCodecConfig(annexB: ByteArray) {
        aaVideoSeen = true
        aaConfig = AnnexB.normalize(annexB)
        log("BRIDGE AA CODEC_CONFIG ${annexB.size} Б nal=${AnnexB.nalTypes(aaConfig!!)}", true)
        if (carlifeStarted) transcoder?.feedConfig(aaConfig!!)
    }

    @Synchronized
    override fun onFrame(annexB: ByteArray, ptsUs: Long) {
        aaVideoSeen = true
        AaState.frames.incrementAndGet()
        AaState.bytes += annexB.size
        if (!carlifeStarted) {
            drop("CarLife ещё не прислал START")
            return
        }
        val b = AnnexB.normalize(annexB)
        val types = AnnexB.nalTypes(b)
        lastAaFrameAt = clockMs()
        aaFrames++
        val t = transcoder
        if (t != null) {
            feedTranscoder(t, b, types, ptsUs)
            return
        }
        if (!gotIdr) {
            if (5 !in types) {
                drop("P-кадр до IDR nal=$types")
                return
            }
            val payload = if (AnnexB.startsWithSps(b) && 8 in types) b else {
                val cfg = aaConfig
                if (cfg == null) {
                    drop("IDR без SPS/PPS и без CODEC_CONFIG")
                    return
                }
                AnnexB.glue(cfg, b)
            }
            val takeover = active != Source.AA
            if (!(if (takeover) link.sendVideoTakeover(payload) else link.sendVideo(payload))) {
                drop("первый IDR не принят CarLife (очередь/закрыто)")
                return
            }
            gotIdr = true
            waitSince = 0
            sent++
            idrSent++
            if (active != Source.AA) {
                active = Source.AA
                log("BRIDGE источник -> AA (первый IDR ${payload.size} Б, nal=${AnnexB.nalTypes(payload)})", true)
                link.hidePlaceholder()
            } else {
                log("BRIDGE IDR -> CarLife ${payload.size} Б nal=${AnnexB.nalTypes(payload)} (SPS/PPS ${if (payload.size != b.size) "склеены" else "в кадре"})", true)
            }
            AaState.status = "мост: AA -> CarLife"
            return
        }
        if (!link.sendVideo(b)) {
            // кадр потерян: декодер ГУ поплывёт, ждём новый IDR
            gotIdr = false
            waitSince = clockMs()
            drop("кадр не принят CarLife, жду новый IDR")
            return
        }
        sent++
        if (5 in types) idrSent++
        AaState.bridgeSent = sent
    }

    private fun drop(why: String) {
        dropped++
        AaState.bridgeDropped = dropped
        dropLogged++
        if (dropLogged <= 3 || dropLogged % 100 == 0L) log("BRIDGE отброшен AA-кадр (#$dropped): $why", false)
    }

    /** Раз в 0.5 с: ключевой кадр, если после START долго нет IDR. */
    @Synchronized
    fun tick() {
        logStats()
        if (!carlifeStarted || gotIdr || !aaVideoSeen) return
        val now = clockMs()
        if (waitSince == 0L) waitSince = now
        if (now - waitSince >= KEYFRAME_WAIT_MS && now - lastKeyframeRequest >= KEYFRAME_RETRY_MS) {
            lastKeyframeRequest = now
            keyframeRequests++
            log("BRIDGE жду IDR от AA ${(now - waitSince) / 1000.0} с: запрашиваю ключевой кадр (VideoFocus NATIVE -> PROJECTED) #$keyframeRequests", true)
            peer?.requestKeyFrame()
        }
    }

    // --- перекодирование ---
    private fun startTranscoder() {
        outGotIdr = false
        outputEnabled = false
        var t = transcoder
        if (t == null) {
            t = link.createTranscoder(this)
            transcoder = t
            if (t == null) {
                log("BRIDGE !!!!!! транскодер не поднялся (MediaCodec), откат на passthrough", true)
                return
            }
        }
        log("BRIDGE видео: режим перекодирование (AA -> декодер -> энкодер, повтор кадра 100 мс)", true)
        aaConfig?.let { t.feedConfig(it) }
        t.requestSyncFrame()        // энкодер: ключевой кадр на START, как только пойдут кадры
    }

    private fun stopTranscoderAsync() {
        val t = transcoder ?: return
        transcoder = null
        encCsd = null
        outGotIdr = false
        outputEnabled = false
        Thread({ t.stop() }, "aa-transcoder-stop").start()
    }

    private fun resetTranscodeForAa() {
        outputEnabled = false
        outGotIdr = false
        transcoder?.resetDecoder()
    }

    /** AA-кадр -> декодер. До первого IDR после START/пересоздания кадры отбрасываются (декодеру нужен IDR). */
    private fun feedTranscoder(t: VideoTranscoder, b: ByteArray, types: List<Int>, ptsUs: Long) {
        if (!gotIdr) {
            if (5 !in types) {
                drop("P-кадр до IDR (декодер ждёт) nal=$types")
                return
            }
            val payload = if (AnnexB.startsWithSps(b) && 8 in types) b else {
                val cfg = aaConfig
                if (cfg == null) {
                    drop("IDR без SPS/PPS и без CODEC_CONFIG")
                    return
                }
                AnnexB.glue(cfg, b)
            }
            gotIdr = true
            waitSince = 0
            outputEnabled = true
            log("BRIDGE AA IDR -> декодер (${payload.size} Б, nal=${AnnexB.nalTypes(payload)})", true)
            // переход с заглушки: энкодер начнёт с её кадра и растворит его в AA (до feedFrame, чтобы GL-нить взвела его раньше первого кадра)
            if (active != Source.AA) link.placeholderSnapshot()?.let { t.startIntro(it) }
            t.feedFrame(payload, ptsUs)
            t.requestSyncFrame()    // первый кадр энкодера после включения выхода сразу IDR, без ожидания до ~1 с
            return
        }
        t.feedFrame(b, ptsUs)
    }

    @Synchronized
    override fun onEncoderConfig(csd: ByteArray) {
        encCsd = csd
        log("BRIDGE энкодер: SPS+PPS ${csd.size} Б nal=${AnnexB.nalTypes(csd)}", false)
    }

    /** Кадр энкодера -> CarLife. Первое сообщение источника = SPS+PPS+IDR (SPS/PPS склеиваем), P до IDR выбрасываем. */
    @Synchronized
    override fun onEncoderFrame(data: ByteArray, key: Boolean) {
        if (!outputEnabled || !carlifeStarted) {
            encDropped++
            return
        }
        val types = AnnexB.nalTypes(data)
        val idr = 5 in types
        if (!outGotIdr) {
            if (!idr) {
                encDropped++
                return
            }
            val payload = if (AnnexB.startsWithSps(data) && 8 in types) data else {
                val cfg = encCsd
                if (cfg == null) {
                    encDropped++
                    return
                }
                AnnexB.glue(cfg, data)
            }
            val takeover = active != Source.AA
            if (!(if (takeover) link.sendVideoTakeover(payload) else link.sendVideo(payload))) {
                encDropped++
                requestIdrOnOverflow()
                return
            }
            outGotIdr = true
            sent++
            idrSent++
            if (active != Source.AA) {
                active = Source.AA
                log("BRIDGE источник -> AA (перекодирование, первый IDR ${payload.size} Б, nal=${AnnexB.nalTypes(payload)})", true)
                link.hidePlaceholder()
            } else {
                log("BRIDGE IDR энкодера -> CarLife ${payload.size} Б nal=${AnnexB.nalTypes(payload)}", true)
            }
            AaState.status = "мост: AA -> CarLife (перекодирование)"
            if (huVw20082138) link.onEncodedKeyFrame(payload.size)
            return
        }
        if (!link.sendVideo(data)) {
            // кадр не принят (очередь CarLife переполнена): дальше P нельзя, до следующего IDR всё выбрасывается выше
            outGotIdr = false
            encDropped++
            requestIdrOnOverflow()
            return
        }
        sent++
        if (idr) idrSent++
        AaState.bridgeSent = sent
        if (idr && huVw20082138) link.onEncodedKeyFrame(data.size)
    }

    /**
     * IDR у энкодера после отказа очереди CarLife - не чаще раза в 1.5 с (IdrThrottle). Иначе большие IDR (30-160 КБ)
     * сами забивают медленный канал. Отказали - ждём уже запрошенный или ближайший естественный IDR (интервал 1 с).
     */
    private fun requestIdrOnOverflow() {
        val t = transcoder ?: return
        if (!idrThrottle.tryRequest(clockMs())) return
        overflowIdrRequests++
        t.requestSyncFrame()
    }

    /** Битрейт энкодера на лету (адаптация под слив ГУ). false = транскодера нет (passthrough или ещё не создан). */
    @Synchronized
    fun setEncoderBitrate(bps: Int): Boolean {
        val t = transcoder ?: return false
        t.setBitrate(bps)
        return true
    }

    /** Целевой fps транскодера на лету (FRAME_RATE_CHANGE от ГУ). false = транскодера нет (passthrough или ещё не создан). */
    @Synchronized
    fun setTargetFps(fps: Int): Boolean {
        val t = transcoder ?: return false
        t.setTargetFps(fps)
        return true
    }

    @Synchronized
    override fun onNeedAaKeyFrame() {
        gotIdr = false
        waitSince = clockMs()
        log("BRIDGE декодер потерял кадр: жду новый IDR от AA", true)
    }

    @Synchronized
    override fun onTranscodeError(msg: String) {
        log("BRIDGE !!!!!! ошибка транскодера: $msg. Откат на passthrough", true)
        stopTranscoderAsync()
        gotIdr = false
        waitSince = clockMs()
    }

    /** ГУ просит ключевой кадр (VIDEO_ENCODER_RESET и т.п.). */
    @Synchronized
    fun onCarlifeKeyFrameRequest() {
        val t = transcoder
        if (t != null) {
            t.requestSyncFrame()
        } else if (carlifeStarted) {
            gotIdr = false
            waitSince = clockMs() - KEYFRAME_WAIT_MS
        }
        log("BRIDGE ГУ просит ключевой кадр (${if (t != null) "sync frame энкодера" else "ждём IDR от AA"})", true)
    }

    /** Раз в 5 с: режим, fps на выходе, статистика транскодера; в passthrough - длительность простоя AA (keepalive не шлём). */
    private fun logStats() {
        if (!carlifeStarted) return
        val now = clockMs()
        if (lastStatsAt == 0L) {
            lastStatsAt = now
            lastStatsSent = sent
            lastStatsAaFrames = aaFrames
            return
        }
        if (now - lastStatsAt < 5000) return
        val sec = (now - lastStatsAt) / 1000.0
        val fps = (sent - lastStatsSent) / sec
        val aaFps = (aaFrames - lastStatsAaFrames) / sec
        val t = transcoder
        val idle = if (lastAaFrameAt > 0 && now - lastAaFrameAt > 1000) " простой AA ${(now - lastAaFrameAt) / 1000.0} с" else ""
        val target = if (t != null) "цель ${t.targetFps} fps" else "fps не регулируется"
        val idrReq = overflowIdrRequests - lastStatsIdrRequests
        val ls = link.videoStats()
        val channel = if (ls == null) "" else
            "; канал: ${if (t != null) "энкодер ${t.currentBitrate / 1000} кбит/с, " else ""}слив %.0f КиБ/с, бэклог ${ls.backlogMs} мс, очередь ${ls.queueFrames} кадр/${ls.queueBytes / 1024} КБ, запросов IDR из-за переполнения $idrReq за окно"
                .format(ls.drainBytesPerSec / 1024)
        log(
            "BRIDGE видео[${if (t != null) "перекодирование" else "passthrough"}]: AA вход %.1f fps, выход в CarLife %.1f fps ($target), отброшено энкодер/гейт $encDropped/$dropped$idle".format(aaFps, fps) +
                channel + (if (t != null) "; ${t.statsLine()}" else ""), true
        )
        lastStatsAt = now
        lastStatsSent = sent
        lastStatsAaFrames = aaFrames
        lastStatsIdrRequests = overflowIdrRequests
    }

    companion object {
        /** action&255 TOUCH_ACTION, которые уходят в AA (у AA те же коды PointerAction). */
        private val TOUCH_NAMES = mapOf(
            AapMessages.TOUCH_DOWN to "DOWN", AapMessages.TOUCH_UP to "UP", AapMessages.TOUCH_MOVE to "MOVE",
            AapMessages.TOUCH_POINTER_DOWN to "POINTER_DOWN", AapMessages.TOUCH_POINTER_UP to "POINTER_UP",
        )

        // Флаги нового звукового поведения (по умолчанию включены): в машине отключаются по одному.
        /** MODULE_STATUS NAVI=1 перед первым TTS_INIT, NAVI=0 при остановке звука. */
        @Volatile @JvmStatic var NAVI_STATUS_ENABLED = true

        /** Debounce TTS_STOP (1500 мс) и очередь управляющих пакетов MEDIA до TTS_STOP+300 мс. */
        @Volatile @JvmStatic var TTS_DEBOUNCE_ENABLED = true

        /** Голосовая сессия Ассистента: MEDIA на паузу, MEDIA_DATA не пересылать, выход через 500 мс. */
        @Volatile @JvmStatic var VOICE_SESSION_ENABLED = true

        /** MODULE_STATUS MUSIC(3) вместе с MEDIA INIT/PAUSE/STOP и ответ на MODULE_CONTROL (CarLifeSession). */
        @Volatile @JvmStatic var MODULE_MUSIC_ENABLED = true

        /** onAudioStop(MEDIA) шлёт MEDIA_PAUSE (как MD) вместо MEDIA_STOP; выключено = старое поведение. */
        @Volatile @JvmStatic var MEDIA_PAUSE_ENABLED = true

        /**
         * MODULE_STATUS NAVI=1 на весь маршрут (MetaRelay): без него приборка Honda Vezel пишет «Turn by turn waiting
         * for your smartphone». Desay G6S (Mazda CX-5) с NAVI=1 глушит штатное радио при входе в CarLife (лог Ивана
         * 2026-10-12), там NAVI=1 только вокруг TTS (huDesayG6S). Выключено = NAVI=1 только вокруг TTS на всех ГУ.
         */
        @Volatile @JvmStatic var ROUTE_NAVI_STATUS_ENABLED = true

        /** ГУ ушло на свой источник (MODULE_CONTROL MUSIC=0): AA на паузу; на START play не шлём. */
        @Volatile @JvmStatic var HU_MUSIC_FOLLOW_ENABLED = true

        /** Desay G6S: MUSIC=1 после нашей паузы по MUSIC=0 - AA play (KEYCODE 126) и ответ MUSIC=1. */
        @Volatile @JvmStatic var HU_MUSIC_RESUME_ENABLED = true

        /** Сколько отвечать MUSIC=1 на пачку MUSIC=1 после нашего play, пока MEDIA INIT ещё не пришёл. */
        const val HU_RESUME_ANSWER_MS = 3_000L

        /**
         * Mazda CX-5: AA сам запускает музыку через 5-9 с после BT_HFP_CONNECTION. Пока ГУ на своём источнике, такой play
         * (в окне после HFP, без ввода с ГУ) паузу не снимает, а снова получает KEYCODE 127.
         */
        @Volatile @JvmStatic var BT_AUTOPLAY_GUARD_ENABLED = true

        const val BT_AUTOPLAY_WINDOW_MS = 20_000L
        const val USER_INPUT_WINDOW_MS = 3_000L
        private const val REPAUSE_MIN_MS = 1_000L

        const val TTS_STOP_DELAY_MS = 1500L
        const val MEDIA_AFTER_TTS_MS = 300L
        const val VOICE_EXIT_DELAY_MS = 500L
        private const val TTS_DIAG_PACKETS = 3

        /** Android KEYCODE_SEARCH: в AA запускает голосового ассистента. */
        const val KEYCODE_SEARCH = 84
        const val KEYCODE_MEDIA_PLAY = 126
        const val KEYCODE_MEDIA_PAUSE = 127

        /**
         * CarLife hard key (HUJ/CommonParams) -> Android KeyEvent; все значения есть в AapMessages.KEYCODES_SUPPORTED.
         * Громкость, HOME, MEDIA/RADIO/NAVI и цифры не мапим: их обрабатывает само ГУ либо аналога нет.
         */
        val HARD_KEY_TO_ANDROID: Map<Int, Int> = mapOf(
            0x10 to 87,     // SEEK_ADD -> MEDIA_NEXT
            0x0F to 88,     // SEEK_SUB -> MEDIA_PREVIOUS
            0x1F to 126,    // MEDIA_START -> MEDIA_PLAY
            0x20 to 127,    // MEDIA_STOP -> MEDIA_PAUSE
            0x02 to 5,      // PHONE_CALL -> CALL
            0x03 to 6,      // PHONE_END -> ENDCALL
            0x0E to 4,      // BACK
            0x14 to 23,     // OK -> DPAD_CENTER
            0x15 to 21,     // MOVE_LEFT -> DPAD_LEFT
            0x16 to 22,     // MOVE_RIGHT -> DPAD_RIGHT
            0x17 to 19,     // MOVE_UP -> DPAD_UP
            0x18 to 20,     // MOVE_DOWN -> DPAD_DOWN
            0x21 to KEYCODE_SEARCH, // VR_START -> голосовой ассистент
            0x1E to 3,      // MAIN -> HOME (панель приложений)
            0x09 to 209,    // MEDIA -> MUSIC (медиаплеер)
            0x0B to 65538,  // NAVI -> AA NAVIGATION (код из KEY_BINDING_REQUEST AA)
        )

        /** Поворот шайбы (Mazda Commander и т.п.) -> rotary AA: фокус по спискам, выбор — OK (DPAD_CENTER). */
        val HARD_KEY_TO_ROTARY: Map<Int, Int> = mapOf(
            0x06 to 1,      // SELECTOR_NEXT -> по часовой
            0x07 to -1,     // SELECTOR_PREVIOUS -> против часовой
        )

        const val TEXT_START_SERVER = "Запустите сервер ГУ в Android Auto"
        const val TEXT_WAIT_FRAME = "Android Auto: жду кадр..."
        const val TEXT_AA_LOST = "Android Auto отключился, перезапустите сервер ГУ"
        const val TEXT_STARTING_SERVER = "Запускаю сервер ГУ..."
        const val TEXT_RESTARTING_SERVER = "Android Auto отключился, перезапускаю сервер ГУ..."
        const val TEXT_UNLOCK_PHONE = "Разблокируйте телефон"
        const val TEXT_AA_CONSENT = "Завершите настройку Android Auto на телефоне"
        const val DESAY_MANUFACTURER = "Desay SV Automotive"
        const val DESAY_G6S_MODEL = "G6S"
        const val DESAY_G6S_CHANNEL = "20622116"          // STATISTIC_INFO.channel у Mazda CX-5; Vezel - 20832125, другая Mazda - 21520001
        const val VW_20082120_CHANNEL = "20082120"        // VW, protocol 1.0, versionName="RADIO" (h93); Tharu CNS3 - 20082122
        const val VW_20082138_CHANNEL = "20082138"        // VW ID (h196): HU_INFO manufacturer=model="VW BT 1538"
        const val VW_20082138_MANUFACTURER = "VW BT 1538"
        const val HYUNDAI_CN7_PRODUCT = "wp_cn7cpe_cn"    // HU_INFO.product Hyundai Elantra CN7 (h218): manufacturer QTI, model "sm6150_au for arm64"
        const val MEDIA_RATE = 48000
        const val MEDIA_RATE_CN7 = 32000
        const val KEYFRAME_WAIT_MS = 1500L
        const val KEYFRAME_RETRY_MS = 4000L
        private const val NEVER = Long.MIN_VALUE / 4
    }
}

/** Момент, когда коннектор просит подготовить сервер ГУ (автозапуск). */
enum class ServerPhase {
    /** До первой попытки подключения хоста. */
    FIRST,

    /** AA-сессия закончилась: сервер одноразовый, нужен новый. */
    AFTER_SESSION,

    /** Сервер недоступен несколько попыток подряд. */
    RETRY,
}

/**
 * Держит AA-сессию моста: подключается к серверу ГУ, при отказе (connection refused) повторяет раз в
 * 3 с, при завершении сессии сообщает ядру и переподключается. Сервер ГУ перезапускает пользователь или
 * serverPrep (автозапуск): блокирующий вызов на нити коннектора, возвращается, когда сервер готов или время вышло.
 */
class AaConnector(
    private val core: AaBridgeCore,
    private val creds: AapCredentials,
    private val log: (String, Boolean) -> Unit,
    private val options: () -> AapOptions = { AapOptions() },
    private val retryMs: Long = 3000,
    private val serverPrep: ((ServerPhase) -> Unit)? = null,
    private val sessionFactory: (AaVideoSink, AapOptions, (String, Boolean) -> Unit, (String, Boolean) -> Unit) -> AapSession =
        { sink, o, lg, end -> AapSession(creds, sink, o, lg, onEnd = end) },
) : AaLifecycle {
    @Volatile private var stopping = false
    @Volatile private var current: AapSession? = null
    private var thread: Thread? = null
    @Volatile var attempts = 0
        private set

    override fun start() {
        val t = Thread({ loop() }, "aa-connector")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** Корректно закрыть AA (ByeBye, EOF, close) и завершить нить; onDone вызывается из отдельной нити. */
    fun stop() = stop {}

    override fun stop(onDone: () -> Unit) {
        stopping = true
        current?.requestStop("мост остановлен")
        val t = thread
        Thread({
            try {
                t?.join(10_000)
            } catch (_: InterruptedException) {
            }
            onDone()
        }, "aa-connector-stop").start()
    }

    private fun prep(phase: ServerPhase) {
        val p = serverPrep ?: return
        try {
            p(phase)
        } catch (e: Throwable) {
            log("BRIDGE автозапуск сервера ($phase) упал: ${e.javaClass.simpleName}: ${e.message}", true)
        }
    }

    private fun loop() {
        var unavailableLogged = false
        var refusals = 0
        var lastRetryPrepAt = Long.MIN_VALUE / 4
        prep(ServerPhase.FIRST)
        if (stopping) return
        // решение о звуке зависит от FEATURE_CONFIG ГУ (BT-режим): ждём его до 2.5 с перед первым подключением
        if (core.usbAudioEnabled && core.expectFeatureConfig) {
            var waited = 0
            while (!core.featureConfigSeen && waited < 2500 && !stopping) {
                Thread.sleep(50)
                waited += 50
            }
            log("BRIDGE FEATURE_CONFIG ${if (core.featureConfigSeen) "получен" else "не пришёл за $waited мс"}, звук по USB: ${core.audioViaUsb}", true)
        }
        while (!stopping) {
            val ended = CountDownLatch(1)
            core.onAaConnecting()
            attempts++
            val s = sessionFactory(core, options(), log) { _, _ -> ended.countDown() }
            current = s
            core.peer = s
            s.start()
            while (!ended.await(500, TimeUnit.MILLISECONDS)) {
                if (stopping) s.requestStop("мост остановлен")
                core.tick()
            }
            core.peer = null
            current = null
            if (!s.connected) {
                refusals++
                if (!unavailableLogged) {
                    log("BRIDGE сервер ГУ Android Auto недоступен (connection refused), повторяю каждые ${retryMs / 1000.0} с", true)
                    unavailableLogged = true
                }
                AaState.status = "мост: жду сервер ГУ AA"
                // автозапуск мог упереться в блокировку экрана: пока сервера нет, периодически просим повтор
                val nowMs = System.nanoTime() / 1_000_000
                if (serverPrep != null && !stopping && refusals >= 2 && nowMs - lastRetryPrepAt >= RETRY_PREP_MS) {
                    lastRetryPrepAt = nowMs
                    prep(ServerPhase.RETRY)
                }
            } else {
                unavailableLogged = false
                refusals = 0
                log("BRIDGE AA-сессия завершена (established=${s.established}, video=${s.videoStarted})", true)
                AaState.status = "мост: AA отключился"
                core.onAaEnded(s.established)
                if (!stopping) prep(ServerPhase.AFTER_SESSION)
            }
            var left = retryMs
            while (left > 0 && !stopping) {
                Thread.sleep(minOf(left, 100))
                left -= 100
            }
        }
    }

    companion object {
        /** Не чаще раза в 5 с просить автозапуск повторить (он сам пропустит, если повторять нечего). */
        private const val RETRY_PREP_MS = 5_000L
    }
}
