package com.yegorkapomidorka.headswap.launcher

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/** Готовый набор цветов «Вид › Цвета › Пресеты» (ADR 0063); [garland] - включать гирлянду при выборе. */
data class ColorPreset(val id: String, val night: ColorSet, val day: ColorSet, val garland: Boolean) {
    fun set(day: Boolean) = if (day) this.day else night

    companion object {
        val DEFAULT = ColorPreset("default", ColorSet.NIGHT, ColorSet.DAY, false)
        /** Ёлка: тёмно-зелёный фон, золотой акцент. Днём - светлый зеленоватый. */
        val TREE = ColorPreset("tree", ColorSet(46, 152, 70), ColorSet(46, 152, 60), true)
        /** Новогодний: зелёный фон, красный акцент. */
        val NEW_YEAR = ColorPreset("new_year", ColorSet(0, 140, 60), ColorSet(0, 140, 60), true)
        val ALL = listOf(DEFAULT, TREE, NEW_YEAR)
    }
}

/** Геометрия и цвета гирлянды по нижнему краю строки статуса; всё чистое, рисует [Garland]. */
object GarlandLogic {
    /** Шаг крючков и провис дуги в долях T. */
    const val STEP = 1.18f
    const val SAG = 0.23f
    const val BULBS_PER_ARC = 3
    private const val SAMPLES = 10
    /** Мерцание: 1 → [MIN_OPACITY] → 1 за [PERIOD_MS]. */
    const val PERIOD_MS = 2800
    const val MIN_OPACITY = 0.35f

    data class Pt(val x: Float, val y: Float)
    data class Bulb(val x: Float, val y: Float, val colorIdx: Int, val delay: Float)
    data class Shape(val hooks: List<Pt>, val wire: List<List<Pt>>, val bulbs: List<Bulb>)

    /**
     * Дуги от [left] до [right] на высоте [y], шаг крючков [STEP]·[t]. В [gap] (X полоски уведомлений с запасом) ни
     * провода, ни лампочек, ни крючков: полоску не перекрываем.
     */
    fun build(left: Float, right: Float, y: Float, t: Float, gap: ClosedFloatingPointRange<Float>?): Shape {
        val step = STEP * t
        val sag = SAG * t
        fun inGap(x: Float) = gap != null && x >= gap.start && x <= gap.endInclusive
        val hooks = ArrayList<Pt>()
        val wire = ArrayList<List<Pt>>()
        val bulbs = ArrayList<Bulb>()
        var run = ArrayList<Pt>()
        fun flush() { if (run.size > 1) wire += run; run = ArrayList() }
        var i = 0
        var idx = 0
        while (left + i * step < right + step) {
            val x0 = left + i * step
            if (!inGap(x0)) hooks += Pt(x0, y)
            for (k in 0..SAMPLES) {
                val u = k / SAMPLES.toFloat()
                val p = Pt(x0 + u * step, y + 4f * sag * u * (1f - u))
                if (inGap(p.x)) flush() else run += p
            }
            for (b in 1..BULBS_PER_ARC) {
                val u = b / (BULBS_PER_ARC + 1f)
                val bx = x0 + u * step
                if (!inGap(bx)) bulbs += Bulb(bx, y + 4f * sag * u * (1f - u), idx, (idx * 0.37f) % 1f)
                idx++
            }
            i++
        }
        flush()
        return Shape(hooks, wire, bulbs)
    }

    /** Непрозрачность лампочки в фазе [phase] 0..1 со сдвигом [delay]: 1 → 0,35 → 1. */
    fun opacity(phase: Float, delay: Float): Float {
        val u = ((phase + delay) % 1f + 1f) % 1f
        return MIN_OPACITY + (1f - MIN_OPACITY) * abs(2f * u - 1f)
    }

    private fun hsl(h: Int, s: Float, l: Float) = Color.hsl((((h % 360) + 360) % 360).toFloat(), s, l)

    private val WARM_WHITE = Color(0xFFFFF3D6)

    /** Цвета лампочек по кругу от оттенка акцента; «без цвета» ([ColorSet.neutral]) - тёплый белый и бледное золото. */
    fun colors(accentHue: Int): List<Color> =
        if (ColorSet.neutral(accentHue)) listOf(WARM_WHITE, hsl(46, 0.70f, 0.80f))
        else listOf(hsl(accentHue, 0.95f, 0.60f), WARM_WHITE, hsl(accentHue + 25, 0.92f, 0.62f), hsl(accentHue - 25, 0.92f, 0.60f))
}
