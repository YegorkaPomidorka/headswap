package com.yegorkapomidorka.headswap.launcher

import com.yegorkapomidorka.headswap.dex.EmbeddedDisplayPolicy
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Геометрия дочернего дисплея окна (ADR 0050). На низкой плотности рабочего стола ГУ (160–200 DPI) карта Яндекса
 * в окне огрублённая: огромная жёлтая стрелка и значки, мало подписей, мелкий охват. Для карт буфер и плотность
 * растут в одно число раз до 400 DPI: кнопки в dp того же размера, карта как на телефоне; TextureView ужимает буфер в окно.
 */
object EmbedRenderPolicy {
    val MAP_PACKAGES = setOf("ru.yandex.yandexnavi", "ru.yandex.yandexmaps")
    const val MAP_DPI = 400
    /** Половина общего бюджета пикселей хоста: два окна-карты помещаются вместе. */
    const val TILE_PIXELS = EmbeddedDisplayPolicy.MAX_TOTAL_PIXELS / 2

    /** Android 17 (VANILLA_ICE_CREAM + 1): дисплеи создаёт shell-хост VirtualDevice, режим окна на них не меняется. */
    const val WIDE_SDK = 37
    /** Короткая сторона окна в dp, от которой Android 16+ сам игнорирует жёсткую ориентацию приложения. */
    const val WIDE_MIN_DP = 600

    data class Geometry(val width: Int, val height: Int, val density: Int)

    /**
     * Плотность окна портретного приложения (ADR 0051): 0,64 от плотности стола, как у HSD (720p: 200 -> 128 DPI,
     * 900 dp по высоте). Одна на плитку и на развёрнутое окно, чтобы приложение не пересоздавалось при развороте.
     * Плитка высотой 501 px при 128 DPI - 626 dp, выше порога [WIDE_MIN_DP]. Цена: интерфейс мельче, Gboard делится.
     */
    fun wideDensity(dpi: Int): Int = max(96, (dpi * 0.64).roundToInt())

    /** [portrait] - портретное по манифесту ([EmbeddedDisplayPolicy.portraitApp]) на Android 17+. */
    fun geometry(pkg: String?, width: Int, height: Int, dpi: Int, portrait: Boolean = false): Geometry {
        if (portrait && pkg !in MAP_PACKAGES && dpi > 0) return Geometry(width, height, min(dpi, wideDensity(dpi)))
        val plain = Geometry(width, height, dpi)
        if (pkg !in MAP_PACKAGES || width <= 0 || height <= 0 || dpi <= 0) return plain
        val scale = minOf(
            MAP_DPI.toDouble() / dpi,
            EmbeddedDisplayPolicy.MAX_WIDTH.toDouble() / width,
            EmbeddedDisplayPolicy.MAX_HEIGHT.toDouble() / height,
            EmbeddedDisplayPolicy.MAX_DENSITY.toDouble() / dpi,
            sqrt(TILE_PIXELS.toDouble() / (width.toLong() * height)),
        )
        if (scale <= 1.0) return plain
        return Geometry(floor(width * scale).toInt(), floor(height * scale).toInt(),
            min(EmbeddedDisplayPolicy.MAX_DENSITY, max(dpi, (dpi * scale).roundToInt())))
    }
}
