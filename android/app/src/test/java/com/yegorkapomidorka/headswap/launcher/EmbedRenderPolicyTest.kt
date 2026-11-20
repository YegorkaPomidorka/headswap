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

    @Test fun navigatorDoubledAt160() {
        assertEquals(EmbedRenderPolicy.Geometry(1600, 1200, 320), g("ru.yandex.yandexnavi", 800, 600, 160))
    }

    @Test fun mapsScaledByDpiRatio() {
        // 200 DPI (высота 720): 320 / 200 = 1,6
        assertEquals(EmbedRenderPolicy.Geometry(1280, 960, 320), g("ru.yandex.yandexmaps", 800, 600, 200))
    }

    @Test fun noScaleAtHighDpi() {
        assertEquals(EmbedRenderPolicy.Geometry(800, 600, 320), g("ru.yandex.yandexnavi", 800, 600, 320))
        assertEquals(EmbedRenderPolicy.Geometry(800, 600, 400), g("ru.yandex.yandexnavi", 800, 600, 400))
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
