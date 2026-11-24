package com.yegorkapomidorka.headswap.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GarlandLogicTest {
    @Test fun threeBulbsPerArcAndStepIs118T() {
        val s = GarlandLogic.build(0f, 1180f, 50f, 100f, null)
        // дуги по 118 px: [0,118) .. и ещё одна за правым краем
        assertEquals(11, s.hooks.count { it.x < 1180f })
        assertEquals(0f, s.hooks[0].x, 0f)
        assertEquals(118f, s.hooks[1].x, 0.001f)
        assertEquals(s.hooks.size * GarlandLogic.BULBS_PER_ARC, s.bulbs.size)
    }

    @Test fun sagIs023TAtMiddleBulb() {
        val s = GarlandLogic.build(0f, 118f, 0f, 100f, null)
        val mid = s.bulbs.first { kotlin.math.abs(it.x - 59f) < 0.1f }
        assertEquals(23f, mid.y, 0.001f)
    }

    @Test fun gapRemovesBulbsHooksAndWire() {
        val s = GarlandLogic.build(0f, 1000f, 0f, 100f, 400f..600f)
        assertTrue(s.bulbs.none { it.x in 400f..600f })
        assertTrue(s.hooks.none { it.x in 400f..600f })
        assertTrue(s.wire.all { run -> run.none { it.x in 400f..600f } })
        assertTrue(s.wire.size >= 2)
    }

    @Test fun opacityBlinkRange() {
        assertEquals(1f, GarlandLogic.opacity(0f, 0f), 0.001f)
        assertEquals(GarlandLogic.MIN_OPACITY, GarlandLogic.opacity(0.5f, 0f), 0.001f)
        assertEquals(1f, GarlandLogic.opacity(1f, 0f), 0.001f)
        assertEquals(GarlandLogic.MIN_OPACITY, GarlandLogic.opacity(0.2f, 0.3f), 0.001f)
    }

    @Test fun colorsFollowAccentHue() {
        assertEquals(4, GarlandLogic.colors(46).size)
        assertEquals(2, GarlandLogic.colors(ColorSet.HUE_NEUTRAL).size)
        // отрицательный оттенок (0 - 25) заворачивается без падения
        assertEquals(4, GarlandLogic.colors(0).size)
    }

    @Test fun presetsMatchDefaults() {
        assertEquals(ColorSet.NIGHT, ColorPreset.DEFAULT.night)
        assertEquals(ColorSet.DAY, ColorPreset.DEFAULT.day)
        assertTrue(!ColorPreset.DEFAULT.garland && ColorPreset.TREE.garland && ColorPreset.NEW_YEAR.garland)
        assertEquals(ColorSet(46, 152, 70), ColorPreset.TREE.night)
        assertEquals(ColorSet(0, 140, 60), ColorPreset.NEW_YEAR.night)
    }
}
