package com.yegorkapomidorka.headswap.carapp

import androidx.car.app.navigation.model.Maneuver
import com.yegorkapomidorka.headswap.aa.AaMeta
import kotlin.math.roundToInt

/*
 * Чистая JVM-логика своего хоста Car App Library: без Android-типов, считается в тестах.
 * Сам хост - CarAppHost / CarAppSession, поиск навигаторов - CarAppCatalog.
 */

/** Прямоугольник в px поверхности навигатора (android.graphics.Rect в JVM-тестах пустой). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Области карты для приложения (onVisibleAreaChanged / onStableAreaChanged), как у хоста AA.
 * visible - карта без наших карточек (манёвр, прибытие, панели слева); stable - ещё без полос кнопок.
 * Инвариант: visible != stable. Равные области 2ГИС (libv4android, MapWidget*::calculatePosition) понимает как
 * «хост ничего не рисует» и прячет спидометр с камерами и мини-карту.
 */
object CarAreas {
    /** Доля высоты, на которую stable ниже visible, если полос кнопок нет (проверено на стенде 04.11). */
    const val MIN_TOP_SHARE = 0.15f

    /**
     * [leftPx] - сколько px слева занято нашими карточками/панелью, [rightPx] - полоса кнопок справа,
     * [topPx] - полоса сверху над картой. Всё в px поверхности; лишнее обрезается по размеру.
     */
    fun of(w: Int, h: Int, leftPx: Int, rightPx: Int, topPx: Int): Pair<Box, Box> =
        of(Box(0, 0, w, h), leftPx, rightPx, topPx)

    /** То же внутри [frame] - части поверхности, которую видно (карточка показывает вырезку полноэкранной карты). */
    fun of(frame: Box, leftPx: Int, rightPx: Int, topPx: Int): Pair<Box, Box> {
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return Box(0, 0, 0, 0) to Box(0, 0, 0, 0)
        val left = leftPx.coerceIn(0, w - 1)
        val right = (w - rightPx.coerceAtLeast(0)).coerceIn(left + 1, w)
        val top = topPx.coerceIn(0, h - 2)
        // stable ниже visible: равные области 2ГИС понимает как «хост ничего не рисует»
        val stableTop = (top + (h * MIN_TOP_SHARE).roundToInt()).coerceIn(top + 1, h - 1)
        val x = frame.left
        val y = frame.top
        // кнопки - всегда на экране: вне и visible (иначе лимит и мини-карта 2ГИС уходят под них, стенд 04.11)
        return Box(x + left, y + top, x + right, y + h) to Box(x + left, y + stableTop, x + right, y + h)
    }

    /** Вырезка размера карточки [cw]x[ch] из центра поверхности [w]x[h]; карточка больше - вся поверхность. */
    fun crop(w: Int, h: Int, cw: Int, ch: Int): Box {
        val x = ((w - cw) / 2).coerceAtLeast(0)
        val y = ((h - ch) / 2).coerceAtLeast(0)
        return Box(x, y, x + minOf(cw, w), y + minOf(ch, h))
    }

    /** Пока рендер не сообщил свою геометрию: как в пробнике (карточки слева 36 %, кнопки справа 10 %, stable ниже на 15 %). */
    fun default(w: Int, h: Int): Pair<Box, Box> =
        of(w, h, (w * 0.36f).roundToInt(), (w * 0.10f).roundToInt(), 0)
}

/** Числа Configuration для приложения: размер в dp, плотность. */
object CarConfigMath {
    const val MIN_DPI = 80
    const val MAX_DPI = 640

    /**
     * База ступеней «Масштаба карты», постоянная. Не плотность дисплея рабочего стола: та растёт с его высотой
     * (200 на 720, чтобы клавиатура не делилась), и ступень, выбранная юзером, поплыла бы.
     */
    const val BASE_DPI = 160

    /** dpi поверхности навигатора: BASE_DPI × ступень масштаба карты. */
    fun dpi(scale: Float): Int =
        (BASE_DPI * (if (scale > 0f) scale else 1f)).roundToInt().coerceIn(MIN_DPI, MAX_DPI)

    fun dp(px: Int, dpi: Int): Int = if (px <= 0 || dpi <= 0) 0 else px * 160 / dpi

    /**
     * Ступени «Масштаба карты» к плотности окна: Мельче / Авто / Крупнее / Ещё крупнее (макет SettingsNav: ×0,75 / ×1 /
     * ×1,25 / ×1,5 от «Авто»). Потолок «Ещё крупнее» - BASE_DPI (160 dpi на 1280×720, Егор 04.11 на стенде),
     * «Авто» = 160 / 1,5 ≈ 107.
     */
    val STEPS = floatArrayOf(0.5f, 2f / 3f, 5f / 6f, 1f)
    const val AUTO_STEP = 1

    fun step(i: Int): Float = STEPS[i.coerceIn(0, STEPS.size - 1)]
}

/**
 * Навигаторы, которые читают dpi («Масштаб карты») только при старте сессии: при смене ступени сессию перезапускаем.
 * Яндекс Навигатор и Яндекс Карты: стенд 04.11, dpi 107 -> 160 -> 80 (Карты 107 -> 240 -> 80 -> 240) на одной сессии
 * карту не меняли, после перезапуска - меняли.
 */
object CarScaleRestart {
    val PACKAGES = setOf("ru.yandex.yandexnavi", "ru.yandex.yandexmaps")
    fun needed(pkg: String?): Boolean = pkg != null && pkg in PACKAGES
}

/** Отказ хоста или приложения: навигатор показываем обычным окном. */
enum class CarAppRefusal { BIND, PERMISSION, NO_APP_INFO, API_LEVEL, HANDSHAKE, CREATE, FINISHED, DIED }

/** Что знаем о навигаторе на Car App Library. */
data class CarAppTarget(val pkg: String, val service: String, val versionCode: Long, val permission: String?)

/**
 * Вердикт «не поддерживается» по пакету и его versionCode: без него reconcile окна перебиндивал бы приложение на
 * каждом проходе. Обновление приложения (новый versionCode) даёт ему ещё попытку.
 */
class CarAppVerdicts {
    private val refused = HashMap<String, Pair<Long, CarAppRefusal>>()

    @Synchronized fun refuse(t: CarAppTarget, why: CarAppRefusal) { refused[t.pkg] = t.versionCode to why }
    @Synchronized fun refusal(t: CarAppTarget): CarAppRefusal? = refused[t.pkg]?.takeIf { it.first == t.versionCode }?.second
    @Synchronized fun clear(pkg: String) { refused.remove(pkg) }
}

/** Выбор сервиса навигатора из ответа queryIntentServices (action CarAppService + категория NAVIGATION). */
object CarAppPick {
    /** Первый сервис каждого пакета; свой пакет - мимо (мы хост, не приложение). */
    fun targets(found: List<CarAppTarget>, ownPkg: String): Map<String, CarAppTarget> =
        found.filter { it.pkg != ownPkg }.groupBy { it.pkg }.mapValues { it.value.first() }

    /** API, на котором говорим с приложением: его latest, но не выше нашего; null - его min выше нашего. */
    fun apiLevel(appMin: Int?, appLatest: Int?, hostLatest: Int): Int? {
        if (appMin != null && appMin > hostLatest) return null
        return minOf(appLatest ?: appMin ?: 1, hostLatest).coerceAtLeast(1)
    }
}

/** Trip Car App Library -> подсказки CarLife (та же таблица, что для AA 0x8006). */
object CarTrip {
    // Distance.UNIT_*: константы Car App Library, продублированы, чтобы считать без её классов
    const val UNIT_METERS = 1
    const val UNIT_KILOMETERS = 2
    const val UNIT_KILOMETERS_P1 = 3
    const val UNIT_MILES = 4
    const val UNIT_MILES_P1 = 5
    const val UNIT_FEET = 6
    const val UNIT_YARDS = 7

    fun meters(value: Double, unit: Int): Int = when (unit) {
        UNIT_KILOMETERS, UNIT_KILOMETERS_P1 -> value * 1000
        UNIT_MILES, UNIT_MILES_P1 -> value * 1609.344
        UNIT_FEET -> value * 0.3048
        UNIT_YARDS -> value * 0.9144
        else -> value
    }.roundToInt()

    fun turnFor(type: Int): Int = when (type) {
        Maneuver.TYPE_DEPART -> AaMeta.T_START
        Maneuver.TYPE_KEEP_LEFT -> AaMeta.T_BRANCH_LEFT_STRAIGHT
        Maneuver.TYPE_KEEP_RIGHT -> AaMeta.T_BRANCH_RIGHT_STRAIGHT
        Maneuver.TYPE_TURN_SLIGHT_LEFT -> AaMeta.T_LEFT_FRONT
        Maneuver.TYPE_TURN_SLIGHT_RIGHT -> AaMeta.T_RIGHT_FRONT
        Maneuver.TYPE_TURN_NORMAL_LEFT -> AaMeta.T_LEFT
        Maneuver.TYPE_TURN_NORMAL_RIGHT -> AaMeta.T_RIGHT
        Maneuver.TYPE_TURN_SHARP_LEFT -> AaMeta.T_LEFT_BACK
        Maneuver.TYPE_TURN_SHARP_RIGHT -> AaMeta.T_RIGHT_BACK
        Maneuver.TYPE_U_TURN_LEFT, Maneuver.TYPE_U_TURN_RIGHT,
        Maneuver.TYPE_ON_RAMP_U_TURN_LEFT, Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT -> AaMeta.T_BACK
        Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT, Maneuver.TYPE_ON_RAMP_NORMAL_LEFT, Maneuver.TYPE_ON_RAMP_SHARP_LEFT -> AaMeta.T_LEFT_SIDE_IC
        Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT, Maneuver.TYPE_ON_RAMP_SHARP_RIGHT -> AaMeta.T_RIGHT_SIDE_IC
        Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT, Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT -> AaMeta.T_LEFT_SIDE
        Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT -> AaMeta.T_RIGHT_SIDE
        Maneuver.TYPE_FORK_LEFT -> AaMeta.T_BRANCH_LEFT
        Maneuver.TYPE_FORK_RIGHT -> AaMeta.T_BRANCH_RIGHT
        Maneuver.TYPE_MERGE_LEFT -> AaMeta.T_LEFT_SIDE_MAIN
        Maneuver.TYPE_MERGE_RIGHT -> AaMeta.T_RIGHT_SIDE_MAIN
        Maneuver.TYPE_ROUNDABOUT_ENTER_CW, Maneuver.TYPE_ROUNDABOUT_ENTER_CCW,
        Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
        Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE -> AaMeta.T_RING
        Maneuver.TYPE_ROUNDABOUT_EXIT_CW, Maneuver.TYPE_ROUNDABOUT_EXIT_CCW -> AaMeta.T_RING_OUT
        Maneuver.TYPE_FERRY_BOAT, Maneuver.TYPE_FERRY_TRAIN, Maneuver.TYPE_FERRY_BOAT_LEFT, Maneuver.TYPE_FERRY_BOAT_RIGHT,
        Maneuver.TYPE_FERRY_TRAIN_LEFT, Maneuver.TYPE_FERRY_TRAIN_RIGHT -> AaMeta.T_IN_FERRY
        Maneuver.TYPE_DESTINATION, Maneuver.TYPE_DESTINATION_STRAIGHT, Maneuver.TYPE_DESTINATION_LEFT,
        Maneuver.TYPE_DESTINATION_RIGHT -> AaMeta.T_DEST
        else -> AaMeta.T_FRONT
    }
}

/** Касание окна навигатора -> onClick / onScroll приложения: протяжка начинается после [SLOP] px. */
class CarTouch {
    sealed interface Out
    data class Click(val x: Float, val y: Float) : Out
    data class Scroll(val dx: Float, val dy: Float) : Out

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var down = false

    /** action как у MotionEvent: 0 DOWN, 1 UP, 2 MOVE, 3 CANCEL; второй палец и прочее - мимо. */
    fun feed(action: Int, x: Float, y: Float): Out? = when (action) {
        0 -> { downX = x; downY = y; lastX = x; lastY = y; dragging = false; down = true; null }
        2 -> if (!down) null else {
            if (!dragging && (kotlin.math.abs(x - downX) > SLOP || kotlin.math.abs(y - downY) > SLOP)) dragging = true
            if (dragging) Scroll(lastX - x, lastY - y).also { lastX = x; lastY = y } else null
        }
        1 -> if (down && !dragging) { down = false; Click(x, y) } else { down = false; null }
        3 -> { down = false; dragging = false; null }
        else -> null
    }

    companion object { const val SLOP = 12f }
}
