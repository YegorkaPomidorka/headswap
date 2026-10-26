package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R
import java.util.Locale

/** Центральный прямоугольник исходного кадра в долях (u вправо, v вверх; кроп симметричный, поэтому ориентация v не важна). */
data class VideoCrop(val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
    val isNone: Boolean get() = this == NONE

    companion object {
        val NONE = VideoCrop(0f, 0f, 1f, 1f)
    }
}

/**
 * Геометрия видео: единственный источник правды для AA (SDR), выхода CarLife (INIT_DONE, энкодер), кропа и тача.
 * Снимается один раз на подключение (AaBridgeCore.videoMode) и дальше везде берётся оттуда, prefs не читаются.
 * Реализуют пресет [VideoMode] и пользовательский [CustomVideoMode].
 */
interface VideoGeometry {
    /** Разрешение AA в SDR (SDR-строка AapMessages.serviceDiscoveryResponse). */
    val aaResolution: String
    val aaW: Int
    val aaH: Int
    /** Поля margin_width / margin_height в VideoConfiguration SDR (суммарно, делятся пополам по краям). */
    val marginW: Int
    val marginH: Int
    val density: Int
    /** Размер кадра, который уходит на ГУ (INIT_DONE и энкодер). */
    val outW: Int
    val outH: Int
    val bitrate: Int

    /** Короткая метка для логов: имя enum или «custom 2240x976». */
    val label: String

    /** Кроп транскодера: центральная полоса без margin-ов (AA рисует интерфейс только внутри них). */
    val crop: VideoCrop
        get() = if (marginW == 0 && marginH == 0) VideoCrop.NONE
        else VideoCrop(marginW / 2f / aaW, marginH / 2f / aaH, 1f - marginW / 2f / aaW, 1f - marginH / 2f / aaH)

    /** Кроп транскодером: в passthrough WIDE доступен только через SPS-кроп ([spsCropable]). */
    val needsTranscode: Boolean get() = !crop.isNone

    /**
     * Поля можно отрезать в passthrough правкой SPS (h529): область интерфейса 1:1 с кадром ГУ, без масштаба,
     * и половина каждого поля кратна единице кропа 4:2:0 (2 px).
     */
    val spsCropable: Boolean
        get() = !crop.isNone && outW == aaW - marginW && outH == aaH - marginH && (marginW / 2) % 2 == 0 && (marginH / 2) % 2 == 0

    /** Геометрия, которая реально применится при данном переключателе перекодирования и SPS-кропе (эксперимент h529). */
    fun effective(transcode: Boolean, spsCrop: Boolean = false): VideoGeometry =
        if (!transcode && needsTranscode && !(spsCrop && spsCropable)) VideoMode.R720 else this

    /** Метка выхода для UI: «1280×720». */
    val outLabel: String get() = "${outW}×$outH"

    /**
     * Координаты тача ГУ (кадр outW×outH) -> координаты AA. Единственное место такого маппинга.
     * С margin-ами AA ждёт тач в координатах области интерфейса (без полей), а не всего кадра:
     * сдвиг на margin/2 давал промах на 120 px вниз (Desay G6S, лог 2026-10-10). Поэтому без сдвига, только clamp в область.
     * Если область интерфейса масштабируется в кадр ГУ (WIDE_976: 1920×836 -> 2240×976), координаты пересчитываются пропорционально.
     * Без margin-ов - 1:1 как есть, без clamp.
     */
    fun mapTouch(x: Int, y: Int): Pair<Int, Int> {
        if (marginW == 0 && marginH == 0) return x to y
        val cw = aaW - marginW
        val ch = aaH - marginH
        val sx = if (cw == outW) x else (x.toLong() * cw / outW).toInt()
        val sy = if (ch == outH) y else (y.toLong() * ch / outH).toInt()
        return sx.coerceIn(0, cw - 1) to sy.coerceIn(0, ch - 1)
    }

    /** Density, которая уйдёт в SDR: ручная из настроек или своя у режима (AaDensity.AUTO). На кроп и тач не влияет. */
    fun densityFor(densityOverride: Int): Int = if (densityOverride == AaDensity.AUTO) density else densityOverride

    /** Опции SDR для AA этой геометрии; densityOverride - ручной DPI интерфейса AA (AaDensity.AUTO = density режима). */
    fun sdOptions(fullAudio: Boolean, densityOverride: Int = AaDensity.AUTO, carMake: String? = null, marginsAtBottom: Boolean = false) = AapSdOptions(
        resolution = aaResolution, density = densityFor(densityOverride), marginW = marginW, marginH = marginH, fullAudio = fullAudio,
        carMake = carMake, marginsAtBottom = marginsAtBottom,
    )

    /** Строка для лога. */
    fun describe(densityOverride: Int = AaDensity.AUTO): String
}

/** Разрешение видео - пресет. */
enum class VideoMode(
    val titleRes: Int,
    val subRes: Int,
    override val aaResolution: String,
    override val marginW: Int,
    override val marginH: Int,
    override val density: Int,
    override val outW: Int,
    override val outH: Int,
    override val bitrate: Int,
) : VideoGeometry {
    R800(R.string.video_r800_title, R.string.video_r800_sub, "800x480", 0, 0, 120, 800, 480, 4_000_000),
    /** 1024×600 - частое разрешение китайских ГУ (Pioneer RM56E8B, h277): из кадра AA 1280×720 вырезается центр 1024×600, 1:1. */
    R1024(R.string.video_r1024_title, R.string.video_r1024_sub, "1280x720", 256, 120, 160, 1024, 600, 2_000_000),
    R720(R.string.video_r720_title, R.string.video_r720_sub, "1280x720", 0, 0, 160, 1280, 720, 4_000_000),
    R1080(R.string.video_r1080_title, R.string.video_r1080_sub, "1920x1080", 0, 0, 240, 1920, 1080, 8_000_000),
    R1440(R.string.video_r1440_title, R.string.video_r1440_sub, "2560x1440", 0, 0, 320, 2560, 1440, 12_000_000),
    WIDE(R.string.video_wide_title, R.string.video_wide_sub, "1280x720", 0, 240, 160, 1280, 480, 4_000_000),
    /** 1728×648 - нативный экран Desay SV G6S (Мазда): из кадра AA 1920×1080 вырезается центр, поля по обеим осям. */
    WIDE_MID(R.string.video_wide_mid_title, R.string.video_wide_mid_sub, "1920x1080", 192, 432, 160, 1728, 648, 5_000_000),
    WIDE_HD(R.string.video_wide_hd_title, R.string.video_wide_hd_sub, "1920x1080", 0, 360, 160, 1920, 720, 6_000_000),
    /** 1920×640 - экран Citroen C5 X (ГУ просит 1920×640, h269): из кадра AA 1920×1080 вырезается центральная полоса 1920×640. */
    WIDE_640(R.string.video_wide_640_title, R.string.video_wide_640_sub, "1920x1080", 0, 440, 160, 1920, 640, 6_000_000),
    /** 1280×640 - экран Skoda Octavia A8 Pro (ГУ SKODA CNS3 просит 1280×640, h292): из кадра AA 1280×720 вырезается центральная полоса 1280×640, 1:1. */
    WIDE_2_1(R.string.video_wide_2_1_title, R.string.video_wide_2_1_sub, "1280x720", 0, 80, 160, 1280, 640, 4_000_000),
    /** 2240×976 - окно CarLife на VW CNS3 (Tiguan L Pro, экран 2240×1260 с полосами штатного ГУ): из кадра AA 1920×1080 вырезается центральная полоса 1920×836 и масштабируется ×1,167. */
    WIDE_976(R.string.video_wide_976_title, R.string.video_wide_976_sub, "1920x1080", 0, 244, 160, 2240, 976, 8_000_000);

    override val aaW: Int = aaResolution.substringBefore('x').toInt()
    override val aaH: Int = aaResolution.substringAfter('x').toInt()
    override val label: String get() = name

    /** Режим, который реально применится при данном переключателе перекодирования и SPS-кропе. */
    override fun effective(transcode: Boolean, spsCrop: Boolean): VideoMode =
        if (!transcode && needsTranscode && !(spsCrop && spsCropable)) R720 else this

    /**
     * Строка для лога: «видео-режим WIDE: AA 1280x720 margins 0x240 density 160, CarLife 1280x480, кроп v 0.167..0.833».
     * С ручным DPI: «density 200 (вручную, авто 160)».
     */
    override fun describe(densityOverride: Int): String {
        fun f(v: Float) = String.format(Locale.US, "%.3f", v)
        val cropText = if (crop.isNone) "без кропа" else buildString {
            append("кроп")
            if (crop.u0 != 0f) append(" u ${f(crop.u0)}..${f(crop.u1)}")
            if (crop.v0 != 0f) append(" v ${f(crop.v0)}..${f(crop.v1)}")
        }
        val dens = if (densityOverride == AaDensity.AUTO) "$density" else "$densityOverride (вручную, авто $density)"
        return "видео-режим $name: AA $aaResolution margins ${marginW}x$marginH density $dens, CarLife ${outW}x$outH, $cropText"
    }

    companion object {
        val DEFAULT = R720

        /** По имени из prefs; неизвестное/пустое = по умолчанию. */
        fun parse(key: String?): VideoMode = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}

/** Причина, по которой размер для [CustomVideoMode] не принимается. */
enum class CustomResError { WIDTH_RANGE, HEIGHT_RANGE, ODD, PORTRAIT }

/**
 * Своё разрешение (раздел для разработчиков): кадр outW×outH для ГУ, который строится как в WIDE-режимах:
 * подложка AA (800x480, 1280x720 или 1920x1080) + поля + кроп центра и масштаб в транскодере.
 * Наименьшая подложка, в которую кадр влезает целиком = поля, масштаб 1:1. Шире 1920 - аспект вписывается в 1920x1080
 * и область растягивается до кадра (как WIDE_976). Только с перекодированием. Все поля, кроме outW/outH, вычисляются,
 * поэтому сравнение по значению (data class) покрывает всю геометрию.
 * Конструктор не проверяет диапазон (так воспроизводятся и старые пресеты вроде 1024x600); для ввода пользователя - [from].
 */
data class CustomVideoMode(override val outW: Int, override val outH: Int) : VideoGeometry {
    private val plate: Pair<Int, Int> = plateFor(outW, outH)
    private val area: Pair<Int, Int> = areaFor(outW, outH, plate)

    override val aaW: Int get() = plate.first
    override val aaH: Int get() = plate.second
    override val aaResolution: String get() = "${aaW}x$aaH"
    override val marginW: Int get() = plate.first - area.first
    override val marginH: Int get() = plate.second - area.second
    override val density: Int get() = DENSITY
    override val label: String get() = "custom ${outW}x$outH"

    /** Размер области интерфейса AA (подложка без полей), которая масштабируется в кадр. */
    val areaW: Int get() = area.first
    val areaH: Int get() = area.second

    /** Битрейт растёт с площадью кадра: 4 Мбит/с на 1280x720, в пределах 2..8 Мбит/с. */
    override val bitrate: Int
        get() = (BASE_BITRATE.toLong() * outW * outH / (1280L * 720L)).coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt()

    /** Масштаб области до кадра: 1.0 = 1:1. */
    val scale: Double get() = maxOf(outW.toDouble() / areaW, outH.toDouble() / areaH)

    /** Без перекодирования свой размер недоступен, даже когда полей нет (1280x720): кадр строит только транскодер. */
    override val needsTranscode: Boolean get() = true

    override fun describe(densityOverride: Int): String {
        val dens = densityFor(densityOverride)
        val mbps = bitrate / 1_000_000.0
        val rate = if (bitrate % 1_000_000 == 0) "${bitrate / 1_000_000}M" else String.format(Locale.US, "%.1fM", mbps)
        return "custom ${outW}x$outH ← AA $aaResolution поля ${marginW}x$marginH ×${String.format(Locale.US, "%.3f", scale)} dpi $dens $rate"
    }

    companion object {
        const val MIN_W = 640
        const val MAX_W = 2240
        const val MIN_H = 640
        const val MAX_H = 1080
        const val DENSITY = 160
        const val BASE_BITRATE = 4_000_000
        const val MIN_BITRATE = 2_000_000
        const val MAX_BITRATE = 8_000_000
        private val PLATES = listOf(800 to 480, 1280 to 720, 1920 to 1080)
        private val MAX_PLATE = PLATES.last()

        /** Почему размер не годится; null = годится. */
        fun check(w: Int, h: Int): CustomResError? = when {
            w !in MIN_W..MAX_W -> CustomResError.WIDTH_RANGE
            h !in MIN_H..MAX_H -> CustomResError.HEIGHT_RANGE
            w % 2 != 0 || h % 2 != 0 -> CustomResError.ODD
            w < h -> CustomResError.PORTRAIT
            else -> null
        }

        /** Валидный размер -> режим, иначе null. */
        fun from(w: Int, h: Int): CustomVideoMode? = if (check(w, h) == null) CustomVideoMode(w, h) else null

        /** Итоговая геометрия из prefs: свой размер включён и валиден - он, иначе пресет. Невалидные сохранённые значения = откат на пресет. */
        fun resolve(enabled: Boolean, w: Int, h: Int, preset: VideoMode): VideoGeometry =
            (if (enabled) from(w, h) else null) ?: preset

        private fun plateFor(w: Int, h: Int): Pair<Int, Int> =
            PLATES.firstOrNull { w <= it.first && h <= it.second } ?: MAX_PLATE

        private fun even(v: Double): Int = (Math.round(v / 2.0) * 2).toInt()

        private fun areaFor(w: Int, h: Int, plate: Pair<Int, Int>): Pair<Int, Int> {
            if (w <= plate.first && h <= plate.second) return w to h
            // не влезает ни в одну подложку: аспект вписывается в 1920x1080
            val (pw, ph) = MAX_PLATE
            return if (w.toDouble() / h >= pw.toDouble() / ph) pw to even(pw.toDouble() * h / w)
            else even(ph.toDouble() * w / h) to ph
        }
    }
}

/**
 * Ручной DPI интерфейса AA (поле density в VideoConfiguration SDR). Абсолютное значение, а не процент от авто:
 * раскладку AA определяет ширина в dp = aaW * 160 / density, то есть именно абсолютная density.
 */
object AaDensity {
    /** Не переопределять: density режима. */
    const val AUTO = 0
    const val MIN = 100
    const val MAX = 280
    const val STEP = 20

    /** Допустимые ручные значения: 100, 120 .. 280. */
    val VALUES: List<Int> = (MIN..MAX step STEP).toList()

    /** Из prefs: вне сетки/диапазона = авто. */
    fun parse(v: Int): Int = if (v in VALUES) v else AUTO

    /**
     * Шаг «-»/«+» в UI. Из авто - на ближайшее значение сетки строго ниже/выше density режима
     * (у R1440 авто 320 выше максимума, поэтому «+» оттуда ничего не даёт, «-» ставит 280).
     * На краю сетки остаётся как было.
     */
    fun step(current: Int, auto: Int, up: Boolean): Int {
        val base = if (current == AUTO) auto else current
        return if (up) VALUES.firstOrNull { it > base } ?: current
        else VALUES.lastOrNull { it < base } ?: current
    }
}
