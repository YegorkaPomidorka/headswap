// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.aa

/** Quality target for the native desktop Surface source, independent of legacy AA modes. */
object DesktopVideoQuality {
    // CarLife's VIDEO_ENCODER_INIT carries width, height and frameRate, not a 4 Mbit/s cap.
    // This remains a target: the existing HU, thermal and transport policies may lower it.
    const val BITRATE_CEILING_BPS = 8_000_000
    const val MAX_FPS = 60

    /** Desktop AUTO accepts an actual 60 Hz HU request; legacy Android Auto keeps its own policy. */
    fun chooseFps(setting: FpsSetting, requested: Int, huMaxFps: Int? = null): Int {
        val selected = setting.fixed ?: requested.takeIf { it in 1..MAX_FPS } ?: 30
        return huMaxFps?.let { minOf(selected, it) } ?: selected
    }

    fun frameRateChangeAllowed(fps: Int): Boolean = fps in 3..MAX_FPS

    fun bitratePolicy(profile: BitrateProfile) = BitratePolicy(BITRATE_CEILING_BPS, profile = profile)
}
