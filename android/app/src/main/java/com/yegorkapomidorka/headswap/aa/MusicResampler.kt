package com.yegorkapomidorka.headswap.aa

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ресэмплер стерео PCM 16 бит LE с 48000 Гц на [outRate] с состоянием между вызовами.
 *
 * Рациональное отношение L/M (44100 = 147/160, 32000 = 2/3, 24000 = 1/2, 22050 = 147/320), полифазный FIR:
 * прототип ФНЧ рассчитан на частоте 48000·L Гц, на каждый выходной кадр берётся одна фаза из [TAPS_PER_PHASE]
 * коэффициентов, нули при вставке не считаются. ФНЧ - окно Кайзера ([KAISER_BETA]), срез ([CUTOFF_RATIO] от
 * выходной частоты, по уровню -6 дБ) ниже Найквиста выхода. Коэффициенты считаются один раз в конструкторе,
 * усиление каждой фазы нормировано на 1 (постоянная составляющая проходит без искажения уровня).
 * История фильтра и фаза живут между вызовами, поэтому границы пакетов бесшовны: результат не зависит от того,
 * как вход нарезан на куски. Вход - кадры по 4 байта; неполный хвост (размер не кратен 4) отбрасывается.
 * Для 48000 класс не нужен: AaBridge в этом случае пропускает PCM как есть.
 */
class MusicResampler(val outRate: Int) {
    private val up: Int                       // L
    private val down: Int                     // M
    private val coef: FloatArray              // [phase * TAPS_PER_PHASE + j]: вес кадра x[i0 - j]
    private val hist = ShortArray(HIST * 2)   // последние HIST входных кадров (L, R) предыдущих вызовов
    private var work = ShortArray(0)          // историю и новый вход кладём подряд; растёт по мере надобности
    private var pos = 0                       // позиция следующего выхода на сетке 48000·L Гц относительно начала нового входа

    init {
        require(outRate in 1 until IN_RATE) { "outRate=$outRate" }
        val g = gcd(IN_RATE, outRate)
        up = outRate / g
        down = IN_RATE / g
        coef = design(up, outRate)
    }

    fun reset() {
        hist.fill(0)
        pos = 0
    }

    fun process(pcm: ByteArray): ByteArray {
        val n = pcm.size / 4
        if (n == 0) return ByteArray(0)
        if (work.size < (HIST + n) * 2) work = ShortArray((HIST + n) * 2)
        val x = work
        System.arraycopy(hist, 0, x, 0, HIST * 2)
        var p = 0
        var q = HIST * 2
        while (p < n * 4) {
            x[q++] = ((pcm[p].toInt() and 0xFF) or (pcm[p + 1].toInt() shl 8)).toShort()
            x[q++] = ((pcm[p + 2].toInt() and 0xFF) or (pcm[p + 3].toInt() shl 8)).toShort()
            p += 4
        }
        val total = n.toLong() * up
        val outFrames = if (total > pos) ((total - pos - 1) / down + 1).toInt() else 0
        val out = ByteArray(outFrames * 4)
        val c = coef
        var u = pos
        var o = 0
        for (k in 0 until outFrames) {
            val i0 = u / up                       // последний входной кадр, влияющий на выход
            val cb = (u - i0 * up) * TAPS_PER_PHASE
            var base = (HIST + i0) * 2            // свёртка идёт назад по времени
            var l = 0f
            var r = 0f
            for (j in 0 until TAPS_PER_PHASE) {
                val h = c[cb + j]
                l += h * x[base]
                r += h * x[base + 1]
                base -= 2
            }
            val sl = sat(l)
            val sr = sat(r)
            out[o++] = sl.toByte()
            out[o++] = (sl shr 8).toByte()
            out[o++] = sr.toByte()
            out[o++] = (sr shr 8).toByte()
            u += down
        }
        pos = (u - total).toInt()
        System.arraycopy(x, n * 2, hist, 0, HIST * 2)
        return out
    }

    private fun sat(v: Float): Int {
        val i = Math.round(v)
        return if (i > 32767) 32767 else if (i < -32768) -32768 else i
    }

    companion object {
        const val IN_RATE = 48000
        const val TAPS_PER_PHASE = 32
        const val CUTOFF_RATIO = 0.42
        const val KAISER_BETA = 6.0
        private const val HIST = TAPS_PER_PHASE - 1

        private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        /** Прототип ФНЧ на 48000·L Гц длиной L·TAPS_PER_PHASE, разложенный по L фазам; сумма каждой фазы = 1. */
        private fun design(up: Int, outRate: Int): FloatArray {
            val len = up * TAPS_PER_PHASE
            val fc = CUTOFF_RATIO * outRate / (IN_RATE.toDouble() * up)    // относительно частоты дискретизации прототипа
            val mid = (len - 1) / 2.0
            val h = DoubleArray(len)
            for (j in 0 until len) {
                val t = j - mid
                val s = if (t == 0.0) 2 * fc else sin(2 * PI * fc * t) / (PI * t)
                val u = t / mid
                h[j] = s * bessel0(KAISER_BETA * sqrt(max(0.0, 1 - u * u))) / bessel0(KAISER_BETA)
            }
            val out = FloatArray(len)
            for (ph in 0 until up) {
                var sum = 0.0
                for (j in 0 until TAPS_PER_PHASE) sum += h[ph + up * j]
                for (j in 0 until TAPS_PER_PHASE) out[ph * TAPS_PER_PHASE + j] = (h[ph + up * j] / sum).toFloat()
            }
            return out
        }

        private fun bessel0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (k < 50) {
                val f = x / (2 * k)
                term *= f * f
                sum += term
                if (term < 1e-12 * sum) break
                k++
            }
            return sum
        }
    }
}
