package com.yegorkapomidorka.headswap.launcher

import kotlin.math.max

/** Страницы вместо скролла: ни свайпов, ни прокрутки (тач по ГУ идёт с задержкой). */
object Pager {
    fun pageCount(total: Int, perPage: Int): Int =
        if (perPage <= 0 || total <= 0) 1 else (total + perPage - 1) / perPage

    fun clamp(page: Int, total: Int, perPage: Int): Int = page.coerceIn(0, pageCount(total, perPage) - 1)

    fun <T> slice(items: List<T>, page: Int, perPage: Int): List<T> {
        if (perPage <= 0) return emptyList()
        val from = clamp(page, items.size, perPage) * perPage
        return items.subList(from.coerceAtMost(items.size), (from + perPage).coerceAtMost(items.size))
    }
}

/** Сколько колонок и строк влезает в область при минимальной ячейке. */
object GridPlan {
    data class Plan(val cols: Int, val rows: Int) { val perPage: Int get() = cols * rows }

    fun of(availW: Int, availH: Int, minCellW: Int, minCellH: Int, gap: Int): Plan =
        Plan(max(1, (availW + gap) / (minCellW + gap)), max(1, (availH + gap) / (minCellH + gap)))
}

enum class Press { TAP, LONG }

/** Тап или долгий тап: считаем по времени между DOWN и UP, а не по таймеру (на ГУ UP приходит с задержкой). */
object PressLogic {
    const val LONG_MS = 600L
    const val DEBOUNCE_MS = 400L
    const val COLD_START_MS = 8000L

    fun classify(downMs: Long, upMs: Long, allowLong: Boolean): Press =
        if (allowLong && upMs - downMs >= LONG_MS) Press.LONG else Press.TAP
}

/** Антидребезг кнопок запуска: второй тап ближе gapMs к первому игнорируется. */
class Debounce(private val gapMs: Long = PressLogic.DEBOUNCE_MS) {
    private var last = Long.MIN_VALUE

    fun allow(now: Long): Boolean {
        if (last != Long.MIN_VALUE && now - last in 0 until gapMs) return false
        last = now
        return true
    }
}

/** Холодный старт приложения: блокируем повторный запуск, пока оно не появилось или не прошло timeoutMs. */
class LaunchLock(private val timeoutMs: Long = PressLogic.COLD_START_MS) {
    var key: String? = null
        private set
    private var since = 0L

    fun begin(key: String, now: Long) { this.key = key; since = now }
    fun finish() { key = null }
    fun isLocked(now: Long): Boolean {
        if (key == null) return false
        if (now - since >= timeoutMs) { key = null; return false }
        return true
    }
}

/** Приложение пользователя в списке лаунчера: компонент, а не пакет (в пакете может быть несколько входов). */
data class AppRef(val pkg: String, val cls: String) {
    val key: String get() = "$pkg/$cls"

    companion object {
        fun parse(key: String): AppRef? {
            val i = key.indexOf('/')
            if (i <= 0 || i == key.length - 1) return null
            return AppRef(key.substring(0, i), key.substring(i + 1))
        }
    }
}

/** Персистентный список приложений: порядок важен, дублей нет. */
object AppList {
    fun encode(list: List<AppRef>): String = list.joinToString("\n") { it.key }
    fun decode(raw: String?): List<AppRef> =
        raw.orEmpty().split('\n').mapNotNull { AppRef.parse(it.trim()) }.distinct()

    fun add(list: List<AppRef>, ref: AppRef): List<AppRef> = if (ref in list) list else list + ref
    fun remove(list: List<AppRef>, ref: AppRef): List<AppRef> = list - ref
    /** Нижний ряд главного = первые n приложений списка. */
    fun quick(list: List<AppRef>, n: Int): List<AppRef> = list.take(n)
}

/**
 * Списки экранов приложений из каталога установленных (порядок и фильтр нашего пакета уже сделал DesktopAppCatalog).
 * «Все приложения» - весь каталог, выбор для нижнего ряда - каталог без избранных.
 */
object CatalogLists {
    fun all(catalog: List<AppRef>): List<AppRef> = catalog.distinct()
    fun pickable(catalog: List<AppRef>, favorites: List<AppRef>): List<AppRef> = catalog.distinct().filter { it !in favorites }
}

/** Выбор навигатора по умолчанию и проверка сохранённого. */
object NavigatorPick {
    const val YANDEX_NAVI = "ru.yandex.yandexnavi"
    val KNOWN = listOf(YANDEX_NAVI, "ru.yandex.yandexmaps", "ru.dublgis.dgismobile",
        "com.google.android.apps.maps", "com.waze", "app.organicmaps")

    fun defaultFor(installed: Set<String>): String? = KNOWN.firstOrNull { it in installed }

    /** Сохранённый, пока он установлен; иначе по умолчанию. null - выбрать нечего. */
    fun effective(saved: String?, installed: Set<String>): String? =
        saved?.takeIf { it in installed } ?: defaultFor(installed)
}

/** Ответы taskState дочернего дисплея и решения по ним (EmbedHost): что делать после Back и при повторном показе. */
object TaskCheck {
    const val UNKNOWN = -1
    const val NONE = 0
    const val BACKGROUND = 1
    const val TOP = 2

    enum class AfterBack { CLOSED, STAYED_MORE, STAYED, UNKNOWN_MORE, UNKNOWN }

    /** Итог очередной проверки после Back; [last] - проверка была последней. */
    fun afterBack(state: Int, last: Boolean): AfterBack = when {
        state == NONE || state == BACKGROUND -> AfterBack.CLOSED
        state == TOP -> if (last) AfterBack.STAYED else AfterBack.STAYED_MORE
        last -> AfterBack.UNKNOWN
        else -> AfterBack.UNKNOWN_MORE
    }

    /** Окно показали снова: запускать приложение заново, если его нет сверху; -1 считается только после Back с неизвестным итогом. */
    fun needsRelaunch(state: Int, stale: Boolean): Boolean =
        state == NONE || state == BACKGROUND || (state < 0 && stale)

    /** Приложение исчезло с дисплея: «нет задачи» после того, как оно уже было сверху (до этого оно могло просто не появиться). */
    fun vanished(state: Int, seenTop: Boolean): Boolean = state == NONE && seenTop
}

/**
 * Чип плееров на карточке музыки (макет v2): плеер телефона (любая активная сессия) и два известных сервиса,
 * если установлены. Остальные медиаприложения - долгий тап по «ноте» (список плееров).
 */
object PlayerChip {
    const val YANDEX = "ru.yandex.music"
    const val YT_MUSIC = "com.google.android.apps.youtube.music"

    enum class Kind { PHONE, YANDEX, YT_MUSIC }
    data class Slot(val kind: Kind, val pkg: String?, val selected: Boolean)

    fun slots(installed: Set<String>, chosen: String?): List<Slot> = buildList {
        add(Slot(Kind.PHONE, null, chosen == null))
        if (YANDEX in installed) add(Slot(Kind.YANDEX, YANDEX, chosen == YANDEX))
        if (YT_MUSIC in installed) add(Slot(Kind.YT_MUSIC, YT_MUSIC, chosen == YT_MUSIC))
    }
}

/** Подпись типа сети в рейке. Типы - константы TelephonyManager.NETWORK_TYPE_*. */
object NetLabel {
    const val NONE = "нет"
    private const val NR = 20
    private const val LTE = 13
    private val G3 = setOf(3, 8, 9, 10, 15, 17)
    private val G2 = setOf(1, 2, 4, 7, 11, 16)

    fun cellular(type: Int?): String = when {
        type == NR -> "5G"
        type == LTE -> "4G"
        type in G3 -> "3G"
        type in G2 -> "2G"
        else -> "4G"
    }
}

/** Вид центральной карточки на месте навигации (иконка «слои» переключает по кругу). */
enum class CardView { NAVIGATOR, CLOCK;
    fun next(): CardView = entries[(ordinal + 1) % entries.size]

    companion object {
        fun restore(name: String?): CardView = entries.firstOrNull { it.name == name } ?: NAVIGATOR
    }
}
