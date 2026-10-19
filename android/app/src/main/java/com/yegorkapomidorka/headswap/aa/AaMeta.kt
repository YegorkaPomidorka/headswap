package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.PbField
import com.yegorkapomidorka.headswap.PbReader

/**
 * Метаданные AA (ch9 MEDIA_PLAYBACK, ch10 NAVIGATION) -> CarLife (MEDIA_INFO, MEDIA_PROGRESS_BAR, NAVI_NEXT_TURN_INFO).
 * Чистая JVM-логика: разбор, маппинг манёвров, решение что и когда слать. Картинки (PNG -> JPEG, стрелки) делает
 * CarLife-сторона. Разведка: docs/cluster-metadata-recon.md.
 */

/** ch9 0x8001 MediaPlaybackStatus. state: 1 STOPPED, 2 PLAYING, 3 PAUSED. */
class AaMediaStatus(
    val state: Int?,
    val source: String?,
    val playbackSeconds: Int?,
    val shuffle: Boolean?,
    val repeat: Boolean?,
    val repeatOne: Boolean?,
) {
    val playing: Boolean get() = state == AaMeta.STATE_PLAYING
    override fun toString() = "state=${AaMeta.stateName(state)} source=$source pos=${playbackSeconds}s shuffle=$shuffle repeat=$repeat repeat_one=$repeatOne"
}

/** ch9 0x8003 MediaPlaybackMetadata; art обычно PNG 256x256. */
class AaMediaMetadata(
    val song: String?,
    val artist: String?,
    val album: String?,
    val art: ByteArray?,
    val playlist: String?,
    val durationSeconds: Int?,
    val rating: Int?,
) {
    override fun toString() =
        "song=\"$song\" artist=\"$artist\" album=\"$album\" duration=${durationSeconds}s playlist=$playlist rating=$rating art=${AaMeta.blobInfo(art)}"
}

/** Следующий манёвр: из legacy 0x8004 или из первого шага 0x8006. nextTurn уже в кодах CarLife (RouteGuideParams). */
class AaNavTurn(
    val nextTurn: Int,
    val road: String,
    val maneuver: String,
    val roundaboutExit: Int?,
    val image: ByteArray?,
    /** Из legacy 0x8004 (иначе из 0x8006). */
    val legacy: Boolean = false,
    /** Дорога для ключа манёвра: только road.name, без текста cue (он может меняться по мере приближения). */
    val keyRoad: String = road,
) {
    override fun toString() = "maneuver=$maneuver -> nextTurn=$nextTurn road=\"$road\"" +
        (if (roundaboutExit != null) " exit=$roundaboutExit" else "") + (if (image != null) " image=${AaMeta.blobInfo(image)}" else "")
}

/** Дистанция до манёвра: legacy 0x8005 или step_distance из 0x8007. */
class AaNavDistance(
    val meters: Int,
    val timeSeconds: Int?,
    val destinationMeters: Int? = null,
    val currentRoad: String? = null,
    /** Из legacy 0x8005 (иначе из 0x8007). */
    val legacy: Boolean = false,
) {
    override fun toString() = "remain=${meters}m time=${timeSeconds}s" +
        (if (destinationMeters != null) " до цели=${destinationMeters}m" else "") + (if (currentRoad != null) " текущая=\"$currentRoad\"" else "")
}

/** Приёмник метаданных AA (реализует AaBridgeCore); зовётся из нити AA-сессии. */
interface AaMetaSink {
    fun onMediaStatus(s: AaMediaStatus) {}
    fun onMediaMetadata(m: AaMediaMetadata) {}

    /** ch10 INSTRUMENT_CLUSTER_START (true) / STOP (false). */
    fun onNavCluster(start: Boolean) {}

    /** ch10 0x8003 NavigationStatus: 0 UNAVAILABLE, 1 ACTIVE, 2 INACTIVE, 3 REROUTING. */
    fun onNavStatus(status: Int) {}
    fun onNavTurn(t: AaNavTurn) {}
    fun onNavDistance(d: AaNavDistance) {}
}

/** Одно сообщение CarLife MEDIA_INFO; art - как пришло из AA (PNG), перекодирует CarLife-сторона. */
class CarlifeMediaInfo(
    val source: String,
    val song: String,
    val artist: String,
    val album: String,
    val art: ByteArray?,
    val durationSeconds: Int,
    val playlistNum: Int = 0,
    val songId: String = "-1",
    val mode: Int = 0,
) {
    override fun toString() = "song=\"$song\" artist=\"$artist\" album=\"$album\" duration=${durationSeconds}s source=$source art=${AaMeta.blobInfo(art)}"
}

/** Одно сообщение CarLife NAVI_NEXT_TURN_INFO (поле 6 и иконку выбирает CarLife-сторона по настройке). */
class NaviTurnInfo(
    val action: Int,
    val nextTurn: Int,
    val roadName: String,
    val totalDistance: Int,
    val remainDistance: Int,
    val timeSeconds: Int,
) {
    override fun toString() = "action=$action nextTurn=$nextTurn road=\"$roadName\" total=${totalDistance}m remain=${remainDistance}m time=${timeSeconds}s"
}

/**
 * Что класть в поле 6 NAVI_NEXT_TURN_INFO: в 8.6.7 это bytes turnIconData (PNG), в apollo-DuerOS/CarProjection - int32 time.
 * BOTH - оба под номером 6: парсер, ждущий другой wire-type, уносит чужое в unknown fields. Выбор - в «Для разработчиков».
 */
enum class NaviField6(val icon: Boolean, val time: Boolean) {
    BOTH(true, true), ICON(true, false), TIME(false, true), NONE(false, false);

    companion object {
        fun of(icon: Boolean, time: Boolean) = values().first { it.icon == icon && it.time == time }
    }
}

object AaMeta {
    // ch9 MediaPlaybackStatusMessageId
    const val MEDIA_STATUS = 0x8001
    const val MEDIA_INPUT = 0x8002
    const val MEDIA_METADATA = 0x8003

    // ch10 NavigationStatusMessageId
    const val NAV_START = 0x8001
    const val NAV_STOP = 0x8002
    const val NAV_STATUS = 0x8003
    const val NAV_TURN = 0x8004
    const val NAV_DISTANCE = 0x8005
    const val NAV_STATE = 0x8006
    const val NAV_POSITION = 0x8007

    const val STATE_PLAYING = 2

    const val NAV_UNAVAILABLE = 0
    const val NAV_ACTIVE = 1
    const val NAV_INACTIVE = 2
    const val NAV_REROUTING = 3

    // CarLife nextTurn (RE/core/base/subscribe/RouteGuideParams.java, gTurnIconName)
    const val T_FRONT = 1
    const val T_RIGHT_FRONT = 2
    const val T_RIGHT = 3
    const val T_RIGHT_BACK = 4
    const val T_BACK = 5
    const val T_LEFT_BACK = 6
    const val T_LEFT = 7
    const val T_LEFT_FRONT = 8
    const val T_RING = 9
    const val T_RING_OUT = 10
    const val T_LEFT_SIDE = 11
    const val T_RIGHT_SIDE = 12
    const val T_LEFT_SIDE_MAIN = 13
    const val T_BRANCH_LEFT_STRAIGHT = 14
    const val T_RIGHT_SIDE_MAIN = 15
    const val T_BRANCH_RIGHT_STRAIGHT = 16
    const val T_BRANCH_CENTER = 17
    const val T_LEFT_SIDE_IC = 18
    const val T_RIGHT_SIDE_IC = 19
    const val T_BRANCH_LEFT = 20
    const val T_BRANCH_RIGHT = 21
    const val T_START = 23
    const val T_DEST = 24
    const val T_IN_FERRY = 29

    /** Конец маршрута, как Baidu onNaviStateChange(false): nextTurn 24, roadName «目的地». */
    const val END_ROAD = "目的地"

    fun stateName(s: Int?) = when (s) {
        1 -> "STOPPED"
        2 -> "PLAYING"
        3 -> "PAUSED"
        null -> "-"
        else -> "?$s"
    }

    fun navStatusName(s: Int) = when (s) {
        0 -> "UNAVAILABLE"
        1 -> "ACTIVE"
        2 -> "INACTIVE"
        3 -> "REROUTING"
        else -> "?$s"
    }

    /** "<N Б PNG>" для лога. */
    fun blobInfo(b: ByteArray?): String {
        if (b == null) return "нет"
        val kind = when {
            b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "PNG"
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "JPEG"
            b.isEmpty() -> "пусто"
            else -> "bin"
        }
        return "<${b.size} Б $kind>"
    }

    // --- разбор ---
    private fun fields(p: ByteArray): List<PbField>? = PbReader.parse(p)
    private fun int(f: List<PbField>, n: Int): Int? = PbReader.int(f, n)
    private fun bool(f: List<PbField>, n: Int): Boolean? = int(f, n)?.let { it != 0 }
    private fun str(f: List<PbField>, n: Int): String? = PbReader.str(f, n)
    private fun blob(f: List<PbField>, n: Int): ByteArray? = f.firstOrNull { it.number == n && it.wire == 2 }?.data
    private fun sub(f: List<PbField>, n: Int): List<PbField>? = blob(f, n)?.let { PbReader.parse(it) }

    fun parseMediaStatus(p: ByteArray): AaMediaStatus? {
        val f = fields(p) ?: return null
        return AaMediaStatus(int(f, 1), str(f, 2), int(f, 3), bool(f, 4), bool(f, 5), bool(f, 6))
    }

    fun parseMediaMetadata(p: ByteArray): AaMediaMetadata? {
        val f = fields(p) ?: return null
        return AaMediaMetadata(str(f, 1), str(f, 2), str(f, 3), blob(f, 4), str(f, 5), int(f, 6), int(f, 7))
    }

    fun parseNavStatus(p: ByteArray): Int? = fields(p)?.let { int(it, 1) }

    /** Legacy 0x8004 NextTurnEvent{road 1, turn_side 2, event 3, image 4, turn_number 5, turn_angle 6}. */
    fun parseLegacyTurn(p: ByteArray): AaNavTurn? {
        val f = fields(p) ?: return null
        val event = int(f, 3) ?: 0
        val side = int(f, 2)
        return AaNavTurn(
            turnFromLegacy(event, side), str(f, 1) ?: "", "${legacyName(event)}/${sideName(side)}", int(f, 5), blob(f, 4),
            legacy = true,
        )
    }

    /** Legacy 0x8005 NextTurnDistanceEvent{distance_meters 1, time_to_turn_seconds 2, ...}. */
    fun parseLegacyDistance(p: ByteArray): AaNavDistance? {
        val f = fields(p) ?: return null
        val m = int(f, 1) ?: return null
        return AaNavDistance(m, int(f, 2), legacy = true)
    }

    /**
     * 0x8006 NavigationState{steps 1, destinations 2}: следующий манёвр = первый шаг.
     * NavigationStep{maneuver 1 {type 1, roundabout_exit_number 2}, road 2 {name 1}, lanes 3, cue 4 {alternate_text 1}}.
     */
    fun parseNavState(p: ByteArray): AaNavTurn? {
        val f = fields(p) ?: return null
        val step = sub(f, 1) ?: return null
        val man = sub(step, 1)
        val type = man?.let { int(it, 1) } ?: 0
        val name = sub(step, 2)?.let { str(it, 1) }?.takeIf { it.isNotBlank() }
        val road = name ?: sub(step, 4)?.let { str(it, 1) }?.takeIf { it.isNotBlank() } ?: ""
        return AaNavTurn(turnFromManeuver(type), road, maneuverName(type), man?.let { int(it, 2) }, null, keyRoad = name ?: "")
    }

    /**
     * 0x8007 NavigationCurrentPosition{step_distance 1 {distance 1 {meters 1}, time_to_step_seconds 2},
     * destination_distances 2 {distance 1 {meters 1}}, current_road 3 {name 1}}. null, если нет дистанции до шага.
     */
    fun parseNavPosition(p: ByteArray): AaNavDistance? {
        val f = fields(p) ?: return null
        val sd = sub(f, 1) ?: return null
        val meters = sub(sd, 1)?.let { int(it, 1) } ?: return null
        val time = PbReader.all(sd, 2).firstOrNull { it.wire == 0 }?.num?.toInt()
        val dest = sub(f, 2)?.let { sub(it, 1) }?.let { int(it, 1) }
        val cur = sub(f, 3)?.let { str(it, 1) }
        return AaNavDistance(meters, time, dest, cur)
    }

    /**
     * Сообщение ch9/ch10 -> приёмник. Возвращает строку-итог для лога (или null, если тип не про метаданные).
     * Ошибки разбора не роняют сессию: возвращается текст ошибки.
     */
    fun dispatch(chan: Int, type: Int, payload: ByteArray, sink: AaMetaSink?): String? {
        if (sink == null) return null
        return try {
            when (chan) {
                AapMessages.CH_MEDIA_PLAYBACK -> when (type) {
                    MEDIA_STATUS -> parseMediaStatus(payload)?.also { sink.onMediaStatus(it) }?.toString() ?: "MediaPlaybackStatus не разобран"
                    MEDIA_METADATA -> parseMediaMetadata(payload)?.also { sink.onMediaMetadata(it) }?.toString() ?: "MediaPlaybackMetadata не разобран"
                    else -> null
                }
                AapMessages.CH_NAVIGATION -> when (type) {
                    NAV_START -> "cluster START".also { sink.onNavCluster(true) }
                    NAV_STOP -> "cluster STOP".also { sink.onNavCluster(false) }
                    NAV_STATUS -> parseNavStatus(payload)?.let { sink.onNavStatus(it); "status=${navStatusName(it)}" } ?: "NavigationStatus не разобран"
                    NAV_TURN -> parseLegacyTurn(payload)?.also { sink.onNavTurn(it) }?.let { "legacy $it" } ?: "NextTurnEvent не разобран"
                    NAV_DISTANCE -> parseLegacyDistance(payload)?.also { sink.onNavDistance(it) }?.let { "legacy $it" } ?: "NextTurnDistanceEvent не разобран"
                    NAV_STATE -> parseNavState(payload)?.also { sink.onNavTurn(it) }?.let { "state $it" } ?: "NavigationState без шагов"
                    NAV_POSITION -> parseNavPosition(payload)?.also { sink.onNavDistance(it) }?.let { "position $it" } ?: "NavigationCurrentPosition без step_distance"
                    else -> null
                }
                else -> null
            }
        } catch (e: Throwable) {
            "сбой разбора: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // --- маппинг манёвров AA -> CarLife nextTurn ---
    /** NavigationManeuver.NavigationType (0x8006) -> nextTurn. Неизвестное -> 1 (прямо), как Baidu. */
    fun turnFromManeuver(type: Int): Int = when (type) {
        1 -> T_START                                    // DEPART
        2 -> T_FRONT                                    // NAME_CHANGE
        3 -> T_BRANCH_LEFT_STRAIGHT                     // KEEP_LEFT
        4 -> T_BRANCH_RIGHT_STRAIGHT                    // KEEP_RIGHT
        5 -> T_LEFT_FRONT                               // TURN_SLIGHT_LEFT
        6 -> T_RIGHT_FRONT                              // TURN_SLIGHT_RIGHT
        7 -> T_LEFT                                     // TURN_NORMAL_LEFT
        8 -> T_RIGHT                                    // TURN_NORMAL_RIGHT
        9 -> T_LEFT_BACK                                // TURN_SHARP_LEFT
        10 -> T_RIGHT_BACK                              // TURN_SHARP_RIGHT
        11, 12, 19, 20 -> T_BACK                        // U_TURN_*, ON_RAMP_U_TURN_*
        13, 15, 17 -> T_LEFT_SIDE_IC                    // ON_RAMP_*_LEFT
        14, 16, 18 -> T_RIGHT_SIDE_IC                   // ON_RAMP_*_RIGHT
        21, 23 -> T_LEFT_SIDE                           // OFF_RAMP_*_LEFT
        22, 24 -> T_RIGHT_SIDE                          // OFF_RAMP_*_RIGHT
        25 -> T_BRANCH_LEFT                             // FORK_LEFT
        26 -> T_BRANCH_RIGHT                            // FORK_RIGHT
        27 -> T_LEFT_SIDE_MAIN                          // MERGE_LEFT
        28 -> T_RIGHT_SIDE_MAIN                         // MERGE_RIGHT
        29 -> T_FRONT                                   // MERGE_SIDE_UNSPECIFIED
        30, 32, 33, 34, 35 -> T_RING                    // ROUNDABOUT_ENTER(_AND_EXIT*)
        31 -> T_RING_OUT                                // ROUNDABOUT_EXIT
        36 -> T_FRONT                                   // STRAIGHT
        37, 38 -> T_IN_FERRY                            // FERRY_*
        39, 40, 41, 42 -> T_DEST                        // DESTINATION*
        else -> T_FRONT
    }

    /** Legacy NextTurnEnum (0x8004) x TurnSide (1 LEFT, 2 RIGHT, 3 UNSPECIFIED) -> nextTurn. */
    fun turnFromLegacy(event: Int, side: Int?): Int {
        val left = side == 1
        val right = side == 2
        fun lr(l: Int, r: Int, none: Int = T_FRONT) = if (left) l else if (right) r else none
        return when (event) {
            1 -> T_START                                        // DEPART
            2 -> T_FRONT                                        // NAME_CHANGE
            3 -> lr(T_LEFT_FRONT, T_RIGHT_FRONT)                // SLIGHT_TURN
            4 -> lr(T_LEFT, T_RIGHT)                            // TURN
            5 -> lr(T_LEFT_BACK, T_RIGHT_BACK)                  // SHARP_TURN
            6 -> T_BACK                                         // U_TURN
            7 -> lr(T_LEFT_SIDE_IC, T_RIGHT_SIDE_IC)            // ON_RAMP
            8 -> lr(T_LEFT_SIDE, T_RIGHT_SIDE)                  // OFF_RAMP
            9 -> lr(T_BRANCH_LEFT, T_BRANCH_RIGHT, T_BRANCH_CENTER) // FORK
            10 -> lr(T_LEFT_SIDE_MAIN, T_RIGHT_SIDE_MAIN)       // MERGE
            11, 13 -> T_RING                                    // ROUNDABOUT_ENTER(_AND_EXIT)
            12 -> T_RING_OUT                                    // ROUNDABOUT_EXIT
            14 -> T_FRONT                                       // STRAIGHT
            16, 17 -> T_IN_FERRY                                // FERRY_*
            19 -> T_DEST                                        // DESTINATION
            else -> T_FRONT
        }
    }

    private val MANEUVER_NAMES = arrayOf(
        "UNKNOWN", "DEPART", "NAME_CHANGE", "KEEP_LEFT", "KEEP_RIGHT", "TURN_SLIGHT_LEFT", "TURN_SLIGHT_RIGHT",
        "TURN_NORMAL_LEFT", "TURN_NORMAL_RIGHT", "TURN_SHARP_LEFT", "TURN_SHARP_RIGHT", "U_TURN_LEFT", "U_TURN_RIGHT",
        "ON_RAMP_SLIGHT_LEFT", "ON_RAMP_SLIGHT_RIGHT", "ON_RAMP_NORMAL_LEFT", "ON_RAMP_NORMAL_RIGHT", "ON_RAMP_SHARP_LEFT",
        "ON_RAMP_SHARP_RIGHT", "ON_RAMP_U_TURN_LEFT", "ON_RAMP_U_TURN_RIGHT", "OFF_RAMP_SLIGHT_LEFT", "OFF_RAMP_SLIGHT_RIGHT",
        "OFF_RAMP_NORMAL_LEFT", "OFF_RAMP_NORMAL_RIGHT", "FORK_LEFT", "FORK_RIGHT", "MERGE_LEFT", "MERGE_RIGHT",
        "MERGE_SIDE_UNSPECIFIED", "ROUNDABOUT_ENTER", "ROUNDABOUT_EXIT", "ROUNDABOUT_ENTER_AND_EXIT_CW",
        "ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE", "ROUNDABOUT_ENTER_AND_EXIT_CCW", "ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE",
        "STRAIGHT", "FERRY_BOAT", "FERRY_TRAIN", "DESTINATION", "DESTINATION_STRAIGHT", "DESTINATION_LEFT", "DESTINATION_RIGHT",
    )

    fun maneuverName(t: Int) = MANEUVER_NAMES.getOrNull(t) ?: "?$t"

    private fun legacyName(e: Int) = when (e) {
        0 -> "UNKNOWN"; 1 -> "DEPART"; 2 -> "NAME_CHANGE"; 3 -> "SLIGHT_TURN"; 4 -> "TURN"; 5 -> "SHARP_TURN"
        6 -> "U_TURN"; 7 -> "ON_RAMP"; 8 -> "OFF_RAMP"; 9 -> "FORK"; 10 -> "MERGE"; 11 -> "ROUNDABOUT_ENTER"
        12 -> "ROUNDABOUT_EXIT"; 13 -> "ROUNDABOUT_ENTER_AND_EXIT"; 14 -> "STRAIGHT"; 16 -> "FERRY_BOAT"
        17 -> "FERRY_TRAIN"; 19 -> "DESTINATION"; else -> "?$e"
    }

    private fun sideName(s: Int?) = when (s) {
        1 -> "LEFT"; 2 -> "RIGHT"; 3 -> "UNSPECIFIED"; null -> "-"; else -> "?$s"
    }

    /**
     * Как рисовать стрелку для nextTurn: вид и угол выхода в градусах (0 вверх, + вправо).
     * Рисует Android-хелпер TurnIcons; здесь только таблица, чтобы её проверял JVM-тест.
     */
    enum class GlyphKind { ARROW, U_TURN, RING, RING_OUT, DEST, START }

    class Glyph(val kind: GlyphKind, val angle: Int)

    fun glyph(nextTurn: Int): Glyph = when (nextTurn) {
        T_FRONT, T_BRANCH_CENTER, 22, T_IN_FERRY, 30 -> Glyph(GlyphKind.ARROW, 0)
        T_RIGHT_FRONT -> Glyph(GlyphKind.ARROW, 45)
        T_RIGHT -> Glyph(GlyphKind.ARROW, 90)
        T_RIGHT_BACK -> Glyph(GlyphKind.ARROW, 135)
        T_LEFT_FRONT -> Glyph(GlyphKind.ARROW, -45)
        T_LEFT -> Glyph(GlyphKind.ARROW, -90)
        T_LEFT_BACK -> Glyph(GlyphKind.ARROW, -135)
        T_BACK -> Glyph(GlyphKind.U_TURN, -180)
        T_RING -> Glyph(GlyphKind.RING, 45)
        T_RING_OUT -> Glyph(GlyphKind.RING_OUT, 45)
        T_LEFT_SIDE, T_LEFT_SIDE_MAIN, T_BRANCH_LEFT_STRAIGHT -> Glyph(GlyphKind.ARROW, -25)
        T_RIGHT_SIDE, T_RIGHT_SIDE_MAIN, T_BRANCH_RIGHT_STRAIGHT -> Glyph(GlyphKind.ARROW, 25)
        T_LEFT_SIDE_IC, T_BRANCH_LEFT -> Glyph(GlyphKind.ARROW, -40)
        T_RIGHT_SIDE_IC, T_BRANCH_RIGHT -> Glyph(GlyphKind.ARROW, 40)
        T_START -> Glyph(GlyphKind.START, 0)
        T_DEST -> Glyph(GlyphKind.DEST, 0)
        else -> Glyph(GlyphKind.ARROW, 0)
    }
}

/**
 * Решает, что и когда слать в CarLife по метаданным AA. Без блокировок: зовётся под монитором AaBridgeCore.
 * ready() - CarLife START уже был и ссылка живая; navHold(on) - держать ли MODULE_STATUS NAVI ради маршрута
 * (ядро сводит его с удержанием NAVI для TTS).
 */
class MetaRelay(
    private val log: (String, Boolean) -> Unit,
    private val clockMs: () -> Long,
    private val link: () -> CarlifeLink,
    private val ready: () -> Boolean,
    private val navHold: (Boolean) -> Unit,
    private val musicState: (Boolean) -> Unit,
) {
    // подписки ГУ (CARLIFE_DATA_SUBSCRIBE_START/STOP): музыка по умолчанию включена, навигация - только по START moduleID=0
    var musicSubscribed = true
        private set
    var navSubscribed = false
        private set

    // музыка
    var playing = false
        private set
    private var mediaSource = ""
    private var info: CarlifeMediaInfo? = null
    private var infoKey: String? = null
    private var infoPending = false            // MEDIA_INFO отложен, пока AA не играет
    private var trackSeq = 0
    private var posMs: Int? = null
    private var lastProgressMs: Int? = null
    private var lastProgressAt = NEVER

    // навигация
    var routeActive = false
        private set
    private var turn: AaNavTurn? = null
    private var turnKey: String? = null
    private var remain: AaNavDistance? = null
    private var total = 0
    private var shown = false
    private var turnDirty = false
    private var lastTurnAt = NEVER
    private var lastSent: String? = null
    private var routeEndedAt = NEVER
    private var navNewSeen = false             // в этой AA-сессии были 0x8006/0x8007
    private var legacySeen = false
    private var legacyIgnoredLogged = false

    /** Новая CarLife-сессия: подписки по умолчанию, на ГУ ещё ничего не показано. */
    fun onAttach() {
        musicSubscribed = true
        navSubscribed = false
        shown = false
        lastSent = null
        lastProgressMs = null
        lastProgressAt = NEVER
    }

    /** CarLife START (холодный): переотправить известное, ГУ могло ещё не знать трек и манёвр. */
    fun onCarlifeStart() {
        shown = false
        lastSent = null
        replayMusic("CarLife START")
        if (routeActive) {
            navHold(true)
            emitTurn(force = true, why = "CarLife START")
        }
    }

    /** SUBSCRIBE_START/STOP от ГУ: moduleID 0 навигация, 2 музыка (номера подписки, не MODULE_STATUS). */
    fun onSubscribe(moduleId: Int, on: Boolean) {
        when (moduleId) {
            SUB_NAVI -> {
                navSubscribed = on
                log("META подписка навигации: ${if (on) "START, шлю NAVI_NEXT_TURN_INFO" else "STOP"}", true)
                // до CarLife START не шлём: на START всё переотправится (onCarlifeStart)
                if (on && routeActive && ready()) {
                    navHold(true)
                    emitTurn(force = true, why = "SUBSCRIBE_START")
                }
            }
            SUB_MUSIC -> {
                musicSubscribed = on
                log("META подписка музыки: ${if (on) "START" else "STOP, MEDIA_INFO не шлю"}", true)
                if (on) replayMusic("SUBSCRIBE_START")
            }
            else -> log("META подписка moduleID=$moduleId ${if (on) "START" else "STOP"}: не поддерживаю", false)
        }
    }

    // --- музыка ---
    fun onMediaStatus(s: AaMediaStatus) {
        s.source?.takeIf { it.isNotBlank() }?.let { mediaSource = it }
        s.playbackSeconds?.let { posMs = it * PROGRESS_UNIT }
        if (s.state != null && s.playing != playing) {
            playing = s.playing
            log("META музыка: ${AaMeta.stateName(s.state)}", true)
            musicState(playing)
            if (playing && infoPending) {
                replayMusic("AA заиграл")
                return
            }
        }
        sendProgress(force = false)
    }

    fun onMediaMetadata(m: AaMediaMetadata) {
        val key = listOf(m.song, m.artist, m.album, m.durationSeconds, m.art?.contentHashCode(), m.art?.size).joinToString("|")
        if (key == infoKey) return
        // тот же трек с новой обложкой - источник прежний (ГУ различает треки по source), новый трек - новый ключ
        val sameTrack = info?.let { it.song == (m.song ?: "") && it.artist == (m.artist ?: "") && it.album == (m.album ?: "") } == true
        if (!sameTrack) trackSeq++
        infoKey = key
        info = CarlifeMediaInfo(
            source = "${mediaSource.ifEmpty { "aa" }}#$trackSeq",
            song = m.song ?: "",
            artist = m.artist ?: "",
            album = m.album ?: "",
            art = m.art,
            durationSeconds = m.durationSeconds ?: 0,
        )
        if (!sameTrack) lastProgressMs = null
        sendInfo(if (sameTrack) "обложка/поля трека обновились" else "новый трек")
    }

    private fun replayMusic(why: String) {
        lastProgressMs = null
        lastProgressAt = NEVER
        if (info != null) sendInfo(why)
        else sendProgress(force = true)
    }

    private fun sendInfo(why: String) {
        val i = info ?: return
        // трек на паузе не показываем: ГУ (Desay G6S, Mazda) считает CarLife-музыку активной и глушит радио
        if (!playing) {
            infoPending = true
            log("META MEDIA_INFO отложен ($why): AA не играет; $i", false)
            return
        }
        infoPending = false
        if (!ready() || !musicSubscribed) {
            log("META MEDIA_INFO отложен ($why): ${if (!ready()) "CarLife не START" else "музыка не подписана"}; $i", false)
            return
        }
        if (link().sendMediaInfo(i)) log("META -> MEDIA_INFO ($why): $i", true)
        sendProgress(force = true)
    }

    private fun sendProgress(force: Boolean) {
        val p = posMs ?: return
        if (!playing || !ready() || !musicSubscribed || info == null) return
        val now = clockMs()
        val last = lastProgressMs
        if (!force && last != null) {
            if (p == last) return
            // как MusicInfoSender: вперёд не чаще раза в секунду, назад (перемотка) сразу
            if (p > last && now - lastProgressAt < PROGRESS_MIN_MS) return
        }
        if (link().sendMediaProgress(p)) {
            lastProgressMs = p
            lastProgressAt = now
            log("META -> MEDIA_PROGRESS_BAR progressBar=$p (${p / PROGRESS_UNIT} с)", false)
        }
    }

    // --- навигация ---
    fun onNavCluster(start: Boolean) {
        log("META навигация: instrument cluster ${if (start) "START" else "STOP"}", true)
        if (!start) endRoute("cluster STOP")
    }

    fun onNavStatus(status: Int) {
        when (status) {
            AaMeta.NAV_ACTIVE, AaMeta.NAV_REROUTING -> startRoute("status ${AaMeta.navStatusName(status)}")
            else -> {
                if (routeActive) endRoute("status ${AaMeta.navStatusName(status)}")
                else log("META навигация: status ${AaMeta.navStatusName(status)}, маршрута нет", false)
            }
        }
    }

    /**
     * Принимать ли событие манёвра/дистанции. Один источник на AA-сессию: как только пришли новые 0x8006/0x8007,
     * legacy 0x8004/0x8005 игнорируются (иначе ключ манёвра скачет между двумя названиями). Без маршрута событие
     * стартует его неявно (телефон может не слать статус), кроме хвоста в TAIL_MS после конца маршрута.
     */
    private fun acceptNav(legacy: Boolean, what: String): Boolean {
        if (!legacy && !navNewSeen) {
            navNewSeen = true
            if (legacySeen) log("META навигация: пришли новые 0x8006/0x8007, legacy 0x8004/0x8005 дальше игнорирую", true)
        }
        if (legacy) {
            if (navNewSeen) {
                if (!legacyIgnoredLogged) log("META навигация: legacy $what игнорирую, источник - 0x8006/0x8007", false)
                legacyIgnoredLogged = true
                return false
            }
            legacySeen = true
        }
        if (!routeActive) {
            if (clockMs() - routeEndedAt < TAIL_MS) {
                log("META навигация: $what через ${clockMs() - routeEndedAt} мс после конца маршрута - хвост, игнор", false)
                return false
            }
            startRoute("пришёл $what")
        }
        return true
    }

    fun onNavTurn(t: AaNavTurn) {
        if (!acceptNav(t.legacy, "манёвр")) return
        val key = "${t.nextTurn}|${t.keyRoad}|${t.roundaboutExit}"
        val changed = key != turnKey
        turn = t
        if (changed) {
            turnKey = key
            remain = null              // старая дистанция относится к прошлому манёвру
            total = 0
            turnDirty = true           // первую дистанцию нового манёвра шлём без троттлинга
            log("META манёвр: $t", true)
        }
        emitTurn(force = changed, why = if (changed) "новый манёвр" else "манёвр повторён")
    }

    fun onNavDistance(d: AaNavDistance) {
        if (!acceptNav(d.legacy, "дистанция")) return
        // два одинаковых поворота подряд (тот же nextTurn|road|exit): ключ не меняется, новый манёвр виден только
        // по скачку дистанции вверх (h137: 10 -> 170 м). Иначе total остаётся от прошлого поворота.
        val prev = remain
        if (prev != null && d.meters - prev.meters >= NEW_TURN_JUMP_M) {
            total = 0
            turnDirty = true
            log("META манёвр: тот же ключ, дистанция ${prev.meters} -> ${d.meters} м - новый поворот", true)
        }
        remain = d
        // totalDistance - дистанция на момент смены манёвра (Baidu фиксирует при смене дороги)
        if (total == 0 || d.meters > total) total = d.meters
        emitTurn(force = false, why = "дистанция")
    }

    private fun startRoute(why: String) {
        if (routeActive) return
        routeActive = true
        shown = false
        lastSent = null
        log("META маршрут начат ($why)", true)
        // NAVI=1 без подписки: VW BT 1538 (h244) шлёт SUBSCRIBE_START только после NAVI=1
        if (ready()) navHold(true)
    }

    private fun endRoute(why: String) {
        if (!routeActive) return
        routeActive = false
        routeEndedAt = clockMs()
        log("META маршрут закончен ($why)", true)
        if (shown && ready() && navSubscribed) {
            val end = NaviTurnInfo(ACTION_END, AaMeta.T_DEST, AaMeta.END_ROAD, 0, 0, 0)
            if (link().sendNaviTurn(end)) log("META -> NAVI_NEXT_TURN_INFO $end", true)
        }
        shown = false
        turnDirty = false
        turn = null
        turnKey = null
        remain = null
        total = 0
        lastSent = null
        navHold(false)
    }

    private fun emitTurn(force: Boolean, why: String) {
        val t = turn ?: return
        val d = remain ?: return                    // без дистанции не шлём: на ГУ было бы «0 м»
        if (!ready() || !navSubscribed) return
        val now = clockMs()
        val idle = now - lastTurnAt
        if (!force && !turnDirty && idle < TURN_MIN_MS) return
        // h137 (Honda Civic, пустой HU_INFO): после ~40 с без пакетов приборка гасит подсказку и на UPDATE не оживает
        val reshow = shown && idle >= NAVI_RESHOW_MS
        val info = NaviTurnInfo(
            if (shown && !reshow) ACTION_UPDATE else ACTION_SHOW, t.nextTurn, t.road, maxOf(total, d.meters), d.meters, d.timeSeconds ?: 0
        )
        val sig = "${info.action}|${info.nextTurn}|${info.roadName}|${info.totalDistance}|${info.remainDistance}|${info.timeSeconds}"
        // на стоянке AA шлёт одно и то же: повторяем раз в NAVI_KEEPALIVE_MS, чтобы ГУ не решила, что маршрута нет
        val keepalive = sig == lastSent
        if (keepalive && idle < NAVI_KEEPALIVE_MS) return
        if (link().sendNaviTurn(info)) {
            val loud = !shown || turnDirty || reshow
            val tag = if (reshow) "$why, повторный показ после ${idle / 1000} с" else if (keepalive) "$why, keepalive" else why
            shown = true
            turnDirty = false
            lastTurnAt = now
            lastSent = sig
            log("META -> NAVI_NEXT_TURN_INFO ($tag) $info", loud)
        }
    }

    /** AA-сессия закончилась: маршрут и плеер исчезли вместе с ней. */
    fun onAaEnded() {
        endRoute("AA-сессия закончилась")
        navNewSeen = false
        legacySeen = false
        legacyIgnoredLogged = false
        routeEndedAt = NEVER
        if (playing) {
            playing = false
            musicState(false)
        }
        posMs = null
        // трек умер вместе с AA: после рестарта не показывать протухший
        info = null
        infoKey = null
        infoPending = false
    }

    companion object {
        const val SUB_NAVI = 0
        const val SUB_MUSIC = 2

        const val ACTION_SHOW = 1
        const val ACTION_UPDATE = 2
        const val ACTION_END = 3

        /**
         * progressBar в мс: MusicInfoSender (обычный канал, не isSecondVehi) шлёт curTime в мс и троттлит по разнице < 1000.
         * duration в MEDIA_INFO - в секундах. Если ГУ покажет прогресс в 1000 раз больше - поставить 1.
         */
        @Volatile @JvmStatic var PROGRESS_UNIT = 1000

        const val PROGRESS_MIN_MS = 1000L
        const val TURN_MIN_MS = 1000L
        /** Повтор неизменного манёвра, пока маршрут активен (на стоянке AA шлёт те же данные, дедуп их глушил). */
        const val NAVI_KEEPALIVE_MS = 5000L
        /** Пауза дольше этой: следующий пакет снова SHOW, ГУ могла уже убрать подсказку. */
        const val NAVI_RESHOW_MS = 10_000L
        /** Рост дистанции при том же ключе манёвра: это уже следующий такой же поворот (шаг AA - 10 м). */
        const val NEW_TURN_JUMP_M = 40

        /** Запоздалые манёвр/дистанция после конца маршрута не стартуют его заново в этом окне. */
        const val TAIL_MS = 5000L
        private const val NEVER = Long.MIN_VALUE / 4
    }
}
