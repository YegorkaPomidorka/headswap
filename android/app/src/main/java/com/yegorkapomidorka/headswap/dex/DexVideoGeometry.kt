// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import com.yegorkapomidorka.headswap.aa.IdrSplicer
import com.yegorkapomidorka.headswap.aa.SpsCrop
import java.io.IOException

/** Verify the encoded source as well as scrcpy's header: neither may hide a 1080p stream. */
internal class DexVideoGeometry {
    private var sessionSeen = false
    private var spsVerified = false

    fun acceptSession(width: Int, height: Int) {
        if (width != WIDTH || height != HEIGHT) {
            throw IOException("DeX передаёт ${width}×${height}; требуется нативный экран ${WIDTH}×${HEIGHT}")
        }
        sessionSeen = true
        spsVerified = false
    }

    /** PPS-only config packets are allowed, but no frame is accepted before a valid SPS. */
    fun acceptConfig(annexB: ByteArray): List<SpsCrop.Info> {
        if (!sessionSeen) throw IOException("scrcpy: конфигурация до описания экрана")
        val infos = try {
            IdrSplicer.nals(annexB).filter { it[0].toInt() and 0x1f == 7 }.map(SpsCrop::parse)
        } catch (e: Exception) {
            throw IOException("Не удалось проверить размер H.264 DeX: повреждён SPS", e)
        }
        for (info in infos) {
            if (info.visibleW != WIDTH || info.visibleH != HEIGHT) {
                throw IOException("H.264 DeX содержит ${info.visibleW}×${info.visibleH}; требуется ${WIDTH}×${HEIGHT}")
            }
        }
        if (infos.isNotEmpty()) spsVerified = true
        return infos
    }

    /** Some encoders put their first SPS in the IDR. Parse only until geometry is verified. */
    fun beforeFrame(annexB: ByteArray): List<SpsCrop.Info> {
        val infos = if (!spsVerified) acceptConfig(annexB) else emptyList()
        if (!spsVerified) throw IOException("Первый кадр DeX пришёл без SPS: размер изображения не подтверждён")
        return infos
    }

    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        // The phone decodes and re-encodes this stream. Keep the first generation clean;
        // CarLife's separate 4 Mbps ceiling and congestion adaptation remain unchanged.
        const val SOURCE_BITRATE = 12_000_000
    }
}
