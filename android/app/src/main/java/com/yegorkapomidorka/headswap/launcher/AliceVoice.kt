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

    private val h = Handler(Looper.getMainLooper())
    private var audio: AudioManager? = null
    private var running = false
    private var startedAt = 0L
    private var micSeen = false
    private var thinkingSince = 0L
    private var lastSpeechAt = 0L
    private var lastLoudAt = 0L
    private var routeUntil = 0L

    private val SPEECH_USAGES = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ASSISTANT, AudioAttributes.USAGE_UNKNOWN)
    private val MIC_SOURCES = setOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.DEFAULT)

    /** Кнопка голоса: Алиса запущена на скрытом дисплее. */
    fun start(ctx: Context) {
        audio = ctx.applicationContext.getSystemService(AudioManager::class.java)
        startedAt = SystemClock.uptimeMillis()
        micSeen = false
        lastSpeechAt = 0L
        set(Phase.LISTENING)
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
            when (phase) {
                Phase.HIDDEN -> {
                    if (!LauncherModel.assistantOpen) { running = false; return }
                    if (micOpen()) { micSeen = true; set(Phase.LISTENING) }
                }
                Phase.ROUTE -> if (now > routeUntil) set(Phase.HIDDEN)
                else -> {
                    val mic = micOpen()
                    // речь без звука в захвате (звук не по USB) тоже считаем речью
                    val speech = speechPlaying() && (!DesktopAudioState.running || now - lastLoudAt < LOUD_MS)
                    when {
                        mic -> { micSeen = true; set(Phase.LISTENING) }
                        speech -> { lastSpeechAt = now; set(Phase.SPEAKING) }
                        phase == Phase.LISTENING && micSeen -> { thinkingSince = now; set(Phase.THINKING) }
                        phase == Phase.LISTENING && now - startedAt > NO_MIC_MS -> set(Phase.HIDDEN)
                        phase == Phase.THINKING && now - thinkingSince > THINK_MS -> set(Phase.HIDDEN)
                        phase == Phase.SPEAKING && now - lastSpeechAt > SPEECH_TAIL_MS -> set(Phase.HIDDEN)
                    }
                }
            }
            // в HIDDEN тикаем, пока панель открыта: Алиса может сама снова открыть микрофон (уточняющий вопрос)
            h.postDelayed(this, TICK_MS)
        }
    }

    private fun micOpen(): Boolean = runCatching {
        audio?.activeRecordingConfigurations?.any { c ->
            c.clientAudioSource in MIC_SOURCES && !(Build.VERSION.SDK_INT >= 29 && c.isClientSilenced)
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
