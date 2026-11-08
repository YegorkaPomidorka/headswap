package com.yegorkapomidorka.headswap.launcher

/**
 * Что показывает плашка главного. Имена - значения prefs: «card_view» (большая) и «small_view» (малая).
 * [APP] - приложение во встроенном окне (какое - [HomeTiles.app]); до настройки «Слоёв» это всегда был YouTube,
 * старое значение «YOUTUBE» читается как [APP].
 */
enum class CardView { NAVIGATOR, APP, MUSIC, CLOCK;
    companion object {
        fun restore(name: String?, default: CardView = NAVIGATOR): CardView =
            if (name == "YOUTUBE") APP else entries.firstOrNull { it.name == name } ?: default
    }
}

/** Раскладка главного: четыре плашки или две на всю высоту - 2:1 (большая слева) и 1:2 (большая справа). */
enum class HomeLayout { QUAD, WIDE_LEFT, WIDE_RIGHT;
    val two: Boolean get() = this != QUAD

    companion object {
        fun restore(name: String?): HomeLayout = entries.firstOrNull { it.name == name } ?: QUAD
    }
}

/** Слой - что может встать в большую плашку: встроенный вид (навигатор, музыка, часы) или любое приложение. */
sealed interface Layer {
    data class View(val view: CardView) : Layer
    /** Приложение по пакету: ключ запуска берётся из каталога, поэтому смена activity при обновлении не ломает слой. */
    data class App(val pkg: String) : Layer
}

/**
 * Что листают «Слои» (настройки «Экран», блок под «Раскладкой»): до [MAX] слоёв в порядке пользователя, минимум один.
 * «+» открывает выбор: сначала убранные встроенные виды, потом установленные приложения (ADR 0030).
 */
object LayerList {
    const val MAX = 4
    /** Встроенные виды, которые бывают слоями (APP - это любое приложение, отдельным видом не бывает). */
    val BUILTIN = listOf(CardView.NAVIGATOR, CardView.MUSIC, CardView.CLOCK)
    /** Как было до настройки: навигатор, YouTube, музыка, часы. */
    val DEFAULT: List<Layer> =
        listOf(Layer.View(CardView.NAVIGATOR), Layer.App(FullscreenApp.YOUTUBE), Layer.View(CardView.MUSIC), Layer.View(CardView.CLOCK))
    private const val APP = "app:"

    fun decode(s: String?): List<Layer> {
        if (s == null) return DEFAULT
        val list = s.split('\n').mapNotNull { item ->
            when {
                item.startsWith(APP) -> item.removePrefix(APP).takeIf { it.isNotBlank() }?.let { Layer.App(it) }
                item == "YOUTUBE" -> Layer.App(FullscreenApp.YOUTUBE)
                else -> BUILTIN.firstOrNull { it.name == item }?.let { Layer.View(it) }
            }
        }.distinct().take(MAX)
        return list.ifEmpty { DEFAULT }
    }

    fun encode(list: List<Layer>): String = list.joinToString("\n") {
        when (it) {
            is Layer.View -> it.view.name
            is Layer.App -> APP + it.pkg
        }
    }

    /** Встроенные виды, которых нет в списке: в выборе по «+» идут первыми. */
    fun freeBuiltins(list: List<Layer>): List<CardView> =
        if (list.size >= MAX) emptyList() else BUILTIN.filter { Layer.View(it) !in list }

    /** Приложения для выбора по «+»: без уже выбранных и без навигатора (его пакет живёт в своём окне). */
    fun pickableApps(installed: List<String>, list: List<Layer>, navigatorPkg: String?): List<String> =
        if (list.size >= MAX) emptyList()
        else installed.distinct().filter { pkg -> pkg != navigatorPkg && Layer.App(pkg) !in list }

    fun add(list: List<Layer>, l: Layer): List<Layer> = if (l in list || list.size >= MAX) list else list + l

    /** Последний не убирается: листать было бы нечего. */
    fun remove(list: List<Layer>, l: Layer): List<Layer> = if (list.size <= 1) list else list - l
}

/** Содержимое большой и малой плашки; [app] - пакет приложения плашки [CardView.APP]. */
data class HomeTiles(val big: CardView, val small: CardView, val app: String = FullscreenApp.YOUTUBE)

/**
 * Правила плашек главного. «Слои» листают большую плашку и пропускают то, что в малой; ⇄ меняет плашки местами.
 * В четырёх плашках малая - всегда музыка. Окно приложения одно (слот EmbedHost.app), поэтому плашка-приложение на
 * экране одна. Приложение не ставится, если его нет на телефоне ([appOk] / [installed] false).
 */
object HomeTilesLogic {
    fun normalize(t: HomeTiles, layout: HomeLayout, appOk: Boolean): HomeTiles {
        val big = t.big.takeIf { appOk || it != CardView.APP } ?: CardView.NAVIGATOR
        if (!layout.two) return HomeTiles(if (big == CardView.MUSIC) CardView.NAVIGATOR else big, CardView.MUSIC, t.app)
        var small = t.small.takeIf { appOk || it != CardView.APP } ?: CardView.MUSIC
        if (small == big) small = if (big == CardView.MUSIC) CardView.NAVIGATOR else CardView.MUSIC
        return HomeTiles(big, small, t.app)
    }

    /**
     * «Слои»: следующий из [layers] (порядок пользователя). Пропускает то, что в малой (приложение - если в малой
     * плашка-приложение: окно одно), неустановленные приложения и музыку в четырёх плашках. Большой нет в списке -
     * встаёт первый подходящий.
     */
    fun nextBig(t: HomeTiles, layout: HomeLayout, installed: (String) -> Boolean, layers: List<Layer> = LayerList.DEFAULT): HomeTiles {
        val n = normalize(t, layout, installed(t.app))
        val current: Layer = if (n.big == CardView.APP) Layer.App(n.app) else Layer.View(n.big)
        val order = layers.filter { l ->
            l == current || when (l) {
                is Layer.View -> l.view != n.small && (layout.two || l.view != CardView.MUSIC)
                is Layer.App -> n.small != CardView.APP && installed(l.pkg)
            }
        }
        if (order.isEmpty()) return n
        val i = order.indexOf(current)
        return when (val next = if (i < 0) order[0] else order[(i + 1) % order.size]) {
            is Layer.View -> n.copy(big = next.view)
            is Layer.App -> n.copy(big = CardView.APP, app = next.pkg)
        }
    }

    fun swap(t: HomeTiles, layout: HomeLayout, appOk: Boolean): HomeTiles {
        val n = normalize(t, layout, appOk)
        return if (layout.two) n.copy(big = n.small, small = n.big) else n
    }
}
