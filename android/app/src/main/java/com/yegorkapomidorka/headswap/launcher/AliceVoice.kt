package com.yegorkapomidorka.headswap.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.dex.DesktopAudioState

/**
 * Что делает скрытая Алиса - для плашки снизу по центру ([AliceVoicePill]). Окно Яндекса живёт на скрытом дисплее, поэтому
 * состояние берём со стороны звука, без Яндекса:
 * - «Я вас слушаю» - открыт микрофон (getActiveRecordingConfigurations: источник MIC / VOICE_RECOGNITION, наш захват
 *   рабочего стола - REMOTE_SUBMIX/микс, не считается);
 * - «Говорю» - играет речь (USAGE_MEDIA/ASSISTANT + CONTENT_TYPE_SPEECH, стенд 07.11) и захват слышит звук;
 * - «Думаю...» - микрофон закрылся, речи ещё нет;
 * - «Маршрут в навигаторе» - маршрут ушёл в плитку, несколько секунд.
 * Главный поток.
 */
object AliceVoice {
    enum class Phase { HIDDEN, LISTENING, THINKING, SPEAKING, ROUTE }

    var phase by mutableStateOf(Phase.HIDDEN)
        private set

    /** Левая панель навигатора (варианты маршрута, карточка манёвра), px области: плашка встаёт по центру карты. */
    @Volatile var navLeftPx = 0

    private const val TICK_MS = 120L
    private const val NO_MIC_MS = 6_000L      // микрофон так и не открылся - убираем
    private const val THINK_MS = 12_000L      // ответа нет - убираем
    private const val SPEECH_TAIL_MS = 1_500L // тишина после речи - убираем (новый вопрос снова откроет микрофон)
    private const val LOUD_MS = 600L
    private const val LOUD = 0.02f
    private const val ROUTE_MS = 5_000L

    /**
     * Дольше этого открытый микрофон - не вопрос, а фоновое прослушивание Яндекса (после ответа с музыкой он держит MIC
     * минутами, стенд 07.11: плашка висела на «Я вас слушаю»). Такую сессию дальше не считаем.
     */
    private const val LISTEN_MAX_MS = 10_000L

    private val h = Handler(Looper.getMainLooper())
    private var audio: AudioManager? = null
    private var running = false
    private var startedAt = 0L
    /** Нажали «Голос», микрофон ещё не открылся: плашку не показываем, пока нет джингла (Егор 07.11). */
    private var pending = false
    private var micSeen = false
    private var thinkingSince = 0L
    private var lastSpeechAt = 0L
    private var lastLoudAt = 0L
    private var routeUntil = 0L
    /** Сессии микрофона, которые не вопрос: были открыты до нажатия или висят дольше [LISTEN_MAX_MS]. */
    private val ignored = HashSet<Int>()
    private val firstSeen = HashMap<Int, Long>()

    private val SPEECH_USAGES = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ASSISTANT, AudioAttributes.USAGE_UNKNOWN)
    private val MIC_SOURCES = setOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.DEFAULT)

    /** Помощник плашки - Gemini (значок и цвета Gemini), иначе Алиса. */
    var gemini by mutableStateOf(false)
        private set

    /** Кнопка голоса: Алиса или Gemini ([isGemini]) запущены на скрытом дисплее. */
    fun start(ctx: Context, isGemini: Boolean = false) {
        gemini = isGemini
        audio = ctx.applicationContext.getSystemService(AudioManager::class.java)
        startedAt = SystemClock.uptimeMillis()
        micSeen = false
        lastSpeechAt = 0L
        ignored.clear(); firstSeen.clear()
        ignored.addAll(micSessions())
        pending = true
        set(Phase.HIDDEN)
        if (!running) { running = true; h.post(tick) }
    }

    /** Маршрут от Алисы ушёл в навигатор. */
    fun route() {
        routeUntil = SystemClock.uptimeMillis() + ROUTE_MS
        set(Phase.ROUTE)
        if (!running) { running = true; h.post(tick) }
    }

    /** Панель Алисы закрыта (тап по плашке, «Назад», таймер). */
    fun stop() {
        if (phase != Phase.ROUTE) set(Phase.HIDDEN)
    }

    private fun set(p: Phase) {
        if (phase == p) return
        AppLog.i("ASSIST плашка: $phase -> $p", false)
        phase = p
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (DesktopAudioState.level > LOUD) lastLoudAt = now
            val mic = micOpen(now)
            when (phase) {
                Phase.HIDDEN -> {
                    if (!pending && !LauncherModel.assistantOpen) { running = false; return }
                    // микрофон мигает на доли секунды сразу после нажатия (стенд 07.11): ждём, что он держится
                    if (mic && now - micSince >= MIC_STEADY_MS) { pending = false; micSeen = true; set(Phase.LISTENING) }
                    else if (pending && now - startedAt > NO_MIC_MS) pending = false
                }
                Phase.ROUTE -> if (now > routeUntil) set(Phase.HIDDEN)
                else -> {
                    // речь без звука в захвате (звук не по USB) тоже считаем речью
                    val speech = speechPlaying() && (!DesktopAudioState.running || now - lastLoudAt < LOUD_MS)
                    when {
                        // Алиса включила музыку - разговор кончился, фоновый микрофон не вопрос
                        phase == Phase.LISTENING && lastSpeechAt > 0 && musicPlaying() -> {
                            ignored.addAll(micSessions()); set(Phase.HIDDEN)
                        }
                        mic -> { micSeen = true; set(Phase.LISTENING) }
                        speech -> { lastSpeechAt = now; set(Phase.SPEAKING) }
                        phase == Phase.LISTENING && micSeen && expired -> set(Phase.HIDDEN)
                        phase == Phase.LISTENING && micSeen -> { thinkingSince = now; set(Phase.THINKING) }
                        phase == Phase.THINKING && now - thinkingSince > THINK_MS -> set(Phase.HIDDEN)
                        phase == Phase.SPEAKING && now - lastSpeechAt > SPEECH_TAIL_MS -> set(Phase.HIDDEN)
                    }
                }
            }
            expired = false
            // в HIDDEN тикаем, пока панель открыта: Алиса может сама снова открыть микрофон (уточняющий вопрос)
            h.postDelayed(this, TICK_MS)
        }
    }

    /** Только что выкинули сессию по [LISTEN_MAX_MS]: из «Я вас слушаю» - прятать, а не «Думаю...». */
    private var expired = false
    /** С какого момента открыт самый старый подходящий микрофон (для [MIC_STEADY_MS]). */
    private var micSince = 0L
    private const val MIC_STEADY_MS = 400L

    private fun micSessions(): Set<Int> = runCatching {
        audio?.activeRecordingConfigurations?.filter { c ->
            c.clientAudioSource in MIC_SOURCES && !(Build.VERSION.SDK_INT >= 29 && c.isClientSilenced)
        }?.map { it.clientAudioSessionId }?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())

    /** Открыт микрофон под вопрос: не из [ignored] и не дольше [LISTEN_MAX_MS]. */
    private fun micOpen(now: Long): Boolean {
        val open = micSessions()
        firstSeen.keys.retainAll(open)
        ignored.retainAll(open)
        var any = false
        var oldest = now
        for (s in open) {
            if (s in ignored) continue
            val since = firstSeen.getOrPut(s) { now }
            if (now - since > LISTEN_MAX_MS) {
                AppLog.i("ASSIST плашка: микрофон $s открыт дольше ${LISTEN_MAX_MS / 1000} с - фоновый, не считаем", false)
                ignored.add(s); expired = true; continue
            }
            any = true
            if (since < oldest) oldest = since
        }
        micSince = oldest
        return any
    }

    /** Играет не речь (музыка, видео): USAGE_MEDIA без CONTENT_TYPE_SPEECH. */
    private fun musicPlaying(): Boolean = runCatching {
        audio?.activePlaybackConfigurations?.any { c ->
            val a = c.audioAttributes
            a.usage == AudioAttributes.USAGE_MEDIA && a.contentType != AudioAttributes.CONTENT_TYPE_SPEECH && started(c)
        } ?: false
    }.getOrDefault(false)

    private fun speechPlaying(): Boolean = runCatching {
        audio?.activePlaybackConfigurations?.any { c ->
            val a = c.audioAttributes
            a.contentType == AudioAttributes.CONTENT_TYPE_SPEECH && a.usage in SPEECH_USAGES && started(c)
        } ?: false
    }.getOrDefault(false)

    /** AudioPlaybackConfiguration.getPlayerState() скрыт; главный процесс без проверок скрытого API. Не вышло - считаем играющим. */
    private fun started(c: AudioPlaybackConfiguration): Boolean = runCatching {
        AudioPlaybackConfiguration::class.java.getMethod("getPlayerState").invoke(c) as Int == 2 // PLAYER_STATE_STARTED
    }.getOrDefault(true)
}
