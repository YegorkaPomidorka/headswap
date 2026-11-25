package com.yegorkapomidorka.headswap.aa

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/** Настройки эквалайзера: 7 полос в дБ (EQ_FREQS) и предусиление. Неизменяемые: мост сравнивает по equals. */
data class EqSettings(val bands: List<Int> = FLAT_BANDS, val preamp: Int = 0) {
    /** Всё по нулям: мост пропускает PCM бит-в-бит. */
    val isFlat: Boolean get() = preamp == 0 && bands.all { it == 0 }

    /** Полос ровно EQ_FREQS.size, значения в допустимых пределах. */
    fun normalized(): EqSettings = EqSettings(
        List(EQ_FREQS.size) { (bands.getOrNull(it) ?: 0).coerceIn(EQ_BAND_MIN, EQ_BAND_MAX) },
        preamp.coerceIn(EQ_PREAMP_MIN, EQ_PREAMP_MAX),
    )

    fun bandsCsv(): String = bands.joinToString(",")

    companion object {
        val FLAT_BANDS: List<Int> = List(7) { 0 }

        /** CSV из prefs; мусор и недостающие полосы = 0. */
        fun parseBands(csv: String?): List<Int> {
            val parts = csv?.split(',').orEmpty()
            return List(EQ_FREQS.size) { parts.getOrNull(it)?.trim()?.toIntOrNull() ?: 0 }
        }
    }
}

/** Центральные частоты полос, Гц. */
val EQ_FREQS = intArrayOf(63, 160, 400, 1000, 2500, 6300, 16000)
const val EQ_BAND_MIN = -12
const val EQ_BAND_MAX = 12
const val EQ_PREAMP_MIN = -12
const val EQ_PREAMP_MAX = 6

/** Пресеты; у «громких» preamp отрицательный, чтобы буст не упирался в лимитер. CUSTOM — значения пользователя. */
enum class EqPreset(val key: String, val settings: EqSettings?) {
    FLAT("flat", EqSettings()),
    BASS("bass", EqSettings(listOf(6, 4, 1, 0, 0, 0, 0), -5)),
    VOCAL("vocal", EqSettings(listOf(-2, -1, 1, 3, 3, 1, 0), -3)),
    /** Удар 160 Гц и гитарное присутствие 2.5k, без суббаса и «воздуха». */
    ROCK("rock", EqSettings(listOf(3, 4, 0, -1, 3, 2, 0), -5)),

    /** Глубокая «улыбка»: суббас и верх 16k, провал середины. */
    ELECTRONIC("electronic", EqSettings(listOf(7, 3, -2, -3, 0, 3, 6), -7)),
    CUSTOM("custom", null);

    companion object {
        fun parse(key: String?): EqPreset = values().firstOrNull { it.key == key } ?: FLAT
    }
}

/** Текущие настройки EQ для моста: UI пишет через AaPrefs, мост читает на каждом буфере (один процесс). */
object EqLive {
    @Volatile var settings: EqSettings = EqSettings()
}

/**
 * 7 peaking-biquad (RBJ cookbook, Q=1) + preamp + мягкий лимитер выше −1 dBFS.
 * Обрабатывает interleaved PCM16 LE на месте; состояние фильтров своё у каждого канала.
 * Чистый Kotlin, без Android API.
 */
class Equalizer(sampleRate: Int = 48000, channels: Int = 2) {
    private val rate = sampleRate
    private val chans = channels
    private var settings = EqSettings()
    private var preGain = 1.0

    /** Фактически применённое предусиление: за буфер линейно идёт к preGain, чтобы смена пресета не щёлкала. */
    private var curGain = 1.0
    private var rampStep = 0.0
    private var rampLeft = 0

    /** Активные полосы (усиление ≠ 0 и ниже Найквиста): индексы и нормированные коэффициенты. */
    private var active = IntArray(0)
    private val b0 = DoubleArray(BANDS)
    private val b1 = DoubleArray(BANDS)
    private val b2 = DoubleArray(BANDS)
    private val a1 = DoubleArray(BANDS)
    private val a2 = DoubleArray(BANDS)

    /** Состояние TDF-II: [канал * BANDS + полоса]. */
    private val z1 = DoubleArray(BANDS * chans)
    private val z2 = DoubleArray(BANDS * chans)
    private var bypassed = true

    /** Новые настройки: пересчёт коэффициентов без сброса состояния (без щелчка при движении слайдера). */
    fun update(s: EqSettings) {
        val n = s.normalized()
        if (n == settings) return
        settings = n
        recompute()
    }

    private fun recompute() {
        preGain = 10.0.pow(settings.preamp / 20.0)
        rampLeft = maxOf(1, rate / 50)
        rampStep = (preGain - curGain) / rampLeft
        val idx = ArrayList<Int>()
        for (i in 0 until BANDS) {
            val g = settings.bands[i]
            val f = EQ_FREQS[i]
            if (g == 0 || f >= rate * 0.45) continue
            val a = 10.0.pow(g / 40.0)
            val w0 = 2 * PI * f / rate
            val alpha = sin(w0) / (2 * Q)
            val cw = cos(w0)
            val a0 = 1 + alpha / a
            b0[i] = (1 + alpha * a) / a0
            b1[i] = -2 * cw / a0
            b2[i] = (1 - alpha * a) / a0
            a1[i] = -2 * cw / a0
            a2[i] = (1 - alpha / a) / a0
            idx.add(i)
        }
        // полоса, ставшая 0 дБ, выпадает из цепочки: её состояние обнуляем, чтобы при возврате не всплыл хвост
        for (i in 0 until BANDS) if (i !in idx) for (c in 0 until chans) {
            z1[c * BANDS + i] = 0.0
            z2[c * BANDS + i] = 0.0
        }
        active = idx.toIntArray()
    }

    /** PCM16 LE interleaved на месте; неполный хвостовой кадр не трогается. */
    fun process(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset) {
        if (enterBypass()) return
        val frames = length / (2 * chans)
        var p = offset
        for (f in 0 until frames) {
            rampFrame()
            for (c in 0 until chans) {
                val s = ((pcm[p].toInt() and 0xFF) or (pcm[p + 1].toInt() shl 8)).toShort()
                val out = sample(s, c)
                pcm[p] = out.toByte()
                pcm[p + 1] = (out shr 8).toByte()
                p += 2
            }
        }
    }

    /** Шаг curGain к preGain на кадр, фиксированная длина ~20 мс (не зависит от нарезки буферов). */
    private fun rampFrame() {
        if (rampLeft <= 0) return
        curGain += rampStep
        if (--rampLeft == 0) curGain = preGain
    }

    /** true = обработка не нужна. На выходе из bypass состояние чистое: старый хвост не всплывёт щелчком. */
    private fun enterBypass(): Boolean {
        if (settings.isFlat) {
            bypassed = true
            curGain = 1.0
            rampLeft = 0
            return true
        }
        if (bypassed) {
            z1.fill(0.0)
            z2.fill(0.0)
            bypassed = false
        }
        return false
    }

    private fun sample(s: Short, c: Int): Int {
        var x = s / 32768.0 * curGain
        val base = c * BANDS
        for (i in active) {
            val k = base + i
            val y = b0[i] * x + z1[k]
            z1[k] = b1[i] * x - a1[i] * y + z2[k]
            z2[k] = b2[i] * x - a2[i] * y
            x = y
        }
        return (softLimit(x) * 32767.0).roundToInt().coerceIn(-32768, 32767)
    }

    companion object {
        const val BANDS = 7
        const val Q = 1.0

        /** Колено −1 dBFS: ниже линейно, выше tanh-сжатие, асимптота 1.0 (переполнения short нет). */
        private const val KNEE = 0.891

        fun softLimit(x: Double): Double {
            val ax = abs(x)
            if (ax <= KNEE) return x
            val y = KNEE + (1 - KNEE) * tanh((ax - KNEE) / (1 - KNEE))
            return if (x < 0) -y else y
        }
    }
}
