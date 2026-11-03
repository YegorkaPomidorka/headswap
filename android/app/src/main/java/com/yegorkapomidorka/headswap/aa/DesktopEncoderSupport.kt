// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.aa

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/** Codec declarations are checked before allocating a desktop producer; no geometry/FPS fallback. */
object DesktopEncoderSupport {
    private const val MIME = "video/avc"

    internal data class Candidate(val name: String, val supports: (Int, Int, Int, Int) -> Boolean)

    internal fun select(candidates: List<Candidate>, width: Int, height: Int, fps: Int, bitrate: Int): String {
        DesktopGeometryPolicy.requireDimensions(width, height)
        require(fps in 1..DesktopVideoQuality.MAX_FPS) { "Частота рабочего стола должна быть от 1 до 60 кадров/с" }
        require(bitrate > 0)
        return candidates.firstOrNull { runCatching { it.supports(width, height, fps, bitrate) }.getOrDefault(false) }?.name
            ?: throw IllegalArgumentException("Телефон не поддерживает кодирование AVC ${width}×$height при $fps кадрах/с. Выберите меньший размер или частоту")
    }

    fun requireSupported(width: Int, height: Int, fps: Int) {
        encoderName(width, height, fps, DesktopVideoQuality.BITRATE_CEILING_BPS)
    }

    internal fun encoderName(width: Int, height: Int, fps: Int, bitrate: Int): String = select(
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            it.isEncoder && it.supportedTypes.any { type -> type.equals(MIME, ignoreCase = true) }
        }.map { info -> Candidate(info.name) { w, h, rate, bps -> supports(info, w, h, rate, bps) } },
        width, height, fps, bitrate,
    )

    internal fun requireSupported(info: MediaCodecInfo, width: Int, height: Int, fps: Int, bitrate: Int) {
        select(listOf(Candidate(info.name) { w, h, rate, bps -> supports(info, w, h, rate, bps) }),
            width, height, fps, bitrate)
    }

    private fun supports(info: MediaCodecInfo, width: Int, height: Int, fps: Int, bitrate: Int): Boolean {
        val capabilities = info.getCapabilitiesForType(MIME)
        if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in capabilities.colorFormats) return false
        val video = capabilities.videoCapabilities ?: return false
        return video.areSizeAndRateSupported(width, height, fps.toDouble()) && video.bitrateRange.contains(bitrate)
    }
}
