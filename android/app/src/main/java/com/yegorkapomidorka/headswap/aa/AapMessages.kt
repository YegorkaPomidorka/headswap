package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.FieldDef
import com.yegorkapomidorka.headswap.Kind
import com.yegorkapomidorka.headswap.PbReader
import com.yegorkapomidorka.headswap.PbText
import com.yegorkapomidorka.headswap.Schema
import java.io.ByteArrayOutputStream

/** Protobuf (proto2) руками для AAP: varint, строки и вложенные сообщения; поля пишутся в порядке вызова. */
class AapPb {
    private val out = ByteArrayOutputStream()

    private fun raw(value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun tag(field: Int, wire: Int) = raw(((field shl 3) or wire).toLong())

    /** Любой varint-тип (int32/enum/bool/uint). Отрицательный Int расширяется знаком: 10 байт, как в protoc. */
    fun v(field: Int, value: Long): AapPb {
        tag(field, 0)
        raw(value)
        return this
    }

    fun v(field: Int, value: Int): AapPb = v(field, value.toLong())

    fun bool(field: Int, value: Boolean): AapPb = v(field, if (value) 1 else 0)

    fun bytes(field: Int, b: ByteArray): AapPb {
        tag(field, 2)
        raw(b.size.toLong())
        out.write(b)
        return this
    }

    fun str(field: Int, s: String): AapPb = bytes(field, s.toByteArray(Charsets.UTF_8))

    fun msg(field: Int, m: AapPb): AapPb = bytes(field, m.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Канал, тип и protobuf-байты исходящего сообщения. */
class AapOut(val chan: Int, val type: Int, val payload: ByteArray)

/** Параметры ServiceDiscoveryResponse; по умолчанию как в pc-headunit (доказано на AA 17.7). */
data class AapSdOptions(
    val resolution: String = "1280x720",
    val fps: Int = 30,
    val density: Int = 160,
    /** margin_width / margin_height VideoConfiguration: AA оставляет такие поля (пополам по краям), интерфейс только внутри. */
    val marginW: Int = 0,
    val marginH: Int = 0,
    /**
     * h529: поля целиком справа и снизу через ui_config.margins (поле 11 VideoConfiguration, Insets top/bottom/left/right),
     * margin_width = margin_height = 0. Интерфейс встаёт в левый верхний угол кадра: декодеры ГУ, которые режут только
     * справа/снизу (Vezel), покажут его ровно.
     */
    val marginsAtBottom: Boolean = false,
    val mic: Boolean = true,
    val vehicleType: Int? = null,
    val fullAudio: Boolean = false,
    val extraServices: Boolean = true,
    val vehicleId: String = "headlessunit-001",
    /** Марка автомобиля вместо «Google» (поле 2 и 17.1; производитель ГУ и модель не трогаем); null = по умолчанию. Эксперимент: логотип на кнопке выхода AA. */
    val carMake: String? = null,
)

object AapMessages {
    // --- каналы (headunit-revived Channel.kt) ---
    const val CH_CONTROL = 0
    const val CH_SENSOR = 1
    const val CH_VIDEO = 2
    const val CH_INPUT = 3
    const val CH_AUDIO_SPEECH = 4
    const val CH_AUDIO_SYSTEM = 5
    const val CH_AUDIO_MEDIA = 6
    const val CH_MIC = 7
    const val CH_BLUETOOTH = 8
    const val CH_MEDIA_PLAYBACK = 9
    const val CH_NAVIGATION = 10

    val AUDIO_CHANNELS = intArrayOf(CH_AUDIO_SPEECH, CH_AUDIO_SYSTEM, CH_AUDIO_MEDIA)

    private val CHANNEL_NAMES = mapOf(
        0 to "CONTROL", 1 to "SENSOR", 2 to "VIDEO", 3 to "INPUT", 4 to "AUDIO_SPEECH", 5 to "AUDIO_SYSTEM",
        6 to "AUDIO_MEDIA", 7 to "MIC", 8 to "BLUETOOTH", 9 to "MEDIA_PLAYBACK", 10 to "NAVIGATION",
    )

    fun channelName(chan: Int) = CHANNEL_NAMES[chan] ?: "CH$chan"

    // --- control (ControlMsgType) ---
    const val VERSION_REQUEST = 1
    const val VERSION_RESPONSE = 2
    const val ENCAPSULATED_SSL = 3
    const val AUTH_COMPLETE = 4
    const val SERVICE_DISCOVERY_REQUEST = 5
    const val SERVICE_DISCOVERY_RESPONSE = 6
    const val CHANNEL_OPEN_REQUEST = 7
    const val CHANNEL_OPEN_RESPONSE = 8
    const val CHANNEL_CLOSE_NOTIFICATION = 9
    const val PING_REQUEST = 11
    const val PING_RESPONSE = 12
    const val NAV_FOCUS_REQUEST = 13
    const val NAV_FOCUS_NOTIFICATION = 14
    const val BYEBYE_REQUEST = 15
    const val BYEBYE_RESPONSE = 16
    const val VOICE_SESSION_NOTIFICATION = 17
    const val AUDIO_FOCUS_REQUEST = 18
    const val AUDIO_FOCUS_NOTIFICATION = 19

    private val CONTROL_NAMES = mapOf(
        1 to "VERSION_REQUEST", 2 to "VERSION_RESPONSE", 3 to "ENCAPSULATED_SSL", 4 to "AUTH_COMPLETE",
        5 to "SERVICE_DISCOVERY_REQUEST", 6 to "SERVICE_DISCOVERY_RESPONSE", 7 to "CHANNEL_OPEN_REQUEST",
        8 to "CHANNEL_OPEN_RESPONSE", 9 to "CHANNEL_CLOSE_NOTIFICATION", 11 to "PING_REQUEST",
        12 to "PING_RESPONSE", 13 to "NAV_FOCUS_REQUEST", 14 to "NAV_FOCUS_NOTIFICATION",
        15 to "BYEBYE_REQUEST", 16 to "BYEBYE_RESPONSE", 17 to "VOICE_SESSION_NOTIFICATION",
        18 to "AUDIO_FOCUS_REQUEST", 19 to "AUDIO_FOCUS_NOTIFICATION", 20 to "CAR_CONNECTED_DEVICES_REQUEST",
        21 to "CAR_CONNECTED_DEVICES_RESPONSE", 22 to "USER_SWITCH_REQUEST", 23 to "BATTERY_STATUS_NOTIFICATION",
        24 to "CALL_AVAILABILITY_STATUS", 25 to "USER_SWITCH_RESPONSE", 26 to "SERVICE_DISCOVERY_UPDATE",
        0x00FF to "UNEXPECTED_MESSAGE", 0xFFFF to "FRAMING_ERROR",
    )

    // --- media (video/audio/mic) ---
    const val MEDIA_DATA = 0x0000
    const val MEDIA_CODEC_CONFIG = 0x0001
    const val MEDIA_SETUP = 0x8000
    const val MEDIA_START = 0x8001
    const val MEDIA_STOP = 0x8002
    const val MEDIA_CONFIG = 0x8003
    const val MEDIA_ACK = 0x8004
    const val MEDIA_MIC_REQUEST = 0x8005
    const val MEDIA_MIC_RESPONSE = 0x8006
    const val MEDIA_VIDEO_FOCUS_REQUEST = 0x8007
    const val MEDIA_VIDEO_FOCUS_NOTIFICATION = 0x8008

    private val MEDIA_NAMES = mapOf(
        0x0000 to "MEDIA_DATA", 0x0001 to "MEDIA_CODEC_CONFIG", 0x8000 to "MEDIA_SETUP", 0x8001 to "MEDIA_START",
        0x8002 to "MEDIA_STOP", 0x8003 to "MEDIA_CONFIG", 0x8004 to "MEDIA_ACK", 0x8005 to "MIC_REQUEST",
        0x8006 to "MIC_RESPONSE", 0x8007 to "VIDEO_FOCUS_REQUEST", 0x8008 to "VIDEO_FOCUS_NOTIFICATION",
        0x8009 to "UPDATE_UI_CONFIG_REQUEST", 0x800A to "UPDATE_UI_CONFIG_REPLY", 0x800B to "AUDIO_UNDERFLOW",
    )

    // --- sensor / input (те же 0x80xx, смысл по каналу) ---
    const val SENSOR_START_REQUEST = 0x8001
    const val SENSOR_START_RESPONSE = 0x8002
    const val SENSOR_EVENT = 0x8003
    const val INPUT_EVENT = 0x8001
    const val INPUT_BINDING_REQUEST = 0x8002
    const val INPUT_BINDING_RESPONSE = 0x8003

    private val PLAYBACK_NAMES = mapOf(0x8001 to "MEDIA_PLAYBACK_STATUS", 0x8002 to "MEDIA_PLAYBACK_INPUT", 0x8003 to "MEDIA_PLAYBACK_METADATA")
    private val NAV_NAMES = mapOf(
        0x8001 to "INSTRUMENT_CLUSTER_START", 0x8002 to "INSTRUMENT_CLUSTER_STOP", 0x8003 to "NAVIGATION_STATUS",
        0x8004 to "NAVIGATION_TURN_EVENT", 0x8005 to "NAVIGATION_DISTANCE_EVENT", 0x8006 to "NAVIGATION_STATE",
        0x8007 to "NAVIGATION_CURRENT_POSITION",
    )

    /** Статус плеера (ch9), состояние/дистанция/позиция навигации (ch10) идут примерно раз в секунду: в UI-лог не пишем (смену трека и манёвра громко пишет MetaRelay). */
    fun isPeriodicMeta(chan: Int, type: Int) =
        (chan == CH_MEDIA_PLAYBACK && type == AaMeta.MEDIA_STATUS) ||
            (chan == CH_NAVIGATION && (type == AaMeta.NAV_DISTANCE || type == AaMeta.NAV_STATE || type == AaMeta.NAV_POSITION))

    private val SENSOR_NAMES = mapOf(0x8001 to "SENSOR_START_REQUEST", 0x8002 to "SENSOR_START_RESPONSE", 0x8003 to "SENSOR_EVENT")
    private val INPUT_NAMES = mapOf(0x8001 to "INPUT_EVENT", 0x8002 to "KEY_BINDING_REQUEST", 0x8003 to "BINDING_RESPONSE")

    private fun isMediaChan(chan: Int) = chan == CH_VIDEO || chan == CH_MIC || chan in AUDIO_CHANNELS

    fun messageName(chan: Int, type: Int): String {
        if (type in CONTROL_NAMES && (type <= 26 || type == 0x00FF || type == 0xFFFF)) {
            // 1 на медиа-каналах = CODEC_CONFIG
            if (!(type == 1 && isMediaChan(chan))) return CONTROL_NAMES.getValue(type)
        }
        if (chan == CH_SENSOR) SENSOR_NAMES[type]?.let { return it }
        if (chan == CH_INPUT) INPUT_NAMES[type]?.let { return it }
        if (chan == CH_MEDIA_PLAYBACK) PLAYBACK_NAMES[type]?.let { return it }
        if (chan == CH_NAVIGATION) NAV_NAMES[type]?.let { return it }
        if (chan == CH_MEDIA_PLAYBACK || chan == CH_NAVIGATION) return "UNKNOWN_0x%04x".format(type)
        MEDIA_NAMES[type]?.let { return it }
        return "UNKNOWN_0x%04x".format(type)
    }

    // --- сырые (нешифрованные) сообщения ---
    const val VERSION_MAJOR = 1
    const val VERSION_MINOR = 2

    /** [type=1][major=1][minor=2]; в кадре: 00 03 00 06 00 01 00 01 00 02. */
    fun versionRequestBody(): ByteArray =
        byteArrayOf(0, VERSION_REQUEST.toByte(), 0, VERSION_MAJOR.toByte(), 0, VERSION_MINOR.toByte())

    /** AuthResponse{status = 0}; в кадре: 00 03 00 04 00 04 08 00. */
    val AUTH_COMPLETE_DATA = byteArrayOf(0x08, 0x00)

    class VersionResponse(val major: Int, val minor: Int, val status: Int, val options: ByteArray)

    /** Тело после type: major u16, minor u16, status i16, опции. */
    fun parseVersionResponse(body: ByteArray): VersionResponse? {
        if (body.size < 6) return null
        fun u16(i: Int) = ((body[i].toInt() and 0xFF) shl 8) or (body[i + 1].toInt() and 0xFF)
        return VersionResponse(u16(0), u16(2), u16(4).toShort().toInt(), body.copyOfRange(6, body.size))
    }

    // --- билдеры: что шлёт головное устройство ---
    fun channelOpenResponse(chan: Int) = AapOut(chan, CHANNEL_OPEN_RESPONSE, AapPb().v(1, 0).toByteArray())

    fun pingResponse(timestamp: Long) = AapOut(CH_CONTROL, PING_RESPONSE, AapPb().v(1, timestamp).toByteArray())

    const val NAV_FOCUS_PROJECTED = 2

    fun navFocusNotification(chan: Int = CH_CONTROL) =
        AapOut(chan, NAV_FOCUS_NOTIFICATION, AapPb().v(1, NAV_FOCUS_PROJECTED).toByteArray())

    /** Всегда выдаём фокус: AapControl.kt:450-455. request: 1 GAIN, 2 TRANSIENT, 3 MAY_DUCK, 4 RELEASE. */
    fun audioFocusResponse(request: Int, chan: Int = CH_CONTROL): AapOut? {
        val state = when (request) {
            1 -> 1      // STATE_GAIN
            2 -> 2      // STATE_GAIN_TRANSIENT
            3 -> 7      // STATE_GAIN_TRANSIENT_GUIDANCE_ONLY
            4 -> 3      // STATE_LOSS
            else -> return null
        }
        return AapOut(chan, AUDIO_FOCUS_NOTIFICATION, AapPb().v(1, state).toByteArray())
    }

    /** Сразу после Config на аудио-канале (AapControl.kt:123-131). */
    fun audioFocusGainUnsolicited() =
        AapOut(CH_CONTROL, AUDIO_FOCUS_NOTIFICATION, AapPb().v(1, 1).bool(2, true).toByteArray())

    const val BYE_USER_SELECTION = 1

    fun byebyeRequest(reason: Int = BYE_USER_SELECTION) = AapOut(CH_CONTROL, BYEBYE_REQUEST, AapPb().v(1, reason).toByteArray())

    fun byebyeResponse() = AapOut(CH_CONTROL, BYEBYE_RESPONSE, ByteArray(0))

    fun mediaConfig(chan: Int, maxUnacked: Int) =
        AapOut(chan, MEDIA_CONFIG, AapPb().v(1, 2).v(2, maxUnacked).v(3, 0).toByteArray())     // STATUS_READY, index 0

    fun videoFocusNotification(projected: Boolean = true, unsolicited: Boolean = true) =
        AapOut(CH_VIDEO, MEDIA_VIDEO_FOCUS_NOTIFICATION, AapPb().v(1, if (projected) 1 else 2).bool(2, unsolicited).toByteArray())

    fun mediaAck(chan: Int, sessionId: Int) = AapOut(chan, MEDIA_ACK, AapPb().v(1, sessionId).v(2, 1).toByteArray())

    fun micResponse(success: Boolean, sessionId: Int = 0) =
        AapOut(CH_MIC, MEDIA_MIC_RESPONSE, AapPb().v(1, if (success) 0 else -7).v(2, sessionId).toByteArray())

    /** DATA микрофона HU -> телефон: канал MIC, type 0, [timestamp u64 BE мкс][PCM16 mono 16 кГц]; кадр шифрованный, флаги 0x0b. */
    fun micData(tsUs: Long, pcm: ByteArray) = AapOut(CH_MIC, MEDIA_DATA, AaMicFormat.dataPayload(tsUs, pcm))

    fun bindingResponse(chan: Int = CH_INPUT) = AapOut(chan, INPUT_BINDING_RESPONSE, AapPb().v(1, 0).toByteArray())

    fun sensorStartResponse(chan: Int = CH_SENSOR) = AapOut(chan, SENSOR_START_RESPONSE, AapPb().v(1, 0).toByteArray())

    /** SensorBatch{driving_status=[{status}]}; 0 = UNRESTRICTED. */
    fun drivingStatusEvent(status: Int = 0) =
        AapOut(CH_SENSOR, SENSOR_EVENT, AapPb().msg(13, AapPb().v(1, status)).toByteArray())

    /** SensorBatch{night_mode=[{is_night_mode}]}. */
    fun nightModeEvent(night: Boolean = false) =
        AapOut(CH_SENSOR, SENSOR_EVENT, AapPb().msg(10, AapPb().bool(1, night)).toByteArray())

    const val TOUCH_DOWN = 0
    const val TOUCH_UP = 1
    const val TOUCH_MOVE = 2
    const val TOUCH_POINTER_DOWN = 5
    const val TOUCH_POINTER_UP = 6

    /** Один палец: InputReport{timestamp ns, touch_event{pointer, action_index 0, action}}. */
    fun touchEvent(action: Int, x: Int, y: Int, timestampNs: Long, pointerId: Int = 0): AapOut =
        touchEvent(action, 0, listOf(TouchPoint(pointerId, x, y)), timestampNs)

    /**
     * InputReport{timestamp ns, touch_event{pointer x N, action_index, action}}: все активные пальцы в одном событии,
     * action_index = позиция в [points] пальца, вызвавшего POINTER_DOWN/UP (как MotionEvent.getActionIndex).
     */
    fun touchEvent(action: Int, actionIndex: Int, points: List<TouchPoint>, timestampNs: Long): AapOut {
        val touch = AapPb()
        for (p in points) touch.msg(1, AapPb().v(1, p.x).v(2, p.y).v(3, p.id))
        touch.v(2, actionIndex).v(3, action)
        return AapOut(CH_INPUT, INPUT_EVENT, AapPb().v(1, timestampNs).msg(3, touch).toByteArray())
    }

    /** InputReport{timestamp ns, key_event{keys=[{keycode, down, metastate 0, longpress false}]}}. */
    fun keyEvent(timestampNs: Long, keycode: Int, down: Boolean): AapOut {
        val key = AapPb().v(1, keycode).bool(2, down).v(3, 0).bool(4, false)
        return AapOut(CH_INPUT, INPUT_EVENT, AapPb().v(1, timestampNs).msg(4, AapPb().msg(1, key)).toByteArray())
    }

    /** Android KEYCODE_ROTARY_CONTROLLER (AA): шайба, двигает фокус по элементам интерфейса. */
    const val KEYCODE_ROTARY_CONTROLLER = 65536

    /** InputReport{timestamp ns, relative_event{data=[{keycode ROTARY_CONTROLLER, delta}]}}; delta > 0 по часовой. */
    fun rotaryEvent(timestampNs: Long, delta: Int): AapOut {
        val rel = AapPb().v(1, KEYCODE_ROTARY_CONTROLLER).v(2, delta)
        return AapOut(CH_INPUT, INPUT_EVENT, AapPb().v(1, timestampNs).msg(6, AapPb().msg(1, rel)).toByteArray())
    }

    // --- ServiceDiscoveryResponse ---
    /** KeyCode.supported из headunit-revived (input/KeyCode.kt:9-61), номера Android KeyEvent. */
    val KEYCODES_SUPPORTED: List<Int> = listOf(
        1, 2, 4, 19, 20, 21, 22, 23, 126, 127, 85, 87, 88, 84, 5, 209, 24, 25, 61, 62, 66, 3, 79, 86,
        6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
        81, 224, 264, 265, 267, 268, 269, 270, 271, 65536, 65537, 65538,
    ).toSortedSet().toList()

    const val VEHICLE_TYPE_CAR = 1
    const val VEHICLE_TYPE_MOTORCYCLE = 3

    private const val MEDIA_CODEC_AUDIO_PCM = 1
    private const val MEDIA_CODEC_VIDEO_H264_BP = 3
    private const val AUDIO_NONE = 0
    private const val AUDIO_SYSTEM = 2
    private const val AUDIO_SPEECH = 1
    private const val AUDIO_MEDIA = 3
    private const val SENSOR_NIGHT = 10
    private const val SENSOR_DRIVING_STATUS = 13

    private fun audioCfg(rate: Int, channels: Int) = AapPb().v(1, rate).v(2, 16).v(3, channels)

    fun serviceDiscoveryResponse(o: AapSdOptions = AapSdOptions()): AapOut {
        val vehicleType = o.vehicleType ?: if (o.mic) VEHICLE_TYPE_CAR else VEHICLE_TYPE_MOTORCYCLE
        val carMake = o.carMake?.takeIf { it.isNotBlank() }
        var vehicleId = if (vehicleType == VEHICLE_TYPE_MOTORCYCLE) o.vehicleId + "-moto" else o.vehicleId
        // другой vehicleId - AA заводит новый профиль машины, а не берёт закешированный с «Google»
        if (carMake != null) vehicleId += "-" + carMake.lowercase().filter { it.isLetterOrDigit() }
        val make = carMake ?: "Google"
        val (width, height) = o.resolution.split("x").map { it.toInt() }
        val resCode = when (o.resolution) {
            "800x480" -> 1
            "1280x720" -> 2
            "1920x1080" -> 3
            "2560x1440" -> 4
            else -> throw IllegalArgumentException("неизвестное разрешение ${o.resolution}")
        }
        val r = AapPb()

        // sensor
        r.msg(1, AapPb().v(1, CH_SENSOR).msg(2, AapPb()
            .msg(1, AapPb().v(1, SENSOR_DRIVING_STATUS))
            .msg(1, AapPb().v(1, SENSOR_NIGHT))))

        // video
        val legacy = !o.marginsAtBottom
        val vc = AapPb().v(1, resCode).v(2, if (o.fps == 30) 2 else 1).v(3, if (legacy) o.marginW else 0).v(4, if (legacy) o.marginH else 0)
            .v(5, o.density).v(8, 10000).v(10, MEDIA_CODEC_VIDEO_H264_BP)
        if (o.marginsAtBottom && (o.marginW > 0 || o.marginH > 0)) {
            val insets = AapPb().v(1, 0).v(2, o.marginH)
            if (o.marginW > 0) insets.v(3, 0).v(4, o.marginW)
            vc.msg(11, AapPb().msg(1, insets))
        }
        r.msg(1, AapPb().v(1, CH_VIDEO).msg(3, AapPb()
            .v(1, MEDIA_CODEC_VIDEO_H264_BP).v(2, AUDIO_NONE).msg(4, vc).bool(5, true)))

        // input
        val input = AapPb()
        for (k in KEYCODES_SUPPORTED) input.v(1, k)
        input.msg(2, AapPb().v(1, width).v(2, height))
        r.msg(1, AapPb().v(1, CH_INPUT).msg(4, input))

        fun audioSink(chan: Int, stream: Int, rate: Int, channels: Int) {
            r.msg(1, AapPb().v(1, chan).msg(3, AapPb()
                .v(1, MEDIA_CODEC_AUDIO_PCM).v(2, stream).msg(3, audioCfg(rate, channels))))
        }
        audioSink(CH_AUDIO_SYSTEM, AUDIO_SYSTEM, 16000, 1)
        if (o.fullAudio) {
            audioSink(CH_AUDIO_SPEECH, AUDIO_SPEECH, 16000, 1)
            audioSink(CH_AUDIO_MEDIA, AUDIO_MEDIA, 48000, 2)
        }

        if (o.mic) {
            r.msg(1, AapPb().v(1, CH_MIC).msg(5, AapPb().v(1, MEDIA_CODEC_AUDIO_PCM).msg(2, audioCfg(16000, 1))))
        }

        if (o.extraServices) {
            r.msg(1, AapPb().v(1, CH_MEDIA_PLAYBACK).msg(9, AapPb()))
            r.msg(1, AapPb().v(1, CH_NAVIGATION).msg(8, AapPb().v(1, 1000).v(2, 2)))    // ImageCodesOnly
        }

        r.str(2, make).str(3, "Desktop Head Unit").str(4, "2025").str(5, vehicleId)
            .v(6, 0)                                                // DRIVER_POSITION_LEFT
            .str(7, "Google").str(8, "Desktop Head Unit").str(9, "1").str(10, "0.1.0")
            .bool(11, false).bool(12, false).v(13, 0).str(14, carMake ?: "Open Headunit")   // подпись плитки выхода в лаунчере AA
        r.msg(17, AapPb()
            .str(1, make).str(2, "Desktop Head Unit").str(3, "2025").str(4, vehicleId)
            .str(5, "Google").str(6, "Desktop Head Unit").str(7, "1").str(8, "0.1.0").v(9, vehicleType))
        return AapOut(CH_CONTROL, SERVICE_DISCOVERY_RESPONSE, r.toByteArray())
    }

    // --- разбор входящих (для логики и лога) ---
    private fun s(name: String, vararg f: Pair<Int, FieldDef>) = Schema(name, f.toMap())
    private fun i(n: String) = FieldDef(n, Kind.INT)
    private fun b(n: String) = FieldDef(n, Kind.BOOL)
    private fun t(n: String) = FieldDef(n, Kind.STR)
    private fun y(n: String) = FieldDef(n, Kind.BYTES)
    private fun m(n: String, sub: Schema) = FieldDef(n, Kind.MSG, sub)

    private val SCHEMAS: Map<Int, Schema> = mapOf(
        SERVICE_DISCOVERY_REQUEST to s("ServiceDiscoveryRequest", 4 to t("phone_name"), 5 to t("phone_brand")),
        CHANNEL_OPEN_REQUEST to s("ChannelOpenRequest", 1 to i("priority"), 2 to i("service_id")),
        CHANNEL_OPEN_RESPONSE to s("ChannelOpenResponse", 1 to i("status")),
        PING_REQUEST to s("PingRequest", 1 to i("timestamp"), 2 to i("bug_report")),
        PING_RESPONSE to s("PingResponse", 1 to i("timestamp")),
        NAV_FOCUS_REQUEST to s("NavFocusRequest", 1 to i("focus_type")),
        NAV_FOCUS_NOTIFICATION to s("NavFocusNotification", 1 to i("focus_type")),
        BYEBYE_REQUEST to s("ByeByeRequest", 1 to i("reason")),
        VOICE_SESSION_NOTIFICATION to s("VoiceSession", 1 to i("status")),
        AUDIO_FOCUS_REQUEST to s("AudioFocusRequest", 1 to i("request")),
        AUDIO_FOCUS_NOTIFICATION to s("AudioFocusNotification", 1 to i("focus_state"), 2 to b("unsolicited")),
        23 to s("BatteryStatus", 1 to i("battery_level"), 2 to i("time_remaining_s"), 3 to b("critical_battery")),
        24 to s("CallAvailability", 1 to b("call_available")),
    )
    private val MEDIA_SCHEMAS: Map<Int, Schema> = mapOf(
        MEDIA_SETUP to s("MediaSetupRequest", 1 to i("type")),
        MEDIA_START to s("Start", 1 to i("session_id"), 2 to i("configuration_index")),
        MEDIA_CONFIG to s("Config", 1 to i("status"), 2 to i("max_unacked"), 3 to i("configuration_indices")),
        MEDIA_ACK to s("Ack", 1 to i("session_id"), 2 to i("ack"), 3 to i("receive_timestamp_ns")),
        MEDIA_MIC_REQUEST to s("MicrophoneRequest", 1 to b("open"), 2 to b("anc_enabled"), 3 to b("ec_enabled"), 4 to i("max_unacked")),
        MEDIA_MIC_RESPONSE to s("MicrophoneResponse", 1 to i("status"), 2 to i("session_id")),
        MEDIA_VIDEO_FOCUS_REQUEST to s("VideoFocusRequest", 1 to i("disp_channel_id"), 2 to i("mode"), 3 to i("reason")),
        MEDIA_VIDEO_FOCUS_NOTIFICATION to s("VideoFocusNotification", 1 to i("mode"), 2 to b("unsolicited")),
    )
    private val SENSOR_SCHEMAS: Map<Int, Schema> = mapOf(
        SENSOR_START_REQUEST to s("SensorRequest", 1 to i("type"), 2 to i("min_update_period")),
        SENSOR_START_RESPONSE to s("SensorResponse", 1 to i("status")),
    )
    private val INPUT_SCHEMAS: Map<Int, Schema> = mapOf(
        INPUT_BINDING_REQUEST to s("KeyBindingRequest", 1 to i("keycodes")),
        INPUT_BINDING_RESPONSE to s("BindingResponse", 1 to i("status")),
    )

    // ch9 / ch10 (aasdk mediaplayback / navigationstatus)
    private val PLAYBACK_SCHEMAS: Map<Int, Schema> = mapOf(
        AaMeta.MEDIA_STATUS to s(
            "MediaPlaybackStatus", 1 to i("state"), 2 to t("media_source"), 3 to i("playback_seconds"),
            4 to b("shuffle"), 5 to b("repeat"), 6 to b("repeat_one"),
        ),
        AaMeta.MEDIA_METADATA to s(
            "MediaPlaybackMetadata", 1 to t("song"), 2 to t("artist"), 3 to t("album"), 4 to y("album_art"),
            5 to t("playlist"), 6 to i("duration_seconds"), 7 to i("rating"),
        ),
    )
    private val NAV_DISTANCE = s("NavigationDistance", 1 to i("meters"), 2 to t("display_value"), 3 to i("display_units"))
    private val NAV_ROAD = s("NavigationRoad", 1 to t("name"))
    private val NAV_STEP = s(
        "NavigationStep",
        1 to m("maneuver", s("NavigationManeuver", 1 to i("type"), 2 to i("roundabout_exit_number"), 3 to i("roundabout_exit_angle"))),
        2 to m("road", NAV_ROAD),
        3 to m("lanes", s("NavigationLane", 1 to m("lane_directions", s("LaneDirection", 1 to i("shape"), 2 to b("is_highlighted"))))),
        4 to m("cue", s("NavigationCue", 1 to t("alternate_text"))),
    )
    private val NAV_SCHEMAS: Map<Int, Schema> = mapOf(
        AaMeta.NAV_STATUS to s("NavigationStatus", 1 to i("status")),
        AaMeta.NAV_TURN to s(
            "NextTurnEvent", 1 to t("road"), 2 to i("turn_side"), 3 to i("event"), 4 to y("image"),
            5 to i("turn_number"), 6 to i("turn_angle"),
        ),
        AaMeta.NAV_DISTANCE to s(
            "NextTurnDistanceEvent", 1 to i("distance_meters"), 2 to i("time_to_turn_seconds"),
            3 to i("display_distance_e3"), 4 to i("display_distance_unit"),
        ),
        AaMeta.NAV_STATE to s(
            "NavigationState", 1 to m("steps", NAV_STEP), 2 to m("destinations", s("NavigationDestination", 1 to t("address"))),
        ),
        AaMeta.NAV_POSITION to s(
            "NavigationCurrentPosition",
            1 to m("step_distance", s("NavigationStepDistance", 1 to m("distance", NAV_DISTANCE), 2 to i("time_to_step_seconds"))),
            2 to m("destination_distances", s(
                "NavigationDestinationDistance", 1 to m("distance", NAV_DISTANCE), 2 to t("estimated_time_at_arrival"),
                3 to i("time_to_arrival_seconds"),
            )),
            3 to m("current_road", NAV_ROAD),
        ),
    )

    /** Одна строка: протобуф-тело с именами полей, если схема известна, иначе hex-префикс. */
    fun describe(chan: Int, type: Int, payload: ByteArray, maxLen: Int = 600): String {
        val schema = when {
            type == MEDIA_CODEC_CONFIG && isMediaChan(chan) -> null
            type <= 26 && !(type == 1 && isMediaChan(chan)) -> SCHEMAS[type]
            chan == CH_SENSOR -> SENSOR_SCHEMAS[type]
            chan == CH_INPUT -> INPUT_SCHEMAS[type]
            chan == CH_MEDIA_PLAYBACK -> PLAYBACK_SCHEMAS[type]
            chan == CH_NAVIGATION -> NAV_SCHEMAS[type]
            else -> MEDIA_SCHEMAS[type]
        }
        val text = if (schema != null || (payload.isNotEmpty() && PbReader.parse(payload) != null && type != MEDIA_DATA && type != MEDIA_CODEC_CONFIG)) {
            PbText.render(payload, schema)
        } else {
            payload.take(32).joinToString(" ") { "%02x".format(it) } + if (payload.size > 32) " ..." else ""
        }
        return if (text.length <= maxLen) text else text.substring(0, maxLen) + "..."
    }
}
