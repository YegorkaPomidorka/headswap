package com.yegorkapomidorka.headswap.launcher

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Размеры лаунчера Headswap Desktop в пикселях экрана ГУ (не в dp: density виртуального дисплея своя).
 * База и формулы - из макета Teyes.dc.html: [W, H, T цель касания, G зазор, M поле, F1 текст, F2 вторичный, F3 цифры].
 */
class LauncherSpec(
    val w: Int, val h: Int, val t: Int, val g: Int, val m: Int, val f1: Int, val f2: Int, val f3: Int,
) {
    val wide = w >= 1500
    /** Узкая лента вроде 1280x480: нижнего ряда нет, приложения сеткой справа. */
    val strip = w >= 1200 && h <= 500
    private val k = t / 120.0

    val fs = f2
    val g4 = r(g * 0.3)
    val g8 = r(g * 0.6)
    val b4 = max(3, r(4 * k))
    val b6 = max(4, r(8 * k))
    val rail = r(t * 1.5)
    val icBig = r(t * 0.6)
    val radius = r(t * 0.2)
    val ic = r(t * 0.44)
    val statusH = r(f2 * 1.3)
    /** Нижний ряд одной полосой: кнопка навигации, приложения и телефон высотой T плюс воздух по G сверху и снизу. */
    val bottomH = t + 2 * g
    /** Блок навигации внизу рейки: в нижнем ряду той же высоты, что ряд; в ленте нижнего ряда нет. */
    val navH = if (strip) r(t * 1.15) else bottomH
    /** Сетка 1:2 зеркально: узкая колонка - музыка сверху и телефон снизу, широкая - карта и приложения. */
    val narrow = r((w - rail - g - m - g) / 3.0)
    val music = if (strip) r(t * 3.4) else narrow
    /** В ленте карточки нет, ширина нужна только оверлею входящего: 6T на широких, 5T - G на остальных. */
    val phone = if (!strip) narrow else if (wide) r(t * 6.0) else r(t * 5.0 - g)
    val cover = r(t * 1.6)
    val tag = max(14, r(f2 * 0.6))
    val appIc = r(t * 0.9)
    val appSvg = r(t * 0.5)
    val appLbl = r(f2 * 0.85)

    // Рейка: кнопки по T, но не больше, чем влезает по высоте между часами и блоком навигации.
    // Назад, Домой, Настройки, Вид карточки; в ленте «Вид карточки» на карте.
    private val railButtons = if (strip) 3 else 4
    private val railFree = h - navH - g - (g + g8) - f1
    val rb = max(1, min(t, floor((railFree - railButtons * g4) / railButtons.toDouble()).toInt()))
    val ric = min(ic, r(rb * 0.5))
    /** Панель «Назад / Домой / Недавние» под окном чужого лаунчера: высота как у круглой кнопки рейки (60-110 px по таблице). */
    val barH = rb
    val barIc = r(barH * 0.42)
    val logoIn = r(rb * 0.64)
    val backIc = min(r(ric * 1.35), r(rb * 0.66))
    // Стеклянный чип плееров на обложке (и кнопка «Вид карточки» на карте в ленте)
    val pcB = r(t * 0.72)
    val pcIc = r(pcB * 0.5)
    val pcVk = r(pcIc * 0.8)
    // Транспорт: пауза T, соседние меньше, чтобы между ними был зазор
    val tp = r(t * 0.78)
    val tpIc = r(ic * 0.85)
    val titleB = t + g8 + g4
    /** Сколько приложений в быстром ряду (без плюса): в ленте сетка 2x3. */
    val quickApps = if (strip) 5 else 4
    /** Узкая карточка «Телефон»: имя цветом состояния, ниже время и счётчик, одна кнопка. */
    val phoneCompact = phone - 2 * g - (if (wide) 2 * t + 2 * g else t + g) < 15 * f2 * 0.55

    private fun r(v: Double) = v.roundToInt()

    override fun equals(other: Any?) = other is LauncherSpec && w == other.w && h == other.h && t == other.t &&
        g == other.g && m == other.m && f1 == other.f1 && f2 == other.f2 && f3 == other.f3
    override fun hashCode() = listOf(w, h, t, g, m, f1, f2, f3).hashCode()
    override fun toString() = "LauncherSpec(${w}x$h T=$t)"
}

object LauncherSpecs {
    /** [W, H, T, G, M, F1, F2, F3], как RES в макете. */
    val TABLE: List<IntArray> = listOf(
        intArrayOf(1920, 720, 90, 21, 29, 34, 28, 42),
        intArrayOf(2240, 976, 110, 25, 35, 42, 35, 52),
        intArrayOf(1920, 1080, 100, 23, 32, 38, 31, 47),
        intArrayOf(1920, 640, 86, 20, 28, 32, 27, 40),
        intArrayOf(1728, 648, 86, 20, 28, 32, 27, 40),
        intArrayOf(1280, 720, 96, 20, 28, 36, 30, 44),
        intArrayOf(1280, 640, 85, 20, 28, 32, 27, 40),
        intArrayOf(1280, 480, 92, 21, 31, 35, 29, 43),
        intArrayOf(1024, 600, 74, 17, 22, 27, 22, 34),
        intArrayOf(800, 480, 62, 14, 18, 23, 19, 29),
    )

    /** Точная строка таблицы либо ближайшая по высоте и пропорции, масштабированная линейно. */
    fun forSize(width: Int, height: Int): LauncherSpec {
        val w = max(1, width)
        val h = max(1, height)
        TABLE.firstOrNull { it[0] == w && it[1] == h }?.let { return of(it, 1.0) }
        val aspect = w.toDouble() / h
        val near = TABLE.minBy { row ->
            abs(ln(h.toDouble() / row[1])) + abs(ln(aspect / (row[0].toDouble() / row[1])))
        }
        // линейно по меньшему из отношений, чтобы раскладка гарантированно влезала
        val k = min(w.toDouble() / near[0], h.toDouble() / near[1])
        return of(near, k, w, h)
    }

    private fun of(row: IntArray, k: Double, w: Int = row[0], h: Int = row[1]): LauncherSpec {
        fun s(i: Int) = if (k == 1.0) row[i] else max(1, (row[i] * k).roundToInt())
        return LauncherSpec(w, h, s(2), s(3), s(4), s(5), s(6), s(7))
    }
}
