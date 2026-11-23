package com.yegorkapomidorka.headswap.launcher

import com.yegorkapomidorka.headswap.dex.EmbeddedDisplayPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedRenderPolicyTest {
    private fun g(pkg: String?, w: Int, h: Int, dpi: Int) = EmbedRenderPolicy.geometry(pkg, w, h, dpi)

    @Test fun otherAppsKeepSizeAndGetWideDensity() {
        // h1264: плитка 761×501 одна и та же на столе 720p (200 DPI) и 1080p (300 DPI); при них Яндекс Про шёл столбиком
        assertEquals(EmbedRenderPolicy.Geometry(761, 501, 131), g("ru.yandex.taximeter", 761, 501, 200))
        assertEquals(EmbedRenderPolicy.Geometry(761, 501, 131), g("ru.yandex.taximeter", 761, 501, 300))
        assertEquals(EmbedRenderPolicy.Geometry(1210, 720, 188), g("ru.yandex.taximeter", 1210, 720, 200))
        assertEquals(EmbedRenderPolicy.Geometry(1846, 1080, 283), g(null, 1846, 1080, 300))
        // короткая сторона окна не уже 600 dp, пока плотность выше нижнего предела
        for (short in 366..1080) for (dpi in listOf(160, 200, 300)) {
            val d = EmbedRenderPolicy.wideDensity(short * 2, short, dpi)
            assertTrue("$short при $dpi", short * 160 / d >= 600 && d <= dpi)
            EmbeddedDisplayPolicy.requireSize(maxOf(128, short * 2).coerceAtMost(EmbeddedDisplayPolicy.MAX_WIDTH), short, d)
        }
    }

    @Test fun wideDensityOnlyLowers() {
        // стол уже шире порога (HSD: 128 DPI) - окно не трогаем; мелкое окно упирается в 96
        assertEquals(EmbedRenderPolicy.Geometry(1210, 720, 128), g("com.spotify.music", 1210, 720, 128))
        assertEquals(EmbedRenderPolicy.Geometry(400, 300, 96), g("com.spotify.music", 400, 300, 200))
    }

    @Test fun assistantKeepsDesktopDensity() {
        assertEquals(EmbedRenderPolicy.Geometry(761, 501, 200), EmbedRenderPolicy.geometry("ru.yandex.searchplugin", 761, 501, 200, wide = false))
    }

    @Test fun navigatorWindowGetsDesktopDensityTimesStep() {
        // ADR 0055: Google Карты в плитке 356×596 на столе 200 DPI раньше шли на 96 DPI (правило 600 dp)
        val gm = NavigatorPick.GOOGLE_MAPS
        assertEquals(EmbedRenderPolicy.Geometry(356, 596, 150), g(gm, 356, 596, 200))
        val steps = (0..3).map { EmbedRenderPolicy.geometry(gm, 356, 596, 200, step = it).density }
        assertEquals(listOf(120, 150, 200, 240), steps)
        assertEquals(240, EmbedRenderPolicy.geometry(NavigatorPick.DGIS, 1210, 720, 200, step = 9).density)
        assertEquals(EmbedRenderPolicy.MIN_DPI, EmbedRenderPolicy.geometry(NavigatorPick.WAZE, 356, 596, 120, step = 0).density)
        // ступень не трогает остальные окна
        assertEquals(EmbedRenderPolicy.Geometry(761, 501, 131), EmbedRenderPolicy.geometry("ru.yandex.taximeter", 761, 501, 200, step = 3))
    }

    @Test fun yandexStepScalesDensityNotBuffer() {
        val auto = g("ru.yandex.yandexmaps", 800, 600, 200)
        val big = EmbedRenderPolicy.geometry("ru.yandex.yandexmaps", 800, 600, 200, step = 3)
        assertEquals(auto.width, big.width)
        assertEquals(auto.height, big.height)
        assertEquals(640, big.density) // 400 × 1,2 / 0,75
        EmbeddedDisplayPolicy.requireSize(big.width, big.height, big.density)
    }

    @Test fun navigatorScaledAt160() {
        assertEquals(EmbedRenderPolicy.Geometry(2000, 1500, 400), g("ru.yandex.yandexnavi", 800, 600, 160))
    }

    @Test fun mapsScaledByDpiRatio() {
        // 200 DPI (высота 720): 400 / 200 = 2
        assertEquals(EmbedRenderPolicy.Geometry(1600, 1200, 400), g("ru.yandex.yandexmaps", 800, 600, 200))
    }

    @Test fun noScaleAtHighDpi() {
        assertEquals(EmbedRenderPolicy.Geometry(800, 600, 400), g("ru.yandex.yandexnavi", 800, 600, 400))
        assertEquals(EmbedRenderPolicy.Geometry(800, 600, 480), g("ru.yandex.yandexnavi", 800, 600, 480))
    }

    @Test fun bigWindowCappedByTileBudget() {
        val r = g("ru.yandex.yandexnavi", 1920, 720, 160)
        assertTrue(r.width.toLong() * r.height <= EmbedRenderPolicy.TILE_PIXELS)
        assertTrue(r.width > 1920 && r.width <= EmbeddedDisplayPolicy.MAX_WIDTH)
        // плотность растёт в то же число раз, что и ширина: dp-кнопки того же размера
        assertEquals(r.width / 1920.0, r.density / 160.0, 0.01)
    }

    @Test fun lowDpiCappedByBufferLimits() {
        val r = g("ru.yandex.yandexnavi", 1280, 1080, 96)
        assertTrue(r.width <= EmbeddedDisplayPolicy.MAX_WIDTH && r.height <= EmbeddedDisplayPolicy.MAX_HEIGHT)
        assertTrue(r.density <= EmbeddedDisplayPolicy.MAX_DENSITY)
        EmbeddedDisplayPolicy.requireSize(r.width, r.height, r.density)
    }
}
