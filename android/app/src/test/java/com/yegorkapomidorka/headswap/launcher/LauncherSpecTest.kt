package com.yegorkapomidorka.headswap.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherSpecTest {
    @Test fun tableRowsComeBackExactly() {
        for (row in LauncherSpecs.TABLE) {
            val s = LauncherSpecs.forSize(row[0], row[1])
            assertEquals("${row[0]}x${row[1]}", listOf(row[0], row[1], row[2], row[3], row[4], row[5], row[6], row[7]),
                listOf(s.w, s.h, s.t, s.g, s.m, s.f1, s.f2, s.f3))
        }
    }

    @Test fun derivedValuesFollowMockup() {
        val s = LauncherSpecs.forSize(1920, 720)
        assertEquals(135, s.rail)
        assertEquals(103, s.navH) // 90 * 1.15 = 103.49999..., как Math.round в макете
        assertEquals(18, s.radius)
        assertEquals(40, s.ic)
        assertEquals(54, s.icBig)
        assertEquals(84, s.logo)
        assertEquals(81, s.appIc)
        assertEquals(324, s.music)
        assertEquals(540, s.phone)
        assertEquals(36, s.statusH)
    }

    @Test fun stripOnlyForShortWideScreens() {
        assertTrue(LauncherSpecs.forSize(1280, 480).strip)
        assertFalse(LauncherSpecs.forSize(800, 480).strip)
        assertFalse(LauncherSpecs.forSize(1280, 640).strip)
        assertEquals(313, LauncherSpecs.forSize(1280, 480).music)
    }

    @Test fun quickAppsFourOnSmallScreen() {
        assertEquals(4, LauncherSpecs.forSize(800, 480).quickApps)
        assertEquals(5, LauncherSpecs.forSize(1024, 600).quickApps)
    }

    @Test fun unknownSizeScalesFromNearest() {
        val s = LauncherSpecs.forSize(1920, 800)
        assertTrue(s.t in 60..120)
        assertTrue(s.rail > 0 && s.music > 0)
        // больше таблицы - крупнее цель касания
        assertTrue(LauncherSpecs.forSize(3840, 1440).t > 110)
        // меньше любой строки таблицы не падает в ноль
        assertTrue(LauncherSpecs.forSize(320, 200).t >= 1)
    }
}
