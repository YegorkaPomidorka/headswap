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
        assertEquals(81, s.appIc)
        assertEquals(324, s.music)
        assertEquals(540, s.phone)
        assertEquals(36, s.statusH)
    }

    @Test fun railFollowsV2Mockup() {
        val s = LauncherSpecs.forSize(1920, 720)
        assertEquals(90, s.rb) // все кнопки рейки по T, пока влезают
        assertEquals(40, s.ric)
        assertEquals(58, s.logoIn)
        assertEquals(54, s.backIc) // белый шеврон 54 px на 1920x720
        // 1280x480: рейка сжата по высоте, кнопки меньше T
        val strip = LauncherSpecs.forSize(1280, 480)
        assertTrue(strip.rb < strip.t)
        assertEquals(54, strip.rb)
        assertEquals(27, strip.ric)
        for (row in LauncherSpecs.TABLE) {
            val r = LauncherSpecs.forSize(row[0], row[1])
            assertTrue("${row[0]}x${row[1]}", r.rb in 1..r.t && r.ric <= r.ic && r.backIc <= r.rb)
        }
    }

    @Test fun musicChipAndTitleFollowV2Mockup() {
        val s = LauncherSpecs.forSize(1920, 720)
        assertEquals(54, s.pcH)
        assertEquals(48, s.pcB)
        assertEquals(27, s.pcIc)
        assertEquals(109, s.titleB)
        assertEquals(17, s.tag)
    }

    @Test fun tallWideScreensGetTallMusicCard() {
        // 1920x1080 и реальный экран ГУ 1600x900 (масштаб строки 1920x1080)
        val full = LauncherSpecs.forSize(1920, 1080)
        assertEquals(440, full.music)
        val vezel = LauncherSpecs.forSize(1600, 900)
        assertEquals(83, vezel.t)
        assertEquals(365, vezel.music)
        assertEquals(498, vezel.phone)
        assertEquals(83, vezel.rb)
        // 1920x720 и 2240x976 остаются 3.6T
        assertEquals(324, LauncherSpecs.forSize(1920, 720).music)
        assertEquals(396, LauncherSpecs.forSize(2240, 976).music)
    }

    @Test fun phoneCardWidthByScreen() {
        assertEquals(540, LauncherSpecs.forSize(1920, 720).phone) // 6T
        val narrow = LauncherSpecs.forSize(1280, 720)
        assertEquals(88 * 5 - 20, narrow.phone) // 5T - G
        assertEquals(62 * 5 - 14, LauncherSpecs.forSize(800, 480).phone)
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
