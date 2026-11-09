package com.yegorkapomidorka.headswap.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherColorsTest {
    @Test fun defaultsKeepHandTunedPalettes() {
        assertSame(Palettes.DARK, LauncherColors.palette(false, ColorSet.NIGHT))
        assertSame(Palettes.LIGHT, LauncherColors.palette(true, ColorSet.DAY))
    }

    @Test fun customSetChangesAccentAndKeepsLightness() {
        val blue = ColorSet(210, 210, 50)
        val night = LauncherColors.palette(false, blue)
        val day = LauncherColors.palette(true, blue)
        assertTrue(night.accent.blue > night.accent.red)
        assertTrue(!night.light && day.light)
        // фон ночью тёмный, днём светлый при любом оттенке
        assertTrue(night.bgStops.first().second.red < 0.2f)
        assertTrue(day.bgStops.first().second.red > 0.8f)
    }

    @Test fun encodeDecodeRoundTripAndFallback() {
        val s = ColorSet(12, 300, 0)
        assertEquals(s, ColorSet.decode(s.encode(), ColorSet.NIGHT))
        assertEquals(ColorSet.NIGHT, ColorSet.decode(null, ColorSet.NIGHT))
        assertEquals(ColorSet.DAY, ColorSet.decode("1,2", ColorSet.DAY))
        assertEquals(ColorSet(ColorSet.HUE_NEUTRAL, 0, 100), ColorSet.decode("999,-5,400", ColorSet.DAY))
    }

    @Test fun neutralHueGivesBlackNightAndWhiteDay() {
        val n = ColorSet(ColorSet.HUE_NEUTRAL, ColorSet.HUE_NEUTRAL, 80)
        val night = LauncherColors.palette(false, n)
        val day = LauncherColors.palette(true, n)
        night.bgStops.forEach { assertEquals(0f, it.second.red + it.second.green + it.second.blue, 0f) }
        day.bgStops.forEach { assertEquals(3f, it.second.red + it.second.green + it.second.blue, 0.001f) }
        assertEquals(0f, night.glowLeft, 0f)
        // акцент контрастный фону: ночью светлый серый, днём тёмный
        assertTrue(night.accent.red > 0.9f && night.accent.red == night.accent.blue)
        assertTrue(day.accent.red < 0.2f && day.accent.red == day.accent.blue)
    }
}
