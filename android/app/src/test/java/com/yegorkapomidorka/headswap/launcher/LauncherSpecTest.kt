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
        assertEquals(132, s.bottomH) // T + 2G
        assertEquals(132, s.navH) // блок навигации - одна полоса с нижним рядом
        assertEquals(18, s.radius)
        assertEquals(40, s.ic)
        assertEquals(54, s.icBig)
        assertEquals(81, s.appIc)
        assertEquals(571, s.music) // (1920 - 135 - 21 - 29 - 21) / 3
        assertEquals(571, s.phone)
        assertEquals(36, s.statusH)
        assertEquals(96, LauncherSpecs.forSize(1280, 720).t)
    }

    @Test fun railFollowsMockup() {
        val s = LauncherSpecs.forSize(1920, 720)
        assertEquals(90, s.rb) // все кнопки рейки по T, пока влезают
        assertEquals(40, s.ric)
        assertEquals(58, s.logoIn)
        assertEquals(54, s.backIc) // белый шеврон 54 px на 1920x720
        // 1280x480: три кнопки, рейка сжата по высоте
        val strip = LauncherSpecs.forSize(1280, 480)
        assertEquals(106, strip.navH)
        assertEquals(88, strip.rb)
        assertEquals(40, strip.ric)
        for (row in LauncherSpecs.TABLE) {
            val r = LauncherSpecs.forSize(row[0], row[1])
            assertTrue("${row[0]}x${row[1]}", r.rb in 1..r.t && r.ric <= r.ic && r.backIc <= r.rb)
        }
    }

    @Test fun foreignBarFollowsRailButton() {
        assertEquals(90, LauncherSpecs.forSize(1920, 720).barH)
        assertEquals(88, LauncherSpecs.forSize(1280, 480).barH)
        assertEquals(62, LauncherSpecs.forSize(800, 480).barH)
        for (row in LauncherSpecs.TABLE) {
            val s = LauncherSpecs.forSize(row[0], row[1])
            // панель не выше пятой части экрана и не ниже цели касания рейки, иконка влезает
            assertTrue("${row[0]}x${row[1]}", s.barH in 60..110 && s.barH == s.rb && s.barIc < s.barH && s.barH * 10 <= s.h * 2)
        }
    }

    @Test fun musicChipAndTransportFollowMockup() {
        val s = LauncherSpecs.forSize(1920, 720)
        assertEquals(65, s.pcB)
        assertEquals(33, s.pcIc)
        assertEquals(26, s.pcVk)
        assertEquals(70, s.tp)
        assertEquals(34, s.tpIc)
        assertEquals(109, s.titleB)
        assertEquals(17, s.tag)
    }

    @Test fun gridIsOneToTwo() {
        val vezel = LauncherSpecs.forSize(1600, 900) // масштаб строки 1920x1080
        assertEquals(83, vezel.t)
        assertEquals(125, vezel.rail)
        assertEquals(470, vezel.music)
        assertEquals(470, vezel.phone)
        assertEquals(83, vezel.rb)
        assertEquals(220, LauncherSpecs.forSize(800, 480).music)
        assertEquals(356, LauncherSpecs.forSize(1280, 720).phone)
    }

    @Test fun compactPhoneByFormula() {
        // на текст меньше 15 * F2 * 0.55
        val compact = setOf("1280x720", "1280x640", "1024x600", "800x480")
        for (row in LauncherSpecs.TABLE) {
            val s = LauncherSpecs.forSize(row[0], row[1])
            if (s.strip) continue
            assertEquals("${row[0]}x${row[1]}", "${row[0]}x${row[1]}" in compact, s.phoneCompact)
        }
    }

    @Test fun stripOnlyForShortWideScreens() {
        assertTrue(LauncherSpecs.forSize(1280, 480).strip)
        assertFalse(LauncherSpecs.forSize(800, 480).strip)
        assertFalse(LauncherSpecs.forSize(1280, 640).strip)
        assertEquals(313, LauncherSpecs.forSize(1280, 480).music)
        assertEquals(92 * 5 - 21, LauncherSpecs.forSize(1280, 480).phone) // только для оверлея входящего
    }

    @Test fun quickAppsFourPlusAddOrFiveInStrip() {
        assertEquals(4, LauncherSpecs.forSize(800, 480).quickApps)
        assertEquals(4, LauncherSpecs.forSize(1920, 720).quickApps)
        assertEquals(5, LauncherSpecs.forSize(1280, 480).quickApps)
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
