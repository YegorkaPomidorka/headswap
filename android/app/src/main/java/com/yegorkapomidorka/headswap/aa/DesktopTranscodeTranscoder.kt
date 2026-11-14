package com.yegorkapomidorka.headswap.aa

import android.graphics.Bitmap
import android.view.Surface

/**
 * Desktop path that mirrors the AA pipeline: VirtualDisplay -> intermediate H.264 encoder
 * -> intermediate decoder -> GL -> final CarLife encoder.
 *
 * The intermediate stream never leaves the phone. It is deliberately kept at a high bitrate
 * so the final encoder sees a decoder-produced Surface, like in the AA (transcode) path.
 */
class DesktopTranscodeTranscoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val out: TranscodeOutput,
    private val log: (String) -> Unit = {},
    private val capabilityFps: Int = fps,
    private val baidu: Boolean = false,
    private val limitedRange: Boolean = false,
    private val pacer30: Boolean = false,
) : VideoTranscoder {
    private val finalTranscoder = AaTranscoder(
        width, height, fps, bitrate, out, log,
        srcWidth = width, srcHeight = height, crop = VideoCrop.NONE,
        baidu = baidu, directSurface = false,
        capabilityFps = capabilityFps, dumpTag = "desktop-final",
        limitedRange = limitedRange,
        pacer30 = pacer30,
    )

    private lateinit var intermediateTranscoder: AaTranscoder

    private val intermediateOut = object : TranscodeOutput {
        override fun onEncoderConfig(csd: ByteArray) {
            finalTranscoder.feedConfig(csd)
        }

        override fun onEncoderFrame(data: ByteArray, key: Boolean) {
            finalTranscoder.feedFrame(data, 0L)
        }

        override fun onEncoderFrame(data: ByteArray, key: Boolean, ptsUs: Long) {
            finalTranscoder.feedFrame(data, ptsUs)
        }

        override fun onTranscodeError(msg: String) {
            out.onTranscodeError("промежуточный: $msg")
        }

        override fun onNeedAaKeyFrame() {
            if (::intermediateTranscoder.isInitialized) intermediateTranscoder.requestSyncFrame()
        }
    }

    @Volatile private var running = false

    init {
        intermediateTranscoder = AaTranscoder(
            width, height, fps, INTERMEDIATE_BITRATE, intermediateOut, log,
            srcWidth = width, srcHeight = height, crop = VideoCrop.NONE,
            baidu = false, directSurface = true,
            capabilityFps = capabilityFps, dumpTag = "desktop-mid",
            pacer30 = pacer30,
        )
    }

    @Synchronized
    fun start() {
        if (running) return
        finalTranscoder.start()
        try {
            intermediateTranscoder.start()
        } catch (e: Exception) {
            finalTranscoder.stop()
            throw e
        }
        running = true
        log(
            "desktop transcode: промежуточный ${intermediateTranscoder.encoderName} " +
                "${width}x$height ${fps}fps ${INTERMEDIATE_BITRATE / 1000}kbps -> " +
                "финальный ${finalTranscoder.encoderName} ${bitrate / 1000}kbps"
        )
    }

    override val surfaceInput: SurfaceVideoInput?
        @Synchronized get() {
            if (!running) return null
            val input = intermediateTranscoder.surfaceInput ?: return null
            return object : SurfaceVideoInput {
                override val surface: Surface = input.surface
            }
        }

    override fun feedConfig(annexB: ByteArray) {}

    override fun feedFrame(annexB: ByteArray, ptsUs: Long) {}

    override fun resetDecoder() {}

    override fun requestSyncFrame() {
        finalTranscoder.requestSyncFrame()
    }

    override fun startIntro(banner: Bitmap) {
        intermediateTranscoder.startIntro(banner)
    }

    override val targetFps: Int get() = finalTranscoder.targetFps

    override fun setTargetFps(fps: Int) {
        finalTranscoder.setTargetFps(fps)
        intermediateTranscoder.setTargetFps(fps)
    }

    override val currentBitrate: Int get() = finalTranscoder.currentBitrate

    override fun setBitrate(bps: Int) {
        finalTranscoder.setBitrate(bps)
    }

    override fun statsLine(): String =
        "desktop transcode: ${finalTranscoder.statsLine()}; " +
            "промежуточный: ${intermediateTranscoder.statsLine()}"

    @Synchronized
    override fun stop() {
        if (!running) {
            intermediateTranscoder.stop()
            finalTranscoder.stop()
            return
        }
        running = false
        intermediateTranscoder.stop()
        finalTranscoder.stop()
    }

    companion object {
        /** Internal only: high enough to keep the second encode free of visible generation loss. */
        const val INTERMEDIATE_BITRATE = 8_000_000
    }
}
