package com.yegorkapomidorka.headswap

import com.yegorkapomidorka.headswap.aa.CarlifeMediaInfo
import com.yegorkapomidorka.headswap.aa.NaviField6
import com.yegorkapomidorka.headswap.aa.NaviTurnInfo
import java.io.ByteArrayOutputStream

/** Каналы внешнего заголовка (спека §2.2). */
object Ch {
    const val CMD = 1
    const val VIDEO = 2
    const val MEDIA = 3
    const val TTS = 4
    const val VR = 5
    const val TOUCH = 6

    fun name(c: Int) = when (c) {
        CMD -> "CMD"
        VIDEO -> "VIDEO"
        MEDIA -> "MEDIA"
        TTS -> "TTS"
        VR -> "VR"
        TOUCH -> "TOUCH"
        else -> "CH$c"
    }

    /** Внутренний заголовок: 8 байт (CMD, TOUCH) или 12 (остальные). */
    fun isCmdStyle(c: Int) = c == CMD || c == TOUCH
}

/** Коды сообщений (спека §3, §5, §6, §7). */
object Msg {
    const val HU_PROTOCOL_VERSION = 0x00018001
    const val PROTOCOL_VERSION_MATCH_STATUS = 0x00010002
    const val HU_INFO = 0x00018003
    const val MD_INFO = 0x00010004
    const val HU_BT_OOB_INFO = 0x00018005
    const val MD_BT_OOB_INFO = 0x00010006
    const val VIDEO_ENCODER_INIT = 0x00018007
    const val VIDEO_ENCODER_INIT_DONE = 0x00010008
    const val VIDEO_ENCODER_START = 0x00018009
    const val VIDEO_ENCODER_PAUSE = 0x0001800A
    const val VIDEO_ENCODER_RESET = 0x0001800B
    const val VIDEO_ENCODER_FRAME_RATE_CHANGE = 0x0001800C
    const val VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE = 0x0001000D
    const val SCREEN_ON = 0x00010018
    const val FOREGROUND = 0x0001001B
    const val LAUNCH_MODE_NORMAL = 0x0001801D
    const val GO_TO_DESKTOP = 0x00010021
    const val MIC_RECORD_WAKEUP_START = 0x00010022
    const val MIC_RECORD_END = 0x00010023
    const val MIC_RECORD_RECOG_START = 0x00010024
    const val MODULE_STATUS = 0x00010026
    const val STATISTIC_INFO = 0x00018027
    const val MODULE_CONTROL = 0x00018028
    const val GEAR_INFO = 0x00010029
    const val NAVI_NEXT_TURN_INFO = 0x00010030
    const val MEDIA_INFO = 0x00010035
    const val MEDIA_PROGRESS_BAR = 0x00010036
    const val CAR_DATA_SUBSCRIBE = 0x00010031
    const val CONNECT_EXCEPTION = 0x00010037
    const val BT_HFP_CONNECTION = 0x00018042
    const val CARLIFE_DATA_SUBSCRIBE = 0x00018043
    const val CARLIFE_DATA_SUBSCRIBE_DONE = 0x00010044
    const val CARLIFE_DATA_SUBSCRIBE_START = 0x00018045
    const val CARLIFE_DATA_SUBSCRIBE_STOP = 0x00018046
    const val HU_AUTHEN_REQUEST = 0x00018048
    const val MD_AUTHEN_RESPONSE = 0x00010049
    const val HU_AUTHEN_RESULT = 0x0001804A
    const val MD_AUTHEN_RESULT = 0x0001004B
    const val MD_FEATURE_CONFIG_REQUEST = 0x00010051
    const val HU_FEATURE_CONFIG_RESPONSE = 0x00018052
    const val ERROR_CODE = 0x00018055
    const val VIDEO_ENCODER_JPEG = 0x00018056
    const val VIDEO_ENCODER_JPEG_ACK = 0x00010057
    const val MD_EXIT = 0x00010059
    const val CHANGE_MD_THEME = 0x00018078
    const val REQUEST_MD_THEME_STATE = 0x00018079
    const val RESPONSE_MD_THEME_STATE = 0x00010080

    const val VIDEO_DATA = 0x00020001
    const val VIDEO_HEARTBEAT = 0x00020002

    const val MEDIA_INIT = 0x00030001
    const val TTS_INIT = 0x00040001
    const val VR_INIT = 0x00050002

    /** HU -> MD по каналу VR: PCM16 mono 16 кГц с микрофона ГУ (Baidu CommonParams.MSG_VR_DATA = 360449). */
    const val VR_MIC_DATA = 0x00058001

    const val TOUCH_ACTION = 0x00068001
    const val TOUCH_ACTION_DOWN = 0x00068002
    const val TOUCH_ACTION_UP = 0x00068003
    const val TOUCH_ACTION_MOVE = 0x00068004
    const val TOUCH_SINGLE_CLICK = 0x00068005
    const val TOUCH_DOUBLE_CLICK = 0x00068006
    const val TOUCH_LONG_PRESS = 0x00068007
    const val TOUCH_CAR_HARD_KEY_CODE = 0x00068008
    const val TOUCH_UI_ACTION_SOUND = 0x00060009
    const val TOUCH_ACTION_BEGIN = 0x0006800A
    const val TOUCH_ACTION_POINTER_DOWN = 0x0006800B
    const val TOUCH_ACTION_POINTER_UP = 0x0006800C
    const val TOUCH_ACTION_OTHERPOINTER_UP = 0x0006800D

    private val names: Map<Int, String> = mapOf(
        HU_PROTOCOL_VERSION to "HU_PROTOCOL_VERSION",
        PROTOCOL_VERSION_MATCH_STATUS to "PROTOCOL_VERSION_MATCH_STATUS",
        HU_INFO to "HU_INFO",
        MD_INFO to "MD_INFO",
        HU_BT_OOB_INFO to "HU_BT_OOB_INFO",
        MD_BT_OOB_INFO to "MD_BT_OOB_INFO",
        VIDEO_ENCODER_INIT to "VIDEO_ENCODER_INIT",
        VIDEO_ENCODER_INIT_DONE to "VIDEO_ENCODER_INIT_DONE",
        VIDEO_ENCODER_START to "VIDEO_ENCODER_START",
        VIDEO_ENCODER_PAUSE to "VIDEO_ENCODER_PAUSE",
        VIDEO_ENCODER_RESET to "VIDEO_ENCODER_RESET",
        VIDEO_ENCODER_FRAME_RATE_CHANGE to "VIDEO_ENCODER_FRAME_RATE_CHANGE",
        VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE to "VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE",
        0x0001800E to "PAUSE_MEDIA",
        0x0001800F to "CAR_VELOCITY",
        0x00018010 to "CAR_GPS",
        0x00018011 to "CAR_GYROSCOPE",
        0x00018012 to "CAR_ACCELERATION",
        0x00018013 to "CAR_OIL",
        0x00010014 to "TELE_STATE_CHANGE_INCOMING",
        0x00010015 to "TELE_STATE_CHANGE_OUTGOING",
        0x00010016 to "TELE_STATE_CHANGE_IDLE",
        0x00010017 to "TELE_STATE_CHANGE_INCALLING",
        0x00010018 to "SCREEN_ON",
        0x00010019 to "SCREEN_OFF",
        0x0001001A to "SCREEN_USERPRESENT",
        FOREGROUND to "FOREGROUND",
        0x0001001C to "BACKGROUND",
        LAUNCH_MODE_NORMAL to "LAUNCH_MODE_NORMAL",
        0x0001801E to "LAUNCH_MODE_PHONE",
        0x0001801F to "LAUNCH_MODE_MAP",
        0x00018020 to "LAUNCH_MODE_MUSIC",
        GO_TO_DESKTOP to "GO_TO_DESKTOP",
        MIC_RECORD_WAKEUP_START to "MIC_RECORD_WAKEUP_START",
        MIC_RECORD_END to "MIC_RECORD_END",
        MIC_RECORD_RECOG_START to "MIC_RECORD_RECOG_START",
        0x00018025 to "GO_TO_FOREGROUND",
        MODULE_STATUS to "MODULE_STATUS",
        STATISTIC_INFO to "STATISTIC_INFO",
        MODULE_CONTROL to "MODULE_CONTROL",
        GEAR_INFO to "GEAR_INFO",
        NAVI_NEXT_TURN_INFO to "NAVI_NEXT_TURN_INFO",
        0x00010031 to "CAR_DATA_SUBSCRIBE",
        0x00010032 to "CAR_DATA_SUBSCRIBE_DONE(C++)",
        0x00018032 to "CAR_DATA_SUBSCRIBE_RSP(Java)",
        0x00010033 to "CAR_DATA_START",
        0x00010034 to "CAR_DATA_STOP",
        MEDIA_INFO to "MEDIA_INFO",
        MEDIA_PROGRESS_BAR to "MEDIA_PROGRESS_BAR",
        CONNECT_EXCEPTION to "CONNECT_EXCEPTION",
        0x00010038 to "REQUEST_GO_TO_FOREGROUND",
        0x00010039 to "UI_ACTION_SOUND",
        0x00010040 to "BT_HFP_REQUEST",
        0x00018041 to "BT_HFP_INDICATION",
        0x00018042 to "BT_HFP_CONNECTION",
        0x00018043 to "CARLIFE_DATA_SUBSCRIBE",
        0x00010044 to "CARLIFE_DATA_SUBSCRIBE_DONE",
        0x00018045 to "CARLIFE_DATA_SUBSCRIBE_START",
        0x00018046 to "CARLIFE_DATA_SUBSCRIBE_STOP",
        0x00010047 to "NAVI_ASSITANTGUIDE_INFO",
        HU_AUTHEN_REQUEST to "HU_AUTHEN_REQUEST",
        MD_AUTHEN_RESPONSE to "MD_AUTHEN_RESPONSE",
        HU_AUTHEN_RESULT to "HU_AUTHEN_RESULT",
        MD_AUTHEN_RESULT to "MD_AUTHEN_RESULT",
        0x0001004C to "GO_TO_FOREGROUND_RESPONSE",
        0x0001004D to "START_BT_AUTOPAIR_REQUEST",
        0x0001804E to "BT_HFP_RESPONSE",
        0x0001004F to "BT_HFP_STATUS_REQUEST",
        0x00018050 to "BT_HFP_STATUS_RESPONSE",
        MD_FEATURE_CONFIG_REQUEST to "MD_FEATURE_CONFIG_REQUEST",
        HU_FEATURE_CONFIG_RESPONSE to "HU_FEATURE_CONFIG_RESPONSE",
        0x00018053 to "BT_START_IDENTIFY_REQ",
        0x00010054 to "BT_IDENTIFY_RESULT_IND",
        ERROR_CODE to "ERROR_CODE",
        0x00018056 to "VIDEO_ENCODER_JPEG",
        0x00010057 to "VIDEO_ENCODER_JPEG_ACK",
        0x00010058 to "BT_HFP_CALL_STATUS_COVER",
        MD_EXIT to "MD_EXIT",
        0x0001006A to "MD_RSA_PUBLIC_KEY_REQUEST",
        0x0001806B to "HU_RSA_PUBLIC_KEY_RESPONSE",
        0x0001006C to "MD_AES_KEY_SEND_REQUEST",
        0x0001806D to "HU_AES_REC_RESPONSE",
        0x0001006E to "MD_ENCRYPT_READY",
        0x00010060 to "TIME_SYNC",
        0x00018060 to "HU_CONNECT_STATISTIC",
        CHANGE_MD_THEME to "CHANGE_MD_THEME",
        REQUEST_MD_THEME_STATE to "REQUEST_MD_THEME_STATE",
        RESPONSE_MD_THEME_STATE to "RESPONSE_MD_THEME_STATE",
        0x00018080 to "HU_ACTIVE_INFO",
        0x00010081 to "MD_ACTIVE_DONE",
        0x00018081 to "REQUEST_HU_THEME_STATE",
        0x00010082 to "RESPONSE_HU_THEME_STATE",
        0x00018083 to "HU_CERTIFICATE",
        0x00018090 to "HU_PROJECTION_LIMIT",
        VIDEO_DATA to "VIDEO_DATA",
        VIDEO_HEARTBEAT to "VIDEO_HEARTBEAT",
        MEDIA_INIT to "MEDIA_INIT",
        0x00030002 to "MEDIA_STOP",
        0x00030003 to "MEDIA_PAUSE",
        0x00030004 to "MEDIA_RESUME_PLAY",
        0x00030005 to "MEDIA_SEEK_TO",
        0x00030006 to "MEDIA_DATA",
        TTS_INIT to "TTS_INIT",
        0x00040002 to "TTS_STOP",
        0x00040003 to "TTS_DATA",
        VR_INIT to "VR_INIT",
        0x00050003 to "VR_DATA",
        0x00050004 to "VR_STOP",
        0x00050006 to "VR_INTERRUPT",
        VR_MIC_DATA to "VR_MIC_DATA",
        TOUCH_ACTION to "TOUCH_ACTION",
        TOUCH_ACTION_DOWN to "TOUCH_ACTION_DOWN",
        TOUCH_ACTION_UP to "TOUCH_ACTION_UP",
        TOUCH_ACTION_MOVE to "TOUCH_ACTION_MOVE",
        TOUCH_SINGLE_CLICK to "TOUCH_SINGLE_CLICK",
        TOUCH_DOUBLE_CLICK to "TOUCH_DOUBLE_CLICK",
        TOUCH_LONG_PRESS to "TOUCH_LONG_PRESS",
        TOUCH_CAR_HARD_KEY_CODE to "TOUCH_CAR_HARD_KEY_CODE",
        TOUCH_UI_ACTION_SOUND to "TOUCH_UI_ACTION_SOUND",
        TOUCH_ACTION_BEGIN to "TOUCH_ACTION_BEGIN",
        TOUCH_ACTION_POINTER_DOWN to "TOUCH_ACTION_POINTER_DOWN",
        TOUCH_ACTION_POINTER_UP to "TOUCH_ACTION_POINTER_UP",
        TOUCH_ACTION_OTHERPOINTER_UP to "TOUCH_ACTION_OTHERPOINTER_UP",
    )

    fun name(type: Int): String = names[type] ?: "UNKNOWN"

    fun hex(type: Int): String = "0x%08X".format(type)
}

/** Разобранное CMD/TOUCH сообщение: заголовок 8 байт [len u16][resv u16][type u32] + body. */
class CmdMsg(val type: Int, val resv: Int, val declaredLen: Int, val body: ByteArray, val truncated: Boolean)

/** Разобранное VIDEO/MEDIA/TTS/VR сообщение: заголовок 12 байт [len u32][time u32][type u32] + data. */
class DataMsg(val type: Int, val time: Int, val declaredLen: Int, val data: ByteArray, val truncated: Boolean)

object Wire {
    /** Максимум одного куска bulk-записи (спека §1.4). */
    const val CHUNK = 16384
    const val MAX_OUTER_LEN = 64 * 1024 * 1024

    fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    fun u32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 8).toByte()
        b[o + 1] = v.toByte()
    }

    fun putU32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 24).toByte()
        b[o + 1] = (v shr 16).toByte()
        b[o + 2] = (v shr 8).toByte()
        b[o + 3] = v.toByte()
    }

    /** Внешний заголовок [chan u32 BE][len u32 BE]. */
    fun outerHeader(chan: Int, innerLen: Int): ByteArray {
        val h = ByteArray(8)
        putU32(h, 0, chan)
        putU32(h, 4, innerLen)
        return h
    }

    /** Внутреннее CMD/TOUCH сообщение: [len u16][0 u16][type u32] + body. */
    fun cmdInner(type: Int, body: ByteArray?): ByteArray {
        val n = body?.size ?: 0
        require(n <= 0xFFFF) { "CMD body too long: $n" }
        val m = ByteArray(8 + n)
        putU16(m, 0, n)
        putU32(m, 4, type)
        if (body != null) System.arraycopy(body, 0, m, 8, n)
        return m
    }

    /** Внутреннее VIDEO/... сообщение: [len u32][time u32][type u32] + data. */
    fun dataInner(type: Int, time: Int, data: ByteArray?): ByteArray {
        val n = data?.size ?: 0
        val m = ByteArray(12 + n)
        putU32(m, 0, n)
        putU32(m, 4, time)
        putU32(m, 8, type)
        if (data != null) System.arraycopy(data, 0, m, 12, n)
        return m
    }

    /**
     * Последовательность отдельных write(): сначала 8-байтный заголовок, затем тело кусками <= 16384
     * (так делает HU Baidu и на это рассчитан f_accessory, спека §1.4).
     */
    fun writeSequence(chan: Int, inner: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        out.add(outerHeader(chan, inner.size))
        var p = 0
        while (p < inner.size) {
            val n = minOf(CHUNK, inner.size - p)
            out.add(inner.copyOfRange(p, p + n))
            p += n
        }
        return out
    }

    fun parseCmd(inner: ByteArray): CmdMsg? {
        if (inner.size < 8) return null
        val len = u16(inner, 0)
        val resv = u16(inner, 2)
        val type = u32(inner, 4)
        val avail = inner.size - 8
        val n = minOf(len, avail)
        return CmdMsg(type, resv, len, inner.copyOfRange(8, 8 + n), len > avail)
    }

    fun parseData(inner: ByteArray): DataMsg? {
        if (inner.size < 12) return null
        val len = u32(inner, 0)
        val time = u32(inner, 4)
        val type = u32(inner, 8)
        val avail = inner.size - 12
        val n = if (len < 0) avail else minOf(len, avail)
        return DataMsg(type, time, len, inner.copyOfRange(12, 12 + n), len < 0 || len > avail)
    }
}

class ProtocolDesync(msg: String) : Exception(msg)

/**
 * Накопительный разбор внешнего заголовка: читаемые из USB куски могут резаться как угодно,
 * сообщения собираются из потока байт, границы transfer не важны.
 */
class FrameParser {
    private var buf = ByteArray(32768)
    private var start = 0
    private var end = 0

    /** Подать очередной кусок; sink вызывается на каждое полное сообщение. */
    fun feed(data: ByteArray, off: Int, n: Int, sink: (chan: Int, inner: ByteArray) -> Unit) {
        ensure(n)
        System.arraycopy(data, off, buf, end, n)
        end += n
        while (true) {
            val have = end - start
            if (have < 8) break
            val chan = Wire.u32(buf, start)
            val len = Wire.u32(buf, start + 4)
            if (chan < 0 || chan > 0xFF || len < 0 || len > Wire.MAX_OUTER_LEN) {
                throw ProtocolDesync("плохой внешний заголовок: chan=$chan len=$len, ${Hex.compact(buf.copyOfRange(start, start + minOf(have, 16)), 16)}")
            }
            if (have < 8 + len) break
            val inner = buf.copyOfRange(start + 8, start + 8 + len)
            start += 8 + len
            sink(chan, inner)
        }
        if (start == end) {
            start = 0
            end = 0
        }
    }

    /** Байт в буфере, ещё не составивших целое сообщение. */
    fun pending() = end - start

    private fun ensure(n: Int) {
        if (end + n <= buf.size) return
        val have = end - start
        if (have + n <= buf.size) {
            System.arraycopy(buf, start, buf, 0, have)
        } else {
            val nb = ByteArray(maxOf(buf.size * 2, have + n))
            System.arraycopy(buf, start, nb, 0, have)
            buf = nb
        }
        start = 0
        end = have
    }
}

/**
 * Сборка inner-сообщений по каналам. Часть ГУ (VW Tharu XR 2026) шлёт заголовок inner (8 байт для CMD/TOUCH,
 * 12 для data-каналов) и тело отдельными внешними кадрами. Если кадр - ровно заголовок с declared len > 0,
 * он удерживается, и кадры этого же канала дописываются, пока не наберётся declared len; потом уходит одно inner.
 * Тот же ГУ режет поток и в другую сторону: кадр = хвост тела + следующие inner целиком + заголовок следующего
 * (154 байта: конец MODULE_CONTROL, HU_AUTHEN_RESULT, STATISTIC_INFO). Поэтому после удержания канал разбирается
 * как поток по declared len, а кадр без удержания режется на несколько inner, если он целиком складывается
 * в цепочку заголовков с правильным типом (старшие 16 бит типа = номер канала).
 * Сообщения целиком в одном кадре (Honda Vezel) проходят как есть, в том числе с неверным declared len.
 */
class InnerAssembler {
    private val pending = HashMap<Int, ByteArray>()

    fun feed(chan: Int, inner: ByteArray, sink: (chan: Int, inner: ByteArray) -> Unit) {
        val hdrLen = hdrLen(chan)
        val p = pending.remove(chan)
        if (p != null) {
            drain(chan, p + inner, sink)
            return
        }
        if (inner.size >= hdrLen && headerOk(chan, inner, 0)) {
            val declared = declared(chan, inner, 0)
            if (inner.size == hdrLen && declared > 0) {
                pending[chan] = inner
                return
            }
            if (inner.size > hdrLen + declared && chainOk(chan, inner)) {
                drain(chan, inner, sink)
                return
            }
        }
        sink(chan, inner)
    }

    /** Отдать все целые inner из buf; неполный хвост удержать. Битый заголовок посреди потока: остаток уходит как есть. */
    private fun drain(chan: Int, buf: ByteArray, sink: (chan: Int, inner: ByteArray) -> Unit) {
        val hdrLen = hdrLen(chan)
        var off = 0
        while (buf.size - off >= hdrLen) {
            if (!headerOk(chan, buf, off)) {
                sink(chan, buf.copyOfRange(off, buf.size))
                return
            }
            val end = off + hdrLen + declared(chan, buf, off)
            if (end > buf.size) break
            sink(chan, buf.copyOfRange(off, end))
            off = end
        }
        if (off < buf.size) pending[chan] = buf.copyOfRange(off, buf.size)
    }

    /** Кадр - цепочка inner с правильными заголовками; последний может быть неполным (или обрывком заголовка). */
    private fun chainOk(chan: Int, buf: ByteArray): Boolean {
        val hdrLen = hdrLen(chan)
        var off = 0
        while (buf.size - off >= hdrLen) {
            if (!headerOk(chan, buf, off)) return false
            off += hdrLen + declared(chan, buf, off)
        }
        return true
    }

    private fun hdrLen(chan: Int) = if (Ch.isCmdStyle(chan)) 8 else 12

    private fun declared(chan: Int, b: ByteArray, o: Int) = if (Ch.isCmdStyle(chan)) Wire.u16(b, o) else Wire.u32(b, o)

    private fun headerOk(chan: Int, b: ByteArray, o: Int): Boolean {
        val cmd = Ch.isCmdStyle(chan)
        val declared = declared(chan, b, o)
        val limit = if (cmd) MAX_CMD_DECLARED else Wire.MAX_OUTER_LEN
        val type = Wire.u32(b, o + if (cmd) 4 else 8)
        return declared in 0..limit && type ushr 16 == chan
    }

    /** Сколько каналов сейчас ждут тело. */
    fun waiting() = pending.size

    companion object {
        const val MAX_CMD_DECLARED = 1024 * 1024
    }
}

/**
 * Данные для MD_INFO (поля CarlifeDeviceInfo).
 * Источник: CarlifeDeviceInfoManager.getCarlifeDeviceInfoBuilder (Baidu CarLife 8.6.7, :70-114).
 */
class DeviceInfo(
    val os: String = "Android",
    val board: String = "",
    val bootloader: String = "",
    val brand: String = "",
    val cpuAbi: String = "",
    val cpuAbi2: String = "",
    val device: String = "",
    val display: String = "",
    val fingerprint: String = "",
    val hardware: String = "",
    val host: String = "",
    val cid: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val product: String = "",
    val serial: String = "unknown",
    val codename: String = "",
    val incremental: String = "",
    val release: String = "",
    val sdk: String = "",
    val sdkInt: Int = 0,
    val btaddress: String = "",
    val carlifeVersion: String = "",
    val carlifeType: Int = 0,
) {
    companion object {
        /**
         * Как в 8.6.7 (:90-98): при SDK_INT >= 33 MD врёт ГУ "29"/29, иначе шлёт настоящие значения.
         * @return пара (sdk строкой, sdk_int)
         */
        fun reportedSdk(realSdkInt: Int): Pair<String, Int> =
            if (realSdkInt >= 33) Pair("29", 29) else Pair(realSdkInt.toString(), realSdkInt)
    }
}

/** Тела сообщений (protobuf), байты сверены со спекой §9. */
object Msgs {
    fun protocolVersion(major: Int, minor: Int) =
        PbWriter().int32(1, major).int32(2, minor).toByteArray()

    fun matchStatus(status: Int) = PbWriter().int32(1, status).toByteArray()

    fun videoEncoderInfo(w: Int, h: Int, fps: Int) =
        PbWriter().int32(1, w).int32(2, h).int32(3, fps).toByteArray()

    fun authenResult(ok: Boolean) = PbWriter().bool(1, ok).toByteArray()

    fun authenResponse(encryptValue: String) = PbWriter().string(1, encryptValue).toByteArray()

    fun authenRequest(random: String) = PbWriter().string(1, random).toByteArray()

    /** CarlifeTouchAction; pointerx/pointery (второй палец) optional, без них тело как у одного пальца. */
    fun touchAction(action: Int, x: Int, y: Int, pointerX: Int? = null, pointerY: Int? = null): ByteArray {
        val w = PbWriter().int32(1, action).int32(2, x).int32(3, y)
        if (pointerX != null) w.int32(4, pointerX)
        if (pointerY != null) w.int32(5, pointerY)
        return w.toByteArray()
    }

    fun deviceInfo(d: DeviceInfo): ByteArray {
        val w = PbWriter()
        // поля optional: пустые строки не пишем
        fun s(f: Int, v: String) {
            if (v.isNotEmpty()) w.string(f, v)
        }
        s(1, d.os); s(2, d.board); s(3, d.bootloader); s(4, d.brand)
        s(5, d.cpuAbi); s(6, d.cpuAbi2); s(7, d.device); s(8, d.display)
        s(9, d.fingerprint); s(10, d.hardware); s(11, d.host); s(12, d.cid)
        s(13, d.manufacturer); s(14, d.model); s(15, d.product); s(16, d.serial)
        s(17, d.codename); s(18, d.incremental); s(19, d.release); s(20, d.sdk)
        w.int32(21, d.sdkInt)
        s(23, d.btaddress); s(24, d.carlifeVersion)
        if (d.carlifeType != 0) w.int32(25, d.carlifeType)
        return w.toByteArray()
    }

    /**
     * CarlifeModuleStatusList для MODULE_STATUS (ModuleStatusManage.sendCarlifeModuleListStatus, :158-221):
     * модули 1,2,3,4,6 всегда, 8 и 9 только если у ГУ major >= 2; статус у всех 0.
     */
    fun moduleStatusList(huMajor: Int): ByteArray {
        val ids = if (huMajor >= 2) intArrayOf(1, 2, 3, 4, 6, 8, 9) else intArrayOf(1, 2, 3, 4, 6)
        val w = PbWriter().int32(1, ids.size)
        for (id in ids) w.bytes(2, PbWriter().int32(1, id).int32(2, 0).toByteArray())
        return w.toByteArray()
    }

    /**
     * MODULE_STATUS об одном модуле (ModuleStatusManage.sendCarlifeModuleStatus, :260-273): cnt=1 и {moduleID, statusID}.
     * Для VR (moduleID 4): 1 = голосовой помощник работает, 0 = нет.
     */
    fun moduleStatusSingle(moduleId: Int, statusId: Int): ByteArray =
        PbWriter().int32(1, 1).bytes(2, PbWriter().int32(1, moduleId).int32(2, statusId).toByteArray()).toByteArray()

    /**
     * CARLIFE_DATA_SUBSCRIBE_DONE на запрос ГУ (CarlifeDataSubscribe.handleMobileCarLifeInfoList, :18-53):
     * moduleID зеркалится, supportFlag: 0 -> true, 1 -> false, 2 -> true, прочие false.
     * null, если запрос не разобрался (Baidu в этом случае не отвечает).
     */
    fun subscribeDone(req: ByteArray): ByteArray? {
        val f = PbReader.parse(req) ?: return null
        val items = PbReader.all(f, 2)
        val w = PbWriter().int32(1, items.size)
        for (item in items) {
            val sub = item.data?.let { d -> PbReader.parse(d) } ?: return null
            val id = PbReader.int(sub, 1) ?: return null
            w.bytes(2, PbWriter().int32(1, id).bool(2, id == 0 || id == 2).toByteArray())
        }
        return w.toByteArray()
    }

    /** moduleID из запроса подписки (для лога START/STOP). */
    fun subscribeIds(req: ByteArray): List<Int> {
        val f = PbReader.parse(req) ?: return emptyList()
        return PbReader.all(f, 2).mapNotNull { it.data?.let { d -> PbReader.parse(d) }?.let { s -> PbReader.int(s, 1) } }
    }

    fun themeState(mode: Int) = PbWriter().int32(1, mode).toByteArray()

    /**
     * CarlifeMediaInfo (MusicInfoSender.sendMusicInfoToVehicle :160-262). Все 9 полей proto2 required: пишем всегда,
     * пустые строки тоже, иначе ГУ на protobuf с required выбросит сообщение. duration в секундах.
     */
    fun mediaInfo(i: CarlifeMediaInfo, albumArt: ByteArray): ByteArray =
        PbWriter().string(1, i.source).string(2, i.song).string(3, i.artist).string(4, i.album)
            .bytes(5, albumArt).int32(6, i.durationSeconds).int32(7, i.playlistNum).string(8, i.songId).int32(9, i.mode)
            .toByteArray()

    /** CarlifeMediaProgressBar (MusicInfoSender :293-316). */
    fun mediaProgress(progressBar: Int) = PbWriter().int32(1, progressBar).toByteArray()

    /**
     * CarlifeNaviNextTurnInfo (RGEventHUDCollection :109-125). Поля 1-5 всегда; поле 6 по режиму [NaviField6]:
     * bytes turnIconData (8.6.7) и/или int32 time (apollo-DuerOS / CarProjection) под одним номером 6.
     */
    fun naviNextTurn(t: NaviTurnInfo, field6: NaviField6, icon: ByteArray): ByteArray {
        val w = PbWriter().int32(1, t.action).int32(2, t.nextTurn).string(3, t.roadName)
            .int32(4, t.totalDistance).int32(5, t.remainDistance)
        if (field6.time) w.int32(6, t.timeSeconds)
        if (field6.icon) w.bytes(6, icon)
        return w.toByteArray()
    }

    fun frameRate(fps: Int) = PbWriter().int32(1, fps).toByteArray()
}

/** Разобранный HU_FEATURE_CONFIG_RESPONSE (CarlifeFeatureConfigList). */
class FeatureConfig(
    val items: List<Pair<String, Int>>,
    val huBtAudioSupport: Boolean?,
    val huBtName: String?,
    val huBtMac: String?,
) {
    fun value(key: String): Int? = items.firstOrNull { it.first == key }?.second

    companion object {
        fun parse(body: ByteArray): FeatureConfig? {
            val f = PbReader.parse(body) ?: return null
            val items = ArrayList<Pair<String, Int>>()
            for (e in PbReader.all(f, 2)) {
                val sub = e.data?.let { PbReader.parse(it) } ?: continue
                items.add(Pair(PbReader.str(sub, 1) ?: "?", PbReader.int(sub, 2) ?: -1))
            }
            return FeatureConfig(
                items,
                PbReader.int(f, 3)?.let { it != 0 },
                PbReader.str(f, 4),
                PbReader.str(f, 5),
            )
        }
    }
}

object Hex {
    fun compact(b: ByteArray, max: Int = 256): String {
        val n = minOf(b.size, max)
        val sb = StringBuilder(n * 3)
        for (i in 0 until n) {
            if (i > 0) sb.append(' ')
            sb.append("%02X".format(b[i].toInt() and 0xFF))
        }
        if (b.size > n) sb.append(" ...(+${b.size - n})")
        return sb.toString()
    }

    /** Классический дамп 16 байт в строке, первые max байт. */
    fun dump(b: ByteArray, max: Int = 256): String {
        val n = minOf(b.size, max)
        val sb = StringBuilder()
        var o = 0
        while (o < n) {
            val l = minOf(16, n - o)
            sb.append("%04X  ".format(o))
            for (i in 0 until 16) {
                if (i < l) sb.append("%02X ".format(b[o + i].toInt() and 0xFF)) else sb.append("   ")
                if (i == 7) sb.append(' ')
            }
            sb.append(" |")
            for (i in 0 until l) {
                val c = b[o + i].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append('|')
            o += l
            if (o < n) sb.append('\n')
        }
        if (b.size > n) sb.append("\n... еще ${b.size - n} байт")
        return sb.toString()
    }
}

/** Работа с H.264 Annex-B. */
object AnnexB {
    private val SC4 = byteArrayOf(0, 0, 0, 1)

    /** Позиции стартовых кодов с байта [from]: (начало кода, длина кода 3 или 4). */
    private fun startCodes(d: ByteArray, from: Int = 0): List<IntArray> {
        val res = ArrayList<IntArray>()
        var i = from
        while (i + 2 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0) {
                if (d[i + 2].toInt() == 1) {
                    val s = if (i > from && d[i - 1].toInt() == 0 && (res.isEmpty() || res.last()[0] + res.last()[1] <= i - 1)) i - 1 else i
                    res.add(intArrayOf(s, i + 3 - s))
                    i += 3
                    continue
                }
            }
            i++
        }
        return res
    }

    /** Все стартовые коды приводятся к 4-байтным 00 00 00 01. Данные без стартовых кодов возвращаются как есть. */
    fun normalize(d: ByteArray): ByteArray {
        val sc = startCodes(d)
        if (sc.isEmpty() || sc.all { it[1] == 4 }) return d
        val out = ByteArrayOutputStream(d.size + sc.size)
        if (sc[0][0] > 0) out.write(d, 0, sc[0][0])
        for ((idx, s) in sc.withIndex()) {
            out.write(SC4)
            val from = s[0] + s[1]
            val to = if (idx + 1 < sc.size) sc[idx + 1][0] else d.size
            out.write(d, from, to - from)
        }
        return out.toByteArray()
    }

    /** NAL-типы (nal_unit_type) в порядке следования; [from] - начало Annex-B внутри массива (без копии, например тело CarLife-сообщения). */
    fun nalTypes(d: ByteArray, from: Int = 0): List<Int> {
        val res = ArrayList<Int>()
        for (s in startCodes(d, from)) {
            val p = s[0] + s[1]
            if (p < d.size) res.add(d[p].toInt() and 0x1F)
        }
        return res
    }

    fun startsWithSps(d: ByteArray) = nalTypes(d).firstOrNull() == 7

    /**
     * Минимальный level_idc H.264 для кадра w x h при 30 fps по MaxFS: 31 до 720p (3600 MB), 40 до 8192 MB (1080p),
     * 42 до 8704 MB (2240x976 = 8540), 50 до 22080 MB (2560x1440 = 14400), выше 51.
     */
    fun levelFor(w: Int, h: Int): Int {
        val mb = ((w + 15) / 16) * ((h + 15) / 16)
        return when {
            mb <= 3600 -> 31
            mb <= 8192 -> 40
            mb <= 8704 -> 42
            mb <= 22080 -> 50
            else -> 51
        }
    }

    /**
     * Подпись SPS Exynos (c2.exynos: Baseline, constraints 0x00, level_idc 52) старые ГУ CarLife не декодируют,
     * экран остаётся чёрным (Desay G6S, Samsung A55). Приводим к подписи c2.qti: constraint_set0 = 1, level по размеру.
     * Трогаем только SPS с level_idc > 41 (для наших размеров это ошибочная подпись), остальные отдаём как есть.
     * Байты profile/constraints/level идут сразу за заголовком NAL, эмуляционных 0x03 перед ними нет, а новые значения
     * (constraints 0x80, level >= 31) пару 00 00 не образуют. Возвращает исходный массив, если менять нечего.
     */
    fun fixSps(d: ByteArray, w: Int, h: Int): ByteArray {
        var out = d
        for (s in startCodes(d)) {
            val p = s[0] + s[1]
            if (p + 3 >= d.size || (d[p].toInt() and 0x1F) != 7) continue
            val profile = d[p + 1].toInt() and 0xFF
            val level = d[p + 3].toInt() and 0xFF
            if (level <= 41) continue
            val cons = if (profile == 66) (d[p + 2].toInt() or 0x80).toByte() else d[p + 2]
            val lvl = levelFor(w, h).toByte()
            // c2.qti на 2240x976 уже пишет 0x80/42: копия без изменений ломала поиск отличий в withFixedSps
            if (cons == d[p + 2] && lvl == d[p + 3]) continue
            if (out === d) out = d.copyOf()
            out[p + 2] = cons
            out[p + 3] = lvl
        }
        return out
    }

    fun glue(a: ByteArray, b: ByteArray): ByteArray {
        val r = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, r, 0, a.size)
        System.arraycopy(b, 0, r, a.size, b.size)
        return r
    }
}
