package com.yegorkapomidorka.headswap.dex

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Программная громкость звука по USB. Захват AudioMix loopback / REMOTE_SUBMIX берёт PCM до громкости потока,
 * поэтому STREAM_MUSIC телефона на ГУ не слышен: жест громкости (ADR 0038) двигал шкалу, а звук не менялся.
 * Усиление считаем из индекса STREAM_MUSIC и применяем к PCM перед отправкой в CarLife.
 */
internal object DesktopVolumeGain {
    /** Диапазон от минимального ненулевого шага до максимума, дБ. */
    const val RANGE_DB = 40f

    /** Индекс громкости -> множитель 0..1: 0 - тишина, максимум - без изменений, между ними линейно в дБ. */
    fun forIndex(index: Int, max: Int): Float {
        if (max <= 0 || index >= max) return 1f
        if (index <= 0) return 0f
        val v = index.toFloat() / max
        return 10f.pow(-RANGE_DB * (1f - v) / 20f)
    }

    /** PCM 16 бит little-endian на месте. */
    fun apply(pcm: ByteArray, gain: Float) {
        if (gain >= 1f) return
        val q = (gain * 65536f).roundToInt() // фиксированная точка 16.16
        var i = 0
        while (i + 1 < pcm.size) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val out = ((s.toLong() * q) shr 16).toInt()
            pcm[i] = out.toByte()
            pcm[i + 1] = (out shr 8).toByte()
            i += 2
        }
    }
}
