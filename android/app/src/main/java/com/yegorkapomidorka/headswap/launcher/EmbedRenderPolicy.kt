package com.yegorkapomidorka.headswap.launcher

import com.yegorkapomidorka.headswap.dex.EmbeddedDisplayPolicy
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Геометрия дочернего дисплея окна (ADR 0049). MapKit Яндекса рисует знаки и подписи карты в пикселях под ~320 DPI,
 * на рабочем столе ГУ (160–200 DPI) они выходят вдвое мельче. Для карт буфер и плотность растут в одно число раз:
 * кнопки в dp остаются того же размера, а пиксели карты на экране крупнеют; TextureView ужимает буфер в окно.
 */
object EmbedRenderPolicy {
    val MAP_PACKAGES = setOf("ru.yandex.yandexnavi", "ru.yandex.yandexmaps")
    const val MAP_DPI = 320
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
