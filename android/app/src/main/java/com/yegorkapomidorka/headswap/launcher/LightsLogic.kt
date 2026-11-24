package com.yegorkapomidorka.headswap.launcher

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Слой «Огоньки» (ADR 0063): боке цветами гирлянды вместо «Без обоев», пока гирлянда включена. Раскладка чистая, фиксированный сид. */
object LightsLogic {
    const val COUNT = 16
    /** Полный цикл анимации: периоды 6, 8 и 10 с в нём укладываются целое число раз. */
    const val CYCLE_MS = 120_000
    private val PERIODS_MS = intArrayOf(6000, 8000, 10000)

    /** [x], [y] - доли экрана; [r] - радиус в T; [alpha] 0.10..0.35; [colorIdx] - индекс в [GarlandLogic.colors]. */
    data class Bokeh(val x: Float, val y: Float, val r: Float, val alpha: Float, val colorIdx: Int, val phase: Float, val periodMs: Int)

    fun layout(count: Int = COUNT): List<Bokeh> {
        var seed = 777L
        fun next(): Float { seed = (seed * 1103515245L + 12345L) and 0x7fffffffL; return (seed % 10000L) / 10000f }
        return List(count) { i ->
            // y в степени: точки чуть плотнее сверху
            Bokeh(next(), next().pow(1.4f), 0.3f + 0.9f * next(), 0.10f + 0.25f * next(), i, next(), PERIODS_MS[i % 3])
        }
    }

    /** Множитель альфы 0.65..1 в момент [cycle] (0..1 от [CYCLE_MS]). */
    fun breathe(b: Bokeh, cycle: Float): Float {
        val cycles = CYCLE_MS.toFloat() / b.periodMs
        return 0.825f + 0.175f * sin(2.0 * PI * (b.phase + cycle * cycles)).toFloat()
    }
}
