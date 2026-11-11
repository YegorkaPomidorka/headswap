package com.yegorkapomidorka.headswap.launcher

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicColorsTest {
    private fun ch(v: Float) = (v * 255).roundToInt()

    private fun assertColor(name: String, expected: Long, actual: Color, tol: Int = 2) {
        val e = Color(expected)
        for ((n, a, b) in listOf(Triple("r", e.red, actual.red), Triple("g", e.green, actual.green), Triple("b", e.blue, actual.blue))) {
            assertTrue("$name $n: ждали ${ch(a)}, вышло ${ch(b)}", abs(ch(a) - ch(b)) <= tol)
        }
    }

    private fun assertOriginal(c: MusicColors) {
        assertColor("base", 0xFF211C10, c.base)
        assertColor("gradFrom", 0xFF6B5420, c.gradFrom)
        assertColor("gradTo", 0xFF3A2440, c.gradTo)
        assertColor("spotA", 0xFFE8C65A, c.spotA)
        assertColor("spotB", 0xFFC45434, c.spotB)
        assertColor("noteInk", 0xFF201A05, c.noteInk)
    }

    @Test fun goldAccentKeepsOriginalColors() {
        assertOriginal(MusicColors.of(Pal.GOLD))
        // акцент по умолчанию из палитры («Вид › Цвета», hsl 46/65/52) отличается от #D4AF37 на единицы
        assertOriginal(MusicColors.of(LauncherColors.accent(false, ColorSet.NIGHT.accentHue)))
        assertOriginal(MusicColors.of(Palettes.DARK.accent))
    }

    @Test fun otherAccentRecolorsEverything() {
        val blue = MusicColors.of(LauncherColors.accent(false, 210))
        val gold = MusicColors.of(Pal.GOLD)
        // золотые тона уходят в синие: у фона и градиента синий канал выше красного
        assertTrue(blue.gradFrom.blue > blue.gradFrom.red)
        assertTrue(blue.base.blue > blue.base.red)
        assertTrue(blue.spotA.blue > blue.spotA.red)
        assertTrue(ch(blue.gradFrom.blue) != ch(gold.gradFrom.blue))
        // светлота прежняя: сумма каналов не уезжает далеко (нет ни выцветания, ни пережога)
        assertEquals(luma(gold.gradFrom), luma(blue.gradFrom), 0.25f)
        // второй цвет градиента сдвигается вместе с акцентом (+241 градус от золота)
        assertTrue(blue.gradTo != gold.gradTo)
    }

    @Test fun neutralAccentGivesGreyCard() {
        val grey = MusicColors.of(LauncherColors.accent(false, ColorSet.HUE_NEUTRAL))
        for (c in listOf(grey.base, grey.gradFrom, grey.gradTo, grey.spotA, grey.spotB)) {
            assertTrue(abs(ch(c.red) - ch(c.green)) <= 1 && abs(ch(c.green) - ch(c.blue)) <= 1)
        }
    }

    private fun luma(c: Color) = c.red + c.green + c.blue
}
