package com.yegorkapomidorka.headswap.launcher

import androidx.compose.ui.graphics.Color

/**
 * Свои цвета («Вид › Цвета»): оттенок акцента, оттенок фона и насыщенность фона, отдельно для ночной и дневной палитры.
 * Значения по умолчанию дают ровно нынешние [Palettes.DARK] и [Palettes.LIGHT] (подобранные вручную), остальное считается.
 */
data class ColorSet(val accentHue: Int, val bgHue: Int, val bgSat: Int) {
    fun encode(): String = "$accentHue,$bgHue,$bgSat"

    companion object {
        /** Тёмное золото: акцент 46°, сине-серый фон 207°. */
        val NIGHT = ColorSet(46, 207, 80)
        /** Светлое золото: акцент 46°, кремовый фон 44°. */
        val DAY = ColorSet(46, 44, 100)

        fun decode(s: String?, fallback: ColorSet): ColorSet {
            val p = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return fallback
            if (p.size != 3) return fallback
            return ColorSet(p[0].coerceIn(0, 360), p[1].coerceIn(0, 360), p[2].coerceIn(0, 100))
        }
    }
}

object LauncherColors {
    private fun hsl(h: Int, s: Float, l: Float, a: Float = 1f): Color =
        Color.hsl((h % 360).toFloat().coerceIn(0f, 359.9f), (s / 100f).coerceIn(0f, 1f), (l / 100f).coerceIn(0f, 1f), a)

    /** Акцент: насыщенность 65%, светлота 52% ночью (золото #D4AF37) и 45% днём - на светлом фоне темнее. */
    fun accent(light: Boolean, hue: Int): Color = hsl(hue, 65f, if (light) 45f else 52f)

    /** Палитра для набора; набор по умолчанию - ручная палитра без пересчёта. */
    fun palette(light: Boolean, set: ColorSet): LauncherPalette = when {
        !light && set == ColorSet.NIGHT -> Palettes.DARK
        light && set == ColorSet.DAY -> Palettes.LIGHT
        light -> day(set)
        else -> night(set)
    }

    private fun night(c: ColorSet): LauncherPalette {
        val b = c.bgHue
        val k = c.bgSat / 100f
        fun bg(s: Float, l: Float, a: Float = 1f) = hsl(b, s * k, l, a)
        return LauncherPalette(
            light = false,
            bgStops = listOf(0f to bg(40f, 6f), 0.42f to bg(34f, 10f), 0.70f to bg(26f, 14f), 1f to bg(30f, 10f)),
            glowLeft = 0.24f, glowRight = 0.13f,
            fg = bg(20f, 96f), fg2 = bg(45f, 86f), fg3 = bg(34f, 76f),
            surf = bg(60f, 85f, 0.07f), btn2 = bg(60f, 85f, 0.12f), pressed = bg(60f, 85f, 0.20f),
            btnSet = bg(60f, 85f, 0.10f), railFg = bg(20f, 96f),
            missed = Color(0xFFFFB4A9), backOff = bg(15f, 44f), tagBg = bg(30f, 7f, 0.6f), mapBg = bg(25f, 12f),
            bgSolid = bg(25f, 14f), mini = bg(30f, 7f),
            bar = bg(22f, 13f), sep = Color(1f, 1f, 1f, 0.08f),
            accent = accent(false, c.accentHue),
        )
    }

    /** Днём поверхности и кнопки тонированы акцентом: на светлом фоне иначе акцент виден только на кнопке навигации. */
    private fun day(c: ColorSet): LauncherPalette {
        val b = c.bgHue
        val k = c.bgSat / 100f
        fun bg(s: Float, l: Float, a: Float = 1f) = hsl(b, s * k, l, a)
        fun tint(a: Float) = hsl(c.accentHue, 55f, 30f, a)
        return LauncherPalette(
            light = true,
            bgStops = listOf(0f to bg(60f, 95f), 0.5f to bg(52f, 91f), 1f to bg(45f, 86f)),
            glowLeft = 0.45f, glowRight = 0.25f,
            fg = bg(22f, 23f), fg2 = bg(17f, 30f), fg3 = bg(14f, 38f),
            surf = tint(0.10f), btn2 = tint(0.15f), pressed = tint(0.22f),
            btnSet = tint(0.15f), railFg = bg(17f, 30f),
            missed = Color(0xFFB3361F), backOff = bg(12f, 60f), tagBg = Color(1f, 1f, 1f, 0.88f), mapBg = bg(25f, 92f),
            bgSolid = bg(52f, 91f), mini = bg(60f, 95f),
            bar = bg(40f, 86f), sep = tint(0.14f),
            accent = accent(true, c.accentHue),
        )
    }
}
