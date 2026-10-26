package com.yegorkapomidorka.headswap

import java.io.ByteArrayOutputStream

/**
 * Минимальный protobuf (proto2) руками: нужные сообщения CarLife простые (varint и строки),
 * поэтому без protobuf-javalite и плагина. Чистая JVM-логика, без android.*.
 */
class PbWriter {
    private val out = ByteArrayOutputStream()

    private fun rawVarint(value: Long) {
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

    private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())

    /** int32: отрицательные кодируются 10-байтным varint (знак расширяется), как в protoc. */
    fun int32(field: Int, v: Int): PbWriter {
        tag(field, 0)
        rawVarint(v.toLong())
        return this
    }

    fun bool(field: Int, v: Boolean): PbWriter = int32(field, if (v) 1 else 0)

    fun bytes(field: Int, b: ByteArray): PbWriter {
        tag(field, 2)
        rawVarint(b.size.toLong())
        out.write(b)
        return this
    }

    fun string(field: Int, s: String): PbWriter = bytes(field, s.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Одно поле разобранного сообщения. wire: 0 varint, 1 fixed64, 2 len-delimited, 5 fixed32. */
class PbField(val number: Int, val wire: Int, val num: Long, val data: ByteArray?)

object PbReader {
    /** null, если байты не похожи на protobuf (обрыв, неизвестный wire type). */
    fun parse(b: ByteArray): List<PbField>? {
        val res = ArrayList<PbField>()
        var p = 0
        fun varint(): Long? {
            var shift = 0
            var r = 0L
            while (p < b.size && shift < 70) {
                val x = b[p++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
            return null
        }
        while (p < b.size) {
            val t = varint() ?: return null
            val wire = (t and 7).toInt()
            val field = (t ushr 3).toInt()
            if (field == 0) return null
            when (wire) {
                0 -> res.add(PbField(field, 0, varint() ?: return null, null))
                1 -> {
                    if (p + 8 > b.size) return null
                    var v = 0L
                    for (i in 0 until 8) v = v or ((b[p + i].toLong() and 0xFF) shl (8 * i))
                    p += 8
                    res.add(PbField(field, 1, v, null))
                }
                2 -> {
                    val l = varint() ?: return null
                    if (l < 0 || p + l > b.size) return null
                    res.add(PbField(field, 2, 0, b.copyOfRange(p, p + l.toInt())))
                    p += l.toInt()
                }
                5 -> {
                    if (p + 4 > b.size) return null
                    var v = 0L
                    for (i in 0 until 4) v = v or ((b[p + i].toLong() and 0xFF) shl (8 * i))
                    p += 4
                    res.add(PbField(field, 5, v, null))
                }
                else -> return null
            }
        }
        return res
    }

    /** Поле как int32 (первое с таким номером). */
    fun int(fields: List<PbField>, number: Int): Int? =
        fields.firstOrNull { it.number == number && it.wire == 0 }?.num?.toInt()

    fun str(fields: List<PbField>, number: Int): String? =
        fields.firstOrNull { it.number == number && it.wire == 2 }?.data?.toString(Charsets.UTF_8)

    fun all(fields: List<PbField>, number: Int): List<PbField> =
        fields.filter { it.number == number }
}

/** BYTES: двоичное поле (картинка), в лог только размер и формат. */
enum class Kind { INT, BOOL, STR, MSG, BYTES }

class FieldDef(val name: String, val kind: Kind, val sub: Schema? = null)

/** Описание сообщения только для красивого вывода в лог (имена полей). */
class Schema(val name: String, val fields: Map<Int, FieldDef>)

private fun int(n: String) = FieldDef(n, Kind.INT)
private fun bool(n: String) = FieldDef(n, Kind.BOOL)
private fun str(n: String) = FieldDef(n, Kind.STR)

private fun schema(name: String, vararg f: Pair<Int, FieldDef>) = Schema(name, f.toMap())

object Schemas {
    val protocolVersion = schema("CarlifeProtocolVersion", 1 to int("majorVersion"), 2 to int("minorVersion"))
    val matchStatus = schema("CarlifeProtocolVersionMatchStatus", 1 to int("matchStatus"))
    val videoEncoderInfo = schema("CarlifeVideoEncoderInfo", 1 to int("width"), 2 to int("height"), 3 to int("frameRate"))
    val videoFrameRate = schema("CarlifeVideoFrameRate", 1 to int("frameRate"))
    val authenRequest = schema("CarlifeAuthenRequest", 1 to str("randomValue"))
    val authenResponse = schema("CarlifeAuthenResponse", 1 to str("encryptValue"))
    val authenResult = schema("CarlifeAuthenResult", 1 to bool("authenResult"))
    val btStartIdentifyReq = schema("CarlifeBTStartIdentifyReq", 1 to str("address"))
    val btIdentifyResultInd = schema("CarlifeBTIdentifyResultInd", 1 to int("status"), 2 to str("address"))
    val moduleStatus = schema("CarlifeModuleStatus", 1 to int("moduleID"), 2 to int("statusID"))
    val moduleStatusList = schema(
        "CarlifeModuleStatusList",
        1 to int("cnt"), 2 to FieldDef("moduleStatus", Kind.MSG, moduleStatus)
    )
    val featureConfig = schema("CarlifeFeatureConfig", 1 to str("key"), 2 to int("value"))
    val featureConfigList = schema(
        "CarlifeFeatureConfigList",
        1 to int("cnt"), 2 to FieldDef("featureConfig", Kind.MSG, featureConfig),
        3 to bool("huBtAudioSupport"), 4 to str("huBtName"), 5 to str("huBtMAC")
    )
    val touchAction = schema(
        "CarlifeTouchAction",
        1 to int("action"), 2 to int("x"), 3 to int("y"), 4 to int("pointerx"), 5 to int("pointery")
    )
    val touchPoint = schema("CarlifeTouchSinglePoint", 1 to int("x"), 2 to int("y"), 3 to int("pointerx"), 4 to int("pointery"))
    val hardKey = schema("CarlifeCarHardKeyCode", 1 to int("keycode"))
    val connectException = schema("CarlifeConnectException", 1 to int("exceptionType"))
    val errorCode = schema("CarlifeErrorCode", 1 to str("errorCode"))
    val gearInfo = schema("CarlifeGearInfo", 1 to int("gear"))
    val musicInit = schema("CarlifeMusicInit", 1 to int("sampleRate"), 2 to int("channelConfig"), 3 to int("sampleFormat"))
    val btPairInfo = schema(
        "CarlifeBTPairInfo",
        1 to str("address"), 2 to str("passKey"), 3 to str("hash"), 4 to str("randomizer"),
        5 to str("uuid"), 6 to str("name"), 7 to int("status")
    )
    val statisticsInfo = schema(
        "CarlifeStatisticsInfo",
        1 to str("cuid"), 2 to str("versionName"), 3 to int("versionCode"), 4 to str("channel"),
        5 to int("connectCount"), 6 to int("connectSuccessCount"), 7 to int("connectTime"), 8 to str("crashLog")
    )
    val deviceInfo = schema(
        "CarlifeDeviceInfo",
        1 to str("os"), 2 to str("board"), 3 to str("bootloader"), 4 to str("brand"), 5 to str("cpu_abi"),
        6 to str("cpu_abi2"), 7 to str("device"), 8 to str("display"), 9 to str("fingerprint"),
        10 to str("hardware"), 11 to str("host"), 12 to str("cid"), 13 to str("manufacturer"),
        14 to str("model"), 15 to str("product"), 16 to str("serial"), 17 to str("codename"),
        18 to str("incremental"), 19 to str("release"), 20 to str("sdk"), 21 to int("sdk_int"),
        22 to str("token"), 23 to str("btaddress"), 24 to str("carlifeversion"), 25 to int("carlifeType"),
        26 to str("year")
    )
    val subscribeInfo = schema(
        "CarlifeSubscribeMobileCarLifeInfo",
        1 to int("moduleID"), 2 to bool("supportFlag"), 3 to int("frequency")
    )
    val subscribeList = schema(
        "CarlifeSubscribeMobileCarLifeInfoList",
        1 to int("cnt"), 2 to FieldDef("subscribemobileCarLifeInfo", Kind.MSG, subscribeInfo)
    )
    val themeState = schema("CarlifeThemeState", 1 to int("themeModes"))
    val mediaInfo = schema(
        "CarlifeMediaInfo",
        1 to str("source"), 2 to str("song"), 3 to str("artist"), 4 to str("album"),
        5 to FieldDef("albumArt", Kind.BYTES), 6 to int("duration"), 7 to int("playlistNum"), 8 to str("songId"), 9 to int("mode")
    )
    val mediaProgressBar = schema("CarlifeMediaProgressBar", 1 to int("progressBar"))

    /** Поле 6: в 8.6.7 bytes turnIconData, в refs (apollo/CarProjection) int32 time; рендер по wire-type. */
    val naviNextTurnInfo = schema(
        "CarlifeNaviNextTurnInfo",
        1 to int("action"), 2 to int("nextTurn"), 3 to str("roadName"), 4 to int("totalDistance"),
        5 to int("remainDistance"), 6 to FieldDef("time|turnIconData", Kind.BYTES)
    )

    /** type сообщения -> схема тела. */
    val byType: Map<Int, Schema> = mapOf(
        Msg.HU_PROTOCOL_VERSION to protocolVersion,
        Msg.PROTOCOL_VERSION_MATCH_STATUS to matchStatus,
        Msg.HU_INFO to deviceInfo,
        Msg.MD_INFO to deviceInfo,
        Msg.HU_BT_OOB_INFO to btPairInfo,
        Msg.MD_BT_OOB_INFO to btPairInfo,
        Msg.VIDEO_ENCODER_INIT to videoEncoderInfo,
        Msg.VIDEO_ENCODER_INIT_DONE to videoEncoderInfo,
        Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE to videoFrameRate,
        Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE to videoFrameRate,
        Msg.MODULE_STATUS to moduleStatusList,
        Msg.STATISTIC_INFO to statisticsInfo,
        Msg.MODULE_CONTROL to moduleStatus,
        Msg.GEAR_INFO to gearInfo,
        Msg.CONNECT_EXCEPTION to connectException,
        Msg.CARLIFE_DATA_SUBSCRIBE to subscribeList,
        Msg.CARLIFE_DATA_SUBSCRIBE_DONE to subscribeList,
        Msg.CARLIFE_DATA_SUBSCRIBE_START to subscribeList,
        Msg.CARLIFE_DATA_SUBSCRIBE_STOP to subscribeList,
        Msg.MEDIA_INFO to mediaInfo,
        Msg.MEDIA_PROGRESS_BAR to mediaProgressBar,
        Msg.NAVI_NEXT_TURN_INFO to naviNextTurnInfo,
        Msg.CHANGE_MD_THEME to themeState,
        Msg.RESPONSE_MD_THEME_STATE to themeState,
        Msg.HU_AUTHEN_REQUEST to authenRequest,
        Msg.MD_AUTHEN_RESPONSE to authenResponse,
        Msg.HU_AUTHEN_RESULT to authenResult,
        Msg.MD_AUTHEN_RESULT to authenResult,
        Msg.BT_START_IDENTIFY_REQ to btStartIdentifyReq,
        Msg.BT_IDENTIFY_RESULT_IND to btIdentifyResultInd,
        Msg.HU_FEATURE_CONFIG_RESPONSE to featureConfigList,
        Msg.ERROR_CODE to errorCode,
        Msg.MEDIA_INIT to musicInit,
        Msg.TTS_INIT to musicInit,
        Msg.VR_INIT to musicInit,
        Msg.TOUCH_ACTION to touchAction,
        Msg.TOUCH_ACTION_BEGIN to touchPoint,
        Msg.TOUCH_ACTION_DOWN to touchPoint,
        Msg.TOUCH_ACTION_UP to touchPoint,
        Msg.TOUCH_ACTION_MOVE to touchPoint,
        Msg.TOUCH_SINGLE_CLICK to touchPoint,
        Msg.TOUCH_DOUBLE_CLICK to touchPoint,
        Msg.TOUCH_LONG_PRESS to touchPoint,
        Msg.TOUCH_ACTION_POINTER_DOWN to touchPoint,
        Msg.TOUCH_ACTION_POINTER_UP to touchPoint,
        Msg.TOUCH_ACTION_OTHERPOINTER_UP to touchPoint,
        Msg.TOUCH_CAR_HARD_KEY_CODE to hardKey,
    )
}

/** Текстовое представление protobuf-тела для лога. */
object PbText {
    fun render(body: ByteArray, schema: Schema?, depth: Int = 0): String {
        if (body.isEmpty()) return "{}"
        val fields = PbReader.parse(body) ?: return "<не protobuf: ${Hex.compact(body, 64)}>"
        val sb = StringBuilder("{")
        for ((i, f) in fields.withIndex()) {
            if (i > 0) sb.append(' ')
            val def = schema?.fields?.get(f.number)
            sb.append(def?.name ?: "f${f.number}").append('=')
            when (f.wire) {
                0 -> sb.append(
                    if (def?.kind == Kind.BOOL) (f.num != 0L).toString() else f.num.toInt().toString()
                )
                1, 5 -> sb.append("0x").append(java.lang.Long.toHexString(f.num))
                2 -> {
                    val d = f.data!!
                    if (def?.kind == Kind.MSG && depth < 4) sb.append(render(d, def.sub, depth + 1))
                    else if (def?.kind == Kind.BYTES) sb.append(blob(d))
                    else sb.append(quote(d))
                }
            }
        }
        return sb.append('}').toString()
    }

    /** "<N Б PNG>": размер и формат по сигнатуре. */
    fun blob(d: ByteArray): String {
        val kind = when {
            d.isEmpty() -> "пусто"
            d.size >= 4 && d[0] == 0x89.toByte() && d[1] == 'P'.code.toByte() && d[2] == 'N'.code.toByte() -> "PNG"
            d.size >= 3 && d[0] == 0xFF.toByte() && d[1] == 0xD8.toByte() -> "JPEG"
            else -> "bin"
        }
        return "<${d.size} Б $kind>"
    }

    /** Строка в кавычках; непечатные байты как \xNN. */
    fun quote(d: ByteArray): String {
        val sb = StringBuilder("\"")
        for (b in d) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E && c != '"'.code && c != '\\'.code) sb.append(c.toChar())
            else sb.append("\\x").append("%02x".format(c))
        }
        return sb.append('"').toString()
    }
}

/**
 * Тач ГУ в виде CarlifeTouchAction: action как у MotionEvent (action&255 - код, action>>8 - индекс пальца),
 * второй палец в px/py (0,0 = нет). Жестовые коды сводятся к тем же action, как у Baidu CarlifeTouchManager.
 */
data class CarlifeTouch(val action: Int, val x: Int, val y: Int, val px: Int = 0, val py: Int = 0) {
    val masked: Int get() = action and 0xFF

    /** Пальцев в событии: 2, если есть координаты второго или это POINTER_DOWN/UP. */
    val fingers: Int get() = if (px != 0 || py != 0 || masked == 5 || masked == 6) 2 else 1

    companion object {
        /** null - не тач (кнопки руля и пр.) или тело не разобралось. Клики/долгое нажатие -> action 3 (в AA не уходят). */
        fun parse(type: Int, body: ByteArray): CarlifeTouch? {
            val f = PbReader.parse(body) ?: return null
            if (type == Msg.TOUCH_ACTION) {
                return CarlifeTouch(
                    PbReader.int(f, 1) ?: -1, PbReader.int(f, 2) ?: -1, PbReader.int(f, 3) ?: -1,
                    PbReader.int(f, 4) ?: 0, PbReader.int(f, 5) ?: 0,
                )
            }
            val action = when (type) {
                Msg.TOUCH_ACTION_BEGIN, Msg.TOUCH_ACTION_DOWN -> 0
                Msg.TOUCH_ACTION_UP -> 1
                Msg.TOUCH_ACTION_MOVE -> 2
                Msg.TOUCH_SINGLE_CLICK, Msg.TOUCH_DOUBLE_CLICK, Msg.TOUCH_LONG_PRESS -> 3
                Msg.TOUCH_ACTION_POINTER_DOWN -> 5 or (1 shl 8)
                Msg.TOUCH_ACTION_POINTER_UP -> 6
                Msg.TOUCH_ACTION_OTHERPOINTER_UP -> 6 or (1 shl 8)
                else -> return null
            }
            return CarlifeTouch(
                action, PbReader.int(f, 1) ?: -1, PbReader.int(f, 2) ?: -1,
                PbReader.int(f, 3) ?: 0, PbReader.int(f, 4) ?: 0,
            )
        }
    }
}
