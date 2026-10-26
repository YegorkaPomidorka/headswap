package com.yegorkapomidorka.headswap.aa

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Битрейт энкодера по размеру ключевых кадров (Hyundai Tucson, Mobis CCNC, h242test4): на карте с движением IDR от
 * c2.qti.avc.encoder (VBR, 3,84 Мбит/с) рос каждую секунду 200 -> 712 КБ, и после ~600 КБ ГУ уходила в цикл
 * «повтор VIDEO_ENCODER_START -> EIO». IDR больше [limitBytes] - битрейт ×[downFactor] (не ниже [minBps]), и так на
 * каждый такой IDR; [calmCount] IDR подряд меньше [calmBytes] - обратно ×[upFactor] (не выше [maxBps]).
 * Чистая логика: кодек не трогает. Зовётся с нитей энкодеров (транскодер и заглушка), поэтому синхронизирована.
 */
class IdrBitrateCeiling(
    private val maxBps: Int,
    private val minBps: Int = MIN_BPS,
    private val limitBytes: Int = LIMIT_BYTES,
    private val calmBytes: Int = CALM_BYTES,
    private val calmCount: Int = CALM_COUNT,
    private val downFactor: Double = DOWN_FACTOR,
    private val upFactor: Double = UP_FACTOR,
) {
    /** Текущий целевой битрейт, бит/с. */
    @Volatile var current = maxBps
        private set

    private var calm = 0

    /** IDR выше лимита, а битрейт уже на полу: сколько таких подряд (для одной строки лога, не на каждый кадр). */
    var stuckAtFloor = 0
        private set

    /** Очередной IDR размером [size] байт. Возвращает новый битрейт, если он изменился, иначе null. */
    @Synchronized
    fun onKeyFrame(size: Int): Int? {
        if (size > limitBytes) {
            calm = 0
            val target = max(minBps, (current * downFactor).roundToInt())
            if (target >= current) {
                stuckAtFloor++
                return null
            }
            stuckAtFloor = 0
            current = target
            return target
        }
        stuckAtFloor = 0
        if (size >= calmBytes) {
            calm = 0
            return null
        }
        if (++calm < calmCount) return null
        calm = 0
        if (current >= maxBps) return null
        val target = min(maxBps, max(current + 1, (current * upFactor).roundToInt()))
        current = target
        return target
    }

    companion object {
        const val LIMIT_BYTES = 300 * 1024
        const val CALM_BYTES = 150 * 1024
        const val CALM_COUNT = 5
        const val MIN_BPS = 1_000_000
        const val DOWN_FACTOR = 0.7
        const val UP_FACTOR = 1.15
    }
}
