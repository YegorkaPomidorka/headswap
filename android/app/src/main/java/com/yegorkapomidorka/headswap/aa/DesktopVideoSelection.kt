// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.aa

import kotlin.math.roundToInt

/** Limits apply only to a parent desktop; child map buffers have their own larger budget. */
object DesktopGeometryPolicy {
    const val MIN_WIDTH = 640
    const val MAX_WIDTH = 1920
    const val MIN_HEIGHT = 480
    const val MAX_HEIGHT = 1080
    const val MIN_DENSITY = 96
    const val MAX_DENSITY = 640
    /** Портретный рабочий стол (h921: Tucson NX4 HSAE с прошивкой на весь экран просит 960×1138). */
    const val PORTRAIT_MIN_WIDTH = 480
    const val PORTRAIT_MAX_WIDTH = 1080
    const val PORTRAIT_MAX_HEIGHT = 1920

    /** Горизонтальный размер. DeX и отдельные экраны пускают только его. */
    fun accepts(width: Int, height: Int): Boolean =
        width in MIN_WIDTH..MAX_WIDTH && height in MIN_HEIGHT..MAX_HEIGHT &&
            width >= height && width % 2 == 0 && height % 2 == 0

    fun acceptsPortrait(width: Int, height: Int): Boolean =
        width in PORTRAIT_MIN_WIDTH..PORTRAIT_MAX_WIDTH && height in (width + 2)..PORTRAIT_MAX_HEIGHT &&
            width % 2 == 0 && height % 2 == 0

    /** Свой рабочий стол на виртуальном дисплее: горизонталь или портрет. */
    fun acceptsNative(width: Int, height: Int): Boolean = accepts(width, height) || acceptsPortrait(width, height)

    fun requireDimensions(width: Int, height: Int) {
        require(accepts(width, height)) {
            "Нужен горизонтальный размер ${MIN_WIDTH}–${MAX_WIDTH} × ${MIN_HEIGHT}–${MAX_HEIGHT} с чётными сторонами"
        }
    }
    fun requireNativeDimensions(width: Int, height: Int) {
        require(acceptsNative(width, height)) {
            "Нужен размер ${MIN_WIDTH}–${MAX_WIDTH} × ${MIN_HEIGHT}–${MAX_HEIGHT} или вертикальный " +
                "${PORTRAIT_MIN_WIDTH}–${PORTRAIT_MAX_WIDTH} × до ${PORTRAIT_MAX_HEIGHT}, стороны чётные"
        }
    }
    fun requireDensity(density: Int) {
        require(density in MIN_DENSITY..MAX_DENSITY) { "Плотность рабочего стола должна быть от $MIN_DENSITY до $MAX_DENSITY DPI" }
    }
    /**
     * Авто: короткая сторона ~576 dp, не меньше 160 dpi. При 600+ dp Gboard считает экран планшетом и делит
     * клавиатуру пополам, её настройка «Разделённая клавиатура» на это не влияет (стенд 04.11: 1280×720 при 160 -
     * пополам, при 200, 213 и 240 - целая). 480 -> 160, 720 -> 200, 900 -> 250, 1080 -> 300. Наш лаунчер свёрстан
     * в пикселях и от плотности не зависит; крупнее становятся клавиатура, системные окна и чужие приложения.
     */
    fun density(height: Int): Int = (160.0 * height / 576).roundToInt().coerceIn(160, MAX_DENSITY)

    /** По короткой стороне: у портрета это ширина (960×1152 -> 267, как 960 по высоте). */
    fun density(width: Int, height: Int): Int = density(minOf(width, height))
}

enum class DesktopResolution(val title: String, val width: Int, val height: Int) {
    AUTO("Авто — как просит магнитола", 1280, 720),
    R1024_768("1024 × 768", 1024, 768),
    R1280_720("1280 × 720", 1280, 720),
    R1600_900("1600 × 900", 1600, 900),
    CUSTOM("Своё разрешение", 1600, 900);
    companion object {
        fun restore(value: String?): DesktopResolution = entries.firstOrNull { it.name == value } ?: R1600_900
    }
}

/** Raw Surface, encoded pixels and touch space have identical dimensions. */
data class NativeDesktopGeometry(override val outW: Int, override val outH: Int) : VideoGeometry {
    init { DesktopGeometryPolicy.requireNativeDimensions(outW, outH) }
    override val aaW = outW
    override val aaH = outH
    override val aaResolution = "${outW}x$outH"
    override val marginW = 0
    override val marginH = 0
    override val density = DesktopGeometryPolicy.density(outW, outH)
    override val bitrate = DesktopVideoQuality.BITRATE_CEILING_BPS
    override val label = "Desktop ${outW}x$outH"
    override fun describe(densityOverride: Int): String =
        "Desktop: Surface ${outW}x$outH -> CarLife ${outW}x$outH, dpi ${densityFor(densityOverride)}, без кропа и масштаба"
}

/** Immutable preference snapshot; first INIT (or START fallback) locks one transport session. */
class DesktopVideoSelection(val resolution: DesktopResolution, val densityOverride: Int,
    val customWidth: Int = 1600, val customHeight: Int = 900) {
    init {
        if (densityOverride != 0) DesktopGeometryPolicy.requireDensity(densityOverride)
        if (resolution == DesktopResolution.CUSTOM) DesktopGeometryPolicy.requireNativeDimensions(customWidth, customHeight)
    }
    private var selected: NativeDesktopGeometry? = null
    fun initialGeometry() = if (resolution == DesktopResolution.CUSTOM) NativeDesktopGeometry(customWidth, customHeight)
        else NativeDesktopGeometry(resolution.width, resolution.height)
    @Synchronized fun resolve(width: Int = 0, height: Int = 0): NativeDesktopGeometry = selected ?: run {
        val result = if (resolution == DesktopResolution.AUTO && DesktopGeometryPolicy.acceptsNative(width, height))
            NativeDesktopGeometry(width, height) else initialGeometry()
        selected = result
        result
    }
    fun densityFor(mode: VideoGeometry): Int = if (densityOverride == 0) mode.density else densityOverride

    companion object {
        /**
         * Размер дисплея рабочего стола из настройки «Видео» AA ([video] - то, что там показано, уже с учётом
         * перекодирования): тот же outW×outH. Размер вне пределов виртуального дисплея (2240×976 и т.п.) - Авто:
         * как просит магнитола, при невалидном INIT 1280×720.
         */
        fun forAaVideo(video: VideoGeometry, densityOverride: Int): DesktopVideoSelection =
            if (DesktopGeometryPolicy.acceptsNative(video.outW, video.outH))
                DesktopVideoSelection(DesktopResolution.CUSTOM, densityOverride, video.outW, video.outH)
            else DesktopVideoSelection(DesktopResolution.AUTO, densityOverride)
    }
}
