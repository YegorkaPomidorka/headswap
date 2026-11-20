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

    data class Geometry(val width: Int, val height: Int, val density: Int)

    fun geometry(pkg: String?, width: Int, height: Int, dpi: Int): Geometry {
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
