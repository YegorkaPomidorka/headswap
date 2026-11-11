package com.yegorkapomidorka.headswap.launcher

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Цвета карточки музыки без обложки. Нарисованы под золото [Pal.GOLD] (#D4AF37); у другого акцента из «Вид › Цвета» оттенок
 * сдвигается на разницу оттенков акцента и золота, насыщенность - во столько же раз, во сколько акцент насыщеннее золота
 * (нейтральный акцент даёт серую карточку), светлота остаётся. У золота цвета ровно прежние.
 */
class MusicColors private constructor(
    val base: Color, val gradFrom: Color, val gradTo: Color, val spotA: Color, val spotB: Color, val noteInk: Color,
) {
    companion object {
        private val GOLD = Color(0xFFD4AF37)

        fun of(accent: Color) = MusicColors(
            base = tint(Color(0xFF211C10), accent), gradFrom = tint(Color(0xFF6B5420), accent),
            gradTo = tint(Color(0xFF3A2440), accent), spotA = tint(Color(0xFFE8C65A), accent),
            spotB = tint(Color(0xFFC45434), accent), noteInk = tint(Color(0xFF201A05), accent),
        )

        /** [base] (нарисован под золото) в оттенке и насыщенности акцента. */
        fun tint(base: Color, accent: Color): Color {
            val a = hsl(accent)
            val g = hsl(GOLD)
            val b = hsl(base)
            val ratio = if (g[1] <= 0f) 1f else (a[1] / g[1]).coerceIn(0f, 1.2f)
            val hue = (((b[0] + a[0] - g[0]) % 360f) + 360f) % 360f
            return Color.hsl(hue, (b[1] * ratio).coerceIn(0f, 1f), b[2], base.alpha)
        }

        /** [оттенок 0..360, насыщенность 0..1, светлота 0..1] */
        private fun hsl(c: Color): FloatArray {
            val mx = max(c.red, max(c.green, c.blue))
            val mn = min(c.red, min(c.green, c.blue))
            val l = (mx + mn) / 2f
            val d = mx - mn
            if (d <= 0f) return floatArrayOf(0f, 0f, l)
            val s = d / (1f - abs(2f * l - 1f))
            val h = when (mx) {
                c.red -> ((c.green - c.blue) / d) % 6f
                c.green -> (c.blue - c.red) / d + 2f
                else -> (c.red - c.green) / d + 4f
            } * 60f
            return floatArrayOf((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
        }
    }
}
