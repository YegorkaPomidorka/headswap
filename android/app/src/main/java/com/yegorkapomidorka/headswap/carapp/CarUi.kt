package com.yegorkapomidorka.headswap.carapp

import java.util.Locale
import kotlin.math.roundToInt

/*
 * Что рисует лаунчер поверх карты навигатора: шаблон Car App Library, переведённый в простые значения
 * (CarUiMapper). Здесь - только модель и чистые правила, без Android-типов: считается в JVM-тестах.
 * Компоновка - как в AA, по макету NavHost.dc.html (утверждён Егором 04.11).
 */

/** Нажатие на элемент шаблона: уходит приложению на потоке хоста. */
fun interface CarClick { fun send() }

/**
 * Иконка приложения. [key] - стабильный ключ для кэша картинок, [load] - достать картинку (android.graphics.Bitmap
 * в рантайме; в тестах не зовётся). [standard] - стандартная иконка хоста (CarIcon.TYPE_BACK и т.п.), 0 - своя.
 */
class CarImage(val key: String, val standard: Int, val tint: CarTint?, val load: () -> Any?)

/** CarColor: тип стандартного цвета или свой (argb день/ночь). Рисуется только в стиле «как в приложении». */
data class CarTint(val type: Int, val color: Int, val colorDark: Int)

class CarAction(
    val title: String,
    val icon: CarImage?,
    /** Action.TYPE_*: 65538 APP_ICON, 65539 BACK, 65540 PAN, 1 - своя кнопка. */
    val type: Int,
    val primary: Boolean,
    val enabled: Boolean,
    val background: CarTint?,
    val click: CarClick?,
)

class CarToggle(val checked: Boolean, val enabled: Boolean, val change: ((Boolean) -> Unit)?)

class CarRow(
    val title: String,
    /** Строки под заголовком, спаны расстояний и времени уже подставлены. */
    val texts: List<String>,
    val image: CarImage?,
    val toggle: CarToggle?,
    val browsable: Boolean,
    /** Номер метки на карте (Row.numericDecoration), 0 - нет. */
    val number: Int,
    val click: CarClick?,
)

class CarGridItem(val title: String, val text: String, val image: CarImage?, val loading: Boolean, val click: CarClick?)

class CarManeuver(
    /** Иконка манёвра от самого приложения (2ГИС шлёт PNG 64×64); null - рисуем свою по [type]. */
    val icon: CarImage?,
    val type: Int,
    val distance: String,
    val road: String,
    val roundaboutExit: Int,
)

class CarEta(val arrival: String, val time: String, val distance: String, val timeColor: CarTint?)

sealed interface CarUi {
    /** Карта с маршрутом или без: манёвр слева вверху, прибытие слева внизу, действия справа. */
    class Nav(
        val maneuver: CarManeuver?,
        val eta: CarEta?,
        val loading: Boolean,
        /** Верхний ряд справа - действия с иконками. */
        val actions: List<CarAction>,
        /** «Завершить»: действие без иконки, рисуется крестиком в карточке прибытия. */
        val end: CarAction?,
        val mapActions: List<CarAction>,
        val background: CarTint?,
    ) : CarUi

    /** Панель слева поверх карты: список мест, варианты маршрута, карточка места / настройки (Pane). */
    class Panel(
        val title: String,
        val back: CarAction?,
        val rows: List<CarRow>,
        val selected: Int,
        val select: ((Int) -> Unit)?,
        /** Кнопки внизу панели: «Позвонить», «В путь» (primary - акцентом). */
        val buttons: List<CarAction>,
        val actions: List<CarAction>,
        val mapActions: List<CarAction>,
        val loading: Boolean,
        val empty: String,
    ) : CarUi

    /** Сетка на всё окно без карты. */
    /** [actions] - кнопки шапки справа (Яндекс: значок поиска с полем ввода, «История»). */
    class Grid(val title: String, val back: CarAction?, val items: List<CarGridItem>, val loading: Boolean,
        val actions: List<CarAction> = emptyList()) : CarUi

    /** Поиск на всё окно: строка ввода, история/подсказки строками, клавиатура ГУ. */
    class Search(
        val hint: String,
        val initial: String,
        val back: CarAction?,
        val rows: List<CarRow>,
        val showKeyboard: Boolean,
        val loading: Boolean,
        val changed: ((String) -> Unit)?,
        val submitted: ((String) -> Unit)?,
    ) : CarUi

    /** Шаблон, который мы не рисуем: карта без наших элементов, в лог - имя. */
    class Unknown(val name: String) : CarUi
}

/** Единицы для подписей; из ресурсов лаунчера (ru/en). */
class CarUnits(val m: String, val km: String, val ft: String, val mi: String, val yd: String, val min: String, val h: String)

object CarFormat {
    /**
     * Distance как его задумало приложение: displayDistance в displayUnit (UNIT_KILOMETERS_P1 - с одним знаком после
     * запятой). Дробная часть через запятую, как в 2ГИС и макете («4,3 км»).
     */
    fun distance(value: Double, unit: Int, u: CarUnits): String {
        val (text, name) = when (unit) {
            CarTrip.UNIT_KILOMETERS -> whole(value) to u.km
            CarTrip.UNIT_KILOMETERS_P1 -> oneDecimal(value) to u.km
            CarTrip.UNIT_MILES -> whole(value) to u.mi
            CarTrip.UNIT_MILES_P1 -> oneDecimal(value) to u.mi
            CarTrip.UNIT_FEET -> whole(value) to u.ft
            CarTrip.UNIT_YARDS -> whole(value) to u.yd
            else -> whole(value) to u.m
        }
        return "$text $name"
    }

    private fun whole(v: Double) = v.roundToInt().toString()

    private fun oneDecimal(v: Double): String {
        val tenths = (v * 10).roundToInt()
        return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10},${kotlin.math.abs(tenths % 10)}"
    }

    /** «10 мин», «1 ч 5 мин», «2 ч»; меньше минуты - «1 мин». Отрицательное (неизвестно) - пусто. */
    fun duration(seconds: Long, u: CarUnits): String {
        if (seconds < 0) return ""
        val minutes = ((seconds + 59) / 60).coerceAtLeast(1)
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "$m ${u.min}"
            m == 0L -> "$h ${u.h}"
            else -> "$h ${u.h} $m ${u.min}"
        }
    }

    /** Время прибытия «ЧЧ:ММ» в часовом поясе, который прислало приложение. */
    fun clock(epochMillis: Long, zoneOffsetSeconds: Int): String {
        val local = Math.floorMod(epochMillis / 1000 + zoneOffsetSeconds, 86_400L)
        return "%02d:%02d".format(Locale.ROOT, local / 3600, local % 3600 / 60)
    }
}

/** Как раскладывать действия NavigationTemplate (одинаково для любого навигатора). */
object CarActions {
    const val TYPE_CUSTOM = 1
    const val TYPE_APP_ICON = 65538
    const val TYPE_BACK = 65539
    const val TYPE_PAN = 65540

    /**
     * «Завершить» - последнее действие actionStrip без иконки, но с подписью (2ГИС: «Завершить»; так же у Google Карт
     * в AA). Рисуется крестиком в карточке прибытия; остальные - кругами справа вверху.
     */
    fun <T> splitEnd(actions: List<T>, hasIcon: (T) -> Boolean, title: (T) -> String): Pair<List<T>, T?> {
        val end = actions.lastOrNull { !hasIcon(it) && title(it).isNotBlank() } ?: return actions to null
        return actions.filter { it !== end } to end
    }

    /** PAN (перетаскивание карты) у нас - жест по карте, кнопка не нужна. */
    fun <T> visibleMapActions(actions: List<T>, type: (T) -> Int): List<T> = actions.filter { type(it) != TYPE_PAN }
}
