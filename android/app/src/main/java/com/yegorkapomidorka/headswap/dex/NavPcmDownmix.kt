package com.yegorkapomidorka.headswap.dex

/**
 * h1318: голос навигатора лаунчера из отдельного AudioMix (48 кГц стерео PCM16 LE) -> моно 16 кГц PCM16 LE,
 * как у канала SPEECH (CarLife VR_INIT 16000/1/16). Стерео усредняется, 48 -> 16 кГц - децимация 3:1 после
 * треугольного ФНЧ [1 2 3 3 2 1]/12 (ноль на 16 кГц гасит зеркальные частоты речи, усиление на постоянной 1).
 * Состояние окна и фаза децимации переходят между вызовами: границы буферов шва не дают.
 */
internal class NavPcmDownmix {
    private val window = IntArray(TAPS.size)
    private var phase = 0

    /** [stereo] - целое число стерео-кадров (кратно 4 байтам); на выходе 2 байта на каждые 3 кадра, остаток фазы копится. */
    fun process(stereo: ByteArray, length: Int = stereo.size): ByteArray {
        require(length in 0..stereo.size && length % 4 == 0) { "нужны целые стерео-кадры PCM16" }
        val frames = length / 4
        val out = ByteArray(((phase + frames) / DECIMATION) * 2)
        var o = 0
        for (f in 0 until frames) {
            val l = sample(stereo, f * 4)
            val r = sample(stereo, f * 4 + 2)
            System.arraycopy(window, 1, window, 0, window.size - 1)
            window[window.size - 1] = (l + r) shr 1
            if (++phase < DECIMATION) continue
            phase = 0
            var acc = 0
            for (i in TAPS.indices) acc += window[i] * TAPS[i]
            val v = (acc / TAP_SUM).coerceIn(-32768, 32767)
            out[o++] = v.toByte()
            out[o++] = (v shr 8).toByte()
        }
        return out
    }

    private fun sample(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)

    private companion object {
        const val DECIMATION = 3
        val TAPS = intArrayOf(1, 2, 3, 3, 2, 1)
        const val TAP_SUM = 12
    }
}
