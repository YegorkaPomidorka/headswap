package com.yegorkapomidorka.headswap.launcher

import com.yegorkapomidorka.headswap.dex.EmbeddedDisplayPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedRenderPolicyTest {
    private fun g(pkg: String?, w: Int, h: Int, dpi: Int) = EmbedRenderPolicy.geometry(pkg, w, h, dpi)

    @Test fun otherAppsUnchanged() {
        assertEquals(EmbedRenderPolicy.Geometry(800, 600, 160), g("com.spotify.music", 800, 600, 160))
        assertEquals(EmbedRenderPolicy.Geometry(800, 600, 160), g(null, 800, 600, 160))
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

    @Test fun portraitAppGetsWideDensity() {
        // h1264: 720p при 200 DPI - столбик, при 128 - на всю плитку; плитка 501 px остаётся выше 600 dp
        assertEquals(EmbedRenderPolicy.Geometry(761, 501, 128), g2("ru.yandex.taximeter", 761, 501, 200))
        assertEquals(EmbedRenderPolicy.Geometry(1210, 720, 128), g2("ru.yandex.taximeter", 1210, 720, 200))
        assertTrue(501 * 160 / EmbedRenderPolicy.wideDensity(200) >= EmbedRenderPolicy.WIDE_MIN_DP)
        // плотность только вниз и не ниже предела дочернего дисплея
        assertEquals(96, EmbedRenderPolicy.wideDensity(120))
        assertEquals(EmbedRenderPolicy.Geometry(800, 600, 96), g2("x.y", 800, 600, 96))
        EmbeddedDisplayPolicy.requireSize(800, 600, EmbedRenderPolicy.wideDensity(96))
    }

    @Test fun mapsStaySupersampledEvenIfPortrait() {
        assertEquals(g("ru.yandex.yandexnavi", 800, 600, 200), g2("ru.yandex.yandexnavi", 800, 600, 200))
    }

    private fun g2(pkg: String?, w: Int, h: Int, dpi: Int) = EmbedRenderPolicy.geometry(pkg, w, h, dpi, portrait = true)

    @Test fun lowDpiCappedByBufferLimits() {
        val r = g("ru.yandex.yandexnavi", 1280, 1080, 96)
        assertTrue(r.width <= EmbeddedDisplayPolicy.MAX_WIDTH && r.height <= EmbeddedDisplayPolicy.MAX_HEIGHT)
        assertTrue(r.density <= EmbeddedDisplayPolicy.MAX_DENSITY)
        EmbeddedDisplayPolicy.requireSize(r.width, r.height, r.density)
    }
}
