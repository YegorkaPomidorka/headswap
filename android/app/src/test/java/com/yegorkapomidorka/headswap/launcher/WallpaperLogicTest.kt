package com.yegorkapomidorka.headswap.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperLogicTest {
    @Test fun unknownIdMeansNone() {
        assertEquals(WallpaperId.NONE, WallpaperId.restore(null))
        assertEquals(WallpaperId.NONE, WallpaperId.restore(""))
        assertEquals(WallpaperId.NONE, WallpaperId.restore("aurora"))
        assertEquals(WallpaperId.NONE, WallpaperId.restore("CUSTOM"))
        for (id in WallpaperId.entries) assertEquals(id, WallpaperId.restore(id.key))
    }

    @Test fun clampKeepsZeroToHundred() {
        assertEquals(0, WallpaperLogic.clamp(-7))
        assertEquals(35, WallpaperLogic.clamp(35))
        assertEquals(100, WallpaperLogic.clamp(250))
    }

    @Test fun blurPxScalesWithHeight() {
        assertEquals(0f, WallpaperLogic.blurPx(0, 720), 0f)
        assertEquals(32.4f, WallpaperLogic.blurPx(100, 720), 0.01f)
        assertEquals(WallpaperLogic.blurPx(100, 720), WallpaperLogic.blurPx(900, 720), 0f)
        assertEquals(WallpaperLogic.blurPx(50, 1440), 2 * WallpaperLogic.blurPx(50, 720), 0.01f)
    }

    @Test fun overscanHidesBlurEdgesAndIsOneWithoutBlur() {
        assertEquals(1f, WallpaperLogic.overscan(0f, 720), 0f)
        // две ширины размытия на сторону: картинка выше экрана на 4 радиуса в сумме
        assertEquals(1f + 64.8f / 720, WallpaperLogic.overscan(32.4f, 720), 0.001f)
    }

    @Test fun dimAlphaTopsOutBelowFull() {
        assertEquals(0f, WallpaperLogic.dimAlpha(0), 0f)
        assertEquals(0.85f, WallpaperLogic.dimAlpha(100), 0.001f)
        assertEquals(0.85f, WallpaperLogic.dimAlpha(400), 0.001f)
    }

    @Test fun sampleSizeStaysCoveringTheTarget() {
        // 2560x1086 под экран 1280x720: уменьшать нельзя, высоты не хватит
        assertEquals(1, WallpaperLogic.sampleSize(2560, 1086, 1280, 720))
        // миниатюра 320x180: 4 даёт 640x271, 8 - уже 135 по высоте
        assertEquals(4, WallpaperLogic.sampleSize(2560, 1086, 320, 180))
        // длинная сторона фото не меньше 2560, без ограничения по высоте
        assertEquals(2, WallpaperLogic.sampleSize(6000, 4000, 2560, 1))
        assertEquals(1, WallpaperLogic.sampleSize(3000, 4000, 2560, 1))
        assertEquals(1, WallpaperLogic.sampleSize(0, 0, 100, 100))
    }

    @Test fun importScaleOnlyShrinks() {
        assertEquals(1f, WallpaperLogic.importScale(2000, 1000), 0f)
        assertEquals(0.5f, WallpaperLogic.importScale(5120, 2880), 0.001f)
        assertTrue(WallpaperLogic.importScale(2560, 10000) < 0.5f)
        assertEquals(1f, WallpaperLogic.importScale(0, 0), 0f)
    }
}
