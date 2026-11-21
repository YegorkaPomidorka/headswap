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

    /**
     * Короткая сторона окна приложения в dp (ADR 0051). От 600 dp Android 16+ игнорирует жёсткий портрет приложения,
     * а приложения включают планшетную вёрстку; запас 10 dp на округление.
     */
    const val WIDE_DP = 610
    const val MIN_DPI = 96

    data class Geometry(val width: Int, val height: Int, val density: Int)

    /**
     * Плотность окна, при которой его короткая сторона не уже [WIDE_DP]; только вниз от плотности стола [dpi].
     * Считается от размера окна, а не стола: плитка 761×501 одна и та же на столе 720p и 1080p (h1264).
     * Окно ниже 366 px и при [MIN_DPI] уже порога - там портретное приложение останется столбиком.
     */
    fun wideDensity(width: Int, height: Int, dpi: Int): Int =
        min(dpi, max(MIN_DPI, floor(160.0 * min(width, height) / WIDE_DP).toInt()))

    /**
     * Навигаторы (ADR 0055): не портретные, правило 600 dp им не нужно - с ним Google Карты в плитке падали до 96 DPI
     * и всё на карте было мелким. Им плотность стола × ступень «Масштаба карты» ([WINDOW_STEPS]).
     */
    val NAV_PACKAGES = (NavigatorPick.KNOWN + NavigatorPick.KNOWN_ZH).toSet()

    /**
     * Множители «Масштаба карты» окна навигатора к плотности стола, индексы те же, что у ступеней своего хоста Car App
     * ([com.yegorkapomidorka.headswap.carapp.CarConfigMath.STEPS]): Мельче / Авто / Крупнее / Ещё крупнее.
     * На столе 720p (200 DPI) - 120 / 150 / 200 / 240: «Авто» ниже стола, ×1 на стенде 21.11 Егору было крупно.
     */
    val WINDOW_STEPS = floatArrayOf(0.6f, 0.75f, 1f, 1.2f)
    const val AUTO_STEP = 1

    fun windowStep(i: Int): Float = WINDOW_STEPS[i.coerceIn(0, WINDOW_STEPS.size - 1)]

    private fun stepped(dpi: Int, step: Int): Int = scaled(dpi, windowStep(step))

    /** Картам Яндекса «Авто» - как было (ADR 0050), ступени - во столько же раз от него, как у остальных. */
    private fun yandexStepped(dpi: Int, step: Int): Int = scaled(dpi, windowStep(step) / windowStep(AUTO_STEP))

    private fun scaled(dpi: Int, k: Float): Int = (dpi * k).roundToInt().coerceIn(MIN_DPI, EmbeddedDisplayPolicy.MAX_DENSITY)

    /**
     * [wide] = false - окно помощника: плашка поверх экрана, ей плотность стола.
     * [step] - ступень «Масштаба карты» навигатора [pkg], остальным окнам не важна.
     */
    fun geometry(pkg: String?, width: Int, height: Int, dpi: Int, wide: Boolean = true, step: Int = AUTO_STEP): Geometry {
        if (width <= 0 || height <= 0 || dpi <= 0) return Geometry(width, height, dpi)
        if (pkg in NAV_PACKAGES && pkg !in MAP_PACKAGES) return Geometry(width, height, stepped(dpi, step))
        if (pkg !in MAP_PACKAGES) return Geometry(width, height, if (wide) wideDensity(width, height, dpi) else dpi)
        val plain = Geometry(width, height, yandexStepped(dpi, step)) // карты не портретные, им плотность только вверх
        val scale = minOf(
            MAP_DPI.toDouble() / dpi,
            EmbeddedDisplayPolicy.MAX_WIDTH.toDouble() / width,
            EmbeddedDisplayPolicy.MAX_HEIGHT.toDouble() / height,
            EmbeddedDisplayPolicy.MAX_DENSITY.toDouble() / dpi,
            sqrt(TILE_PIXELS.toDouble() / (width.toLong() * height)),
        )
        if (scale <= 1.0) return plain
        // буфер растёт в scale раз, плотность - ещё и на ступень: карта та же, кнопки и подписи крупнее/мельче
        return Geometry(floor(width * scale).toInt(), floor(height * scale).toInt(),
            yandexStepped(min(EmbeddedDisplayPolicy.MAX_DENSITY, max(dpi, (dpi * scale).roundToInt())), step))
    }
}
