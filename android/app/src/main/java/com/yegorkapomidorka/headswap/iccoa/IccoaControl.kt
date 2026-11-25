// Derived from FT (github.com/OneAboveAll1964/ft-app), Apache-2.0:
// IccoaHeartbeat.kt, IccoaPortNegotiation.kt, IccoaCarConfig.kt, IccoaAudioPlayerState.kt,
// тело m6 из IccoaMicrophone.kt.
// Изменено для Headswap: один файл, PbWriter проекта, номера методов и m12 по таблице XDL; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import com.yegorkapomidorka.headswap.PbReader
import com.yegorkapomidorka.headswap.PbWriter
import java.io.Closeable

/** Методы cat 1 (control, порт 57219) и тела простых сообщений. */
object IccoaControl {
    const val HEARTBEAT = 1
    const val GET_PORT_REQUEST = 2
    const val GET_PORT_RESPONSE = 3
    const val CAR_FOREGROUND = 4
    const val CAR_BACKGROUND = 5
    const val MICROPHONE = 6
    const val AUDIO_PLAYER_STATE = 8
    const val PHONE_STATE = 9
    const val MUSIC_INFO = 10
    const val NAVIGATION_INFO = 11
    const val KEY_EVENT = 12
    const val DAY_NIGHT = 15
    const val DISCONNECT = 23
    const val CONFIG_REQUEST = 24
    const val CONFIG_RESPONSE = 25

    /** Кнопки голоса на руле в m12. */
    const val KEY_VOICE = 8107
    const val KEY_VOICE_ALT = 8108

    fun heartbeat(nowMs: Long = System.currentTimeMillis()): ByteArray = PbWriter().int64(1, nowMs).toByteArray()

    /** m6: микрофон машины, PCM16 моно 16 кГц придёт в cat 4 method 9. */
    fun microphone(on: Boolean): ByteArray = PbWriter().int32(1, if (on) 1 else 0).int32(2, 16000)
        .int32(3, 1).int32(4, 2).toByteArray()

    data class Key(val up: Boolean, val keyCode: Int)

    /** m12 от машины: f1 = 1 — отпускание, f2 — Android keycode. По спеке XDL, у FT разбора нет. */
    fun key(body: ByteArray): Key? {
        val fields = PbReader.parse(body) ?: return null
        val code = PbReader.int(fields, 2) ?: return null
        return Key(PbReader.int(fields, 1) == 1, code)
    }
}

/** Heartbeat m1 каждые 2 с. Таймер даёт сессия; первый кадр уходит сразу, как у эталона. */
class IccoaHeartbeat(
    private val send: (ByteArray) -> Boolean,
    private val schedule: (Long, () -> Unit) -> Unit
) : Closeable {
    private var started = false
    private var closed = false

    @Synchronized fun start() {
        if (started || closed) return
        started = true
        tick()
    }

    @Synchronized private fun tick() {
        if (closed) return
        if (!send(IccoaControl.heartbeat())) {
            closed = true
            return
        }
        schedule(2_000, ::tick)
    }

    @Synchronized override fun close() { closed = true }
}

/** get_port m2 → m3. Отдаём только порт датчиков: остальные типы (AUDIO=2, UIBC=3) у FT не подтверждены. */
object IccoaPortNegotiation {
    const val SENSOR = 1

    fun responseFor(request: ByteArray): ByteArray? {
        // Ровно `08 01`: обрезанный или задвоенный запрос порт не получает.
        if (!request.contentEquals(byteArrayOf(0x08, 0x01))) return null
        return PbWriter().int32(1, SENSOR).int32(2, IccoaFrame.SENSOR_PORT).toByteArray()
    }
}

/** Конфиг машины m25. Строки и блобы (имя, MAC) наружу не отдаём — только известные числа и форму. */
object IccoaCarConfig {
    private val modes = listOf("normal cast", "data transfer", "app fullscreen", "app window",
        "card cast", "file transfer", "multi-screen", "multi-device", "mirror window",
        "launcher window", "share network")

    /** -1 — поля не было. */
    data class Config(val workModes: Long, val poi: Long, val rotation: Long, val home: Long,
                      val back: Long, val hevc: Long, val dpi: Long, val shape: String)

    fun parse(body: ByteArray): Config {
        require(body.size <= 16_384) { "Oversized car config" }
        val fields = IccoaFrame.Fields(body)
        return Config(fields.number(18, -1), fields.number(19, -1), fields.number(20, -1),
            fields.number(21, -1), fields.number(22, -1), fields.number(23, -1), fields.number(24, -1),
            fields.shape().take(512))
    }

    /** Строка для лога. */
    fun describe(body: ByteArray): String {
        val config = parse(body)
        val result = mutableListOf<String>()
        if (config.workModes >= 0) {
            val names = modes.filterIndexed { bit, _ -> config.workModes and (1L shl bit) != 0L }.toMutableList()
            val unknown = config.workModes and 2047L.inv()
            if (unknown != 0L) names += "unknown 0x${unknown.toString(16)}"
            result += "workModes=${config.workModes} (${names.joinToString().ifEmpty { "none" }})"
        }
        for ((value, name) in listOf(config.poi to "poi", config.rotation to "rotation", config.home to "home",
            config.back to "back", config.hevc to "hevc", config.dpi to "dpi")) {
            if (value >= 0) result += "$name=$value"
        }
        result += "shape=${config.shape}"
        return result.joinToString("; ")
    }
}

/** Состояние плеера для m8. Пока control недоступен, помнит только последнее желаемое состояние. Один поток. */
class IccoaAudioPlayerState(private val stream: Int = STREAM_MUSIC) {
    private var playing: Boolean? = null
    private var queuedPlaying: Boolean? = null
    val pending: Boolean get() = playing != null && playing != queuedPlaying

    fun play() { playing = true }
    fun pause() { if (playing != null) playing = false }
    fun reset() { playing = null; queuedPlaying = null }

    /** Шлёт m8, если состояние изменилось. Возвращает отправленный state (0 start, 2 pause, 3 resume) или null. */
    fun flush(send: (ByteArray) -> Boolean): Int? {
        if (!pending) return null
        val desired = playing!!
        val state = if (!desired) 2 else if (queuedPlaying == null) 0 else 3
        val body = PbWriter().int32(1, stream).int32(2, state).int32(3, 48000)
            .int32(4, 2).int32(5, 2).string(6, "audio/mp4a-latm").toByteArray()
        if (!send(body)) return null
        queuedPlaying = desired
        return state
    }

    companion object {
        const val STREAM_MUSIC = 16
        const val STREAM_TTS = 6
    }
}
