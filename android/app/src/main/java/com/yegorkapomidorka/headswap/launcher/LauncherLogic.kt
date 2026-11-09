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

/**
 * Двойное «Назад» в окне приложения - на главный. Teams и другие на своём первом экране глотают Back: задача остаётся
 * сверху, а «Домой» на рейке нет (Егор 04.11). Отличить первый экран от внутреннего мы не можем, поэтому второе
 * нажатие в пределах [WINDOW_MS] уводит на главный всегда; приложение остаётся в фоне.
 */
object DoubleBack {
    const val WINDOW_MS = 1500L
    fun isDouble(prevAt: Long, now: Long): Boolean = prevAt > 0 && now - prevAt in 0..WINDOW_MS
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
    const val YANDEX_MAPS = "ru.yandex.yandexmaps"
    const val GOOGLE_MAPS = "com.google.android.apps.maps"
    val KNOWN = listOf(YANDEX_NAVI, YANDEX_MAPS, "ru.dublgis.dgismobile",
        GOOGLE_MAPS, "com.waze", "app.organicmaps")

    /** Машинная часть только с подпиской Яндекс Плюс (у Карт тот же NavigationCarAppService, что у Навигатора). */
    fun needsPlus(pkg: String?): Boolean = pkg == YANDEX_NAVI || pkg == YANDEX_MAPS

    /**
     * «Подсказки на приборке» по умолчанию (h800, ADR 0028). Вкл - машинная часть на своём хосте Car App или теневая AA;
     * выкл - навигатор окном, как на телефоне. По умолчанию выкл у всех (Егор 05.11): окно работает без Плюса и без AA.
     */
    @Suppress("UNUSED_PARAMETER")
    fun hintsDefault(pkg: String): Boolean = false

    /** Теневую AA держим, пока у выбранного навигатора подсказки включены; навигатора нет - как раньше, держим. */
    fun shadowWanted(metaNavi: Boolean, navPkg: String?, hints: (String) -> Boolean): Boolean =
        metaNavi && (navPkg == null || hints(navPkg))

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
    /** Приложение осталось первой задачей, но не видно: Back из корня сделал moveTaskToBack. */
    const val MOVED_TO_BACK = 3

    /** Пауза до каждой проверки задачи после Back: 300, 700, 1100, 1500 мс от Back. */
    val BACK_CHECKS = longArrayOf(300, 400, 400, 400)
    /** Дольше этого после Back касания не блокируются, даже если состояние неизвестно: чёрный экран без кликов хуже лишнего касания. */
    const val BACK_BLOCK_MS = 1000L

    /** UNKNOWN: все проверки без ответа (-1), считаем, что приложение ушло из корня; вызывающий закрывает слой, как при CLOSED. */
    enum class AfterBack { CLOSED, STAYED_MORE, STAYED, UNKNOWN_MORE, UNKNOWN }

    /** Итог очередной проверки после Back; [last] - проверка была последней. */
    fun afterBack(state: Int, last: Boolean): AfterBack = when {
        state == NONE || state == BACKGROUND || state == MOVED_TO_BACK -> AfterBack.CLOSED
        state == TOP -> if (last) AfterBack.STAYED else AfterBack.STAYED_MORE
        last -> AfterBack.UNKNOWN
        else -> AfterBack.UNKNOWN_MORE
    }

    /** Касания в окно не идут первую секунду после Back ([backAt] - uptime Back, 0 - Back не было). */
    fun blockedAfterBack(now: Long, backAt: Long): Boolean = backAt != 0L && now - backAt in 0 until BACK_BLOCK_MS

    /** Окно показали снова: запускать приложение заново, если его нет сверху; -1 считается только после Back с неизвестным итогом. */
    fun needsRelaunch(state: Int, stale: Boolean): Boolean =
        state == NONE || state == BACKGROUND || state == MOVED_TO_BACK || (state < 0 && stale)

    /** Приложение исчезло с дисплея: «нет задачи» после того, как оно уже было сверху (до этого оно могло просто не появиться). */
    fun vanished(state: Int, seenTop: Boolean): Boolean = state == NONE && seenTop
}

/**
 * «Назад» на экранах лаунчера (без кнопки «Главный»): на предыдущий экран лаунчера, если он был не главным, иначе на главный.
 * Та же кнопка рейки, что открыла экран (логотип, шестерёнка), при повторном нажатии тоже ведёт назад.
 */
object BackNav {
    /** Экраны лаунчера, куда можно вернуться Назад; встроенные приложения и главный не запоминаются. */
    fun isScreen(area: Area): Boolean =
        area == Area.AllApps || area == Area.Picker || area == Area.Settings || area == Area.Calls || area == Area.Players

    /** Что запомнить как «предыдущий» при уходе с [from]. */
    fun remembered(from: Area): Area? = from.takeIf(::isScreen)

    /** Куда ведёт Назад с экрана [current]; [previous] - запомненный предыдущий экран. */
    fun target(current: Area, previous: Area?): Area = previous?.takeIf { isScreen(it) && it != current } ?: Area.Home

    /**
     * Шаг «Назад» внутри настроек: текст лицензии -> список лицензий -> «Система». Результат - страница и открыт ли текст;
     * null - шагов внутри настроек нет, «Назад» выходит с экрана настроек.
     */
    fun settingsStep(page: SettingsPage, licenseOpen: Boolean): Pair<SettingsPage, Boolean>? = when {
        page == SettingsPage.COLORS -> SettingsPage.LOOK to false
        page != SettingsPage.ABOUT -> null
        licenseOpen -> SettingsPage.ABOUT to false
        else -> SettingsPage.SYSTEM to false
    }

    /** Серый «Назад» - только на главном: идти некуда. */
    fun enabled(current: Area): Boolean = current != Area.Home
}

/**
 * Полноэкранный YouTube в окне приложения занимает весь экран, рейка прячется; выход - кнопкой самого YouTube.
 * Только YouTube: приложение, которое прячет строку состояния всегда (игры), осталось бы без «Назад» и «Домой».
 * Сомнение (ошибка запроса, не тот экран, не готово) - рейка видна.
 */
object FullscreenApp {
    const val YOUTUBE = "com.google.android.youtube"
    private val PACKAGES = setOf(YOUTUBE)
    /** Опрос, пока в окне YouTube. */
    const val POLL_MS = 500L
    /** Подряд ответов «полноэкранный», чтобы спрятать рейку. */
    const val HIDE_AFTER = 2
    /** После смены размера окна YouTube перестраивается и может на миг показать панели: «нет» столько не верим. */
    const val SETTLE_MS = 1500L

    fun watched(pkg: String?): Boolean = pkg in PACKAGES

    /** Решение «рейка спрятана» по ответам childFullscreen (1 да, 0 нет, -1 неизвестно). */
    class Tracker {
        var hidden = false
            private set
        private var yes = 0
        private var resizedAt = Long.MIN_VALUE / 2

        fun reset() { hidden = false; yes = 0 }
        fun resized(now: Long) { resizedAt = now }

        fun update(state: Int, now: Long): Boolean {
            if (state == 1) {
                if (++yes >= HIDE_AFTER) hidden = true
            } else {
                yes = 0
                if (now - resizedAt >= SETTLE_MS) hidden = false
            }
            return hidden
        }
    }
}

/**
 * Чип плееров на карточке музыки: два слота - свой плеер телефона (любая активная сессия) и последний онлайн-плеер
 * (Я.Музыка, YouTube Music, VK Музыка - что слушали последним, иначе первый установленный). Остальные медиаприложения -
 * долгий тап по «ноте» (список плееров). В настройках в оба слота можно закрепить любой плеер телефона (жалобы юзеров 09.11).
 */
object PlayerChip {
    const val YANDEX = "ru.yandex.music"
    const val YT_MUSIC = "com.google.android.apps.youtube.music"
    const val VK = "com.uma.musicvk"
    /** Онлайн-плееры второго слота; порядок - выбор по умолчанию, пока ничего не слушали. */
    val ONLINE = listOf(YANDEX, YT_MUSIC, VK)

    /** APP - любой другой плеер, закреплённый во втором слоте: подпись и иконка - самого приложения. */
    enum class Kind { PHONE, YANDEX, YT_MUSIC, VK, APP }
    data class Slot(val kind: Kind, val pkg: String?, val selected: Boolean)

    private fun kindOf(pkg: String): Kind = when (pkg) {
        YANDEX -> Kind.YANDEX
        YT_MUSIC -> Kind.YT_MUSIC
        VK -> Kind.VK
        else -> Kind.APP
    }

    /**
     * @param local первый слот из настроек (свой плеер); null - любая активная сессия
     * @param fixedOnline второй слот из настроек; null - «Последний»
     */
    fun slots(installed: Set<String>, chosen: String?, lastOnline: String?, local: String? = null, fixedOnline: String? = null): List<Slot> =
        buildList {
            add(Slot(Kind.PHONE, local, chosen == null || (local != null && chosen == local)))
            val online = onlineSlot(installed, lastOnline, fixedOnline, exclude = local)
            if (online != null) add(Slot(kindOf(online), online, chosen == online))
        }

    /**
     * Пакет второго слота: закреплённый в настройках (любой плеер), иначе последний услышанный онлайн, иначе первый
     * установленный. [exclude] - плеер первого слота: второй его не дублирует.
     */
    fun onlineSlot(installed: Set<String>, lastOnline: String?, fixedOnline: String?, exclude: String? = null): String? {
        fun ok(pkg: String?) = pkg != null && pkg in installed && pkg != exclude
        return fixedOnline?.takeIf(::ok) ?: lastOnline?.takeIf { ok(it) && it in ONLINE } ?: ONLINE.firstOrNull(::ok)
    }

    /** Плееры для слотов в настройках: всё найденное на телефоне плюс установленные онлайн-сервисы, без повторов. */
    fun allPlayers(found: List<String>, installed: Set<String>): List<String> =
        (found + ONLINE.filter { it in installed }).distinct()

    /** Список второго слота: онлайн-сервисы первыми (это его основное назначение), за ними остальные плееры. */
    fun secondSlotPlayers(players: List<String>): List<String> =
        ONLINE.filter { it in players } + players.filter { it !in ONLINE }

    /** Последний онлайн-плеер после того, как выбрали или услышали [pkg]: меняется, только если это онлайн-плеер. */
    fun lastOnline(previous: String?, pkg: String?): String? = pkg?.takeIf { it in ONLINE } ?: previous

    /** Последний свой плеер после того, как выбрали или услышали [pkg]: меняется, только если это не онлайн-плеер. */
    fun lastLocal(previous: String?, pkg: String?): String? = pkg?.takeIf { it !in ONLINE } ?: previous
}

/**
 * Сетка экрана настроек: 5 рядов на всю высоту, общая для колонки разделов и карточек справа - границы совпадают
 * (главное требование Егора). Карточка на span рядов занимает от top(start) до bottom(start + span - 1).
 */
object SettingsGrid {
    const val ROWS = 5
    fun top(row: Int, height: Int, gap: Int): Int = Math.round(row * (height + gap).toDouble() / ROWS).toInt()
    fun bottom(row: Int, height: Int, gap: Int): Int = top(row + 1, height, gap) - gap
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

