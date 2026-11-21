// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.aa

/** Quality target for the native desktop Surface source, independent of legacy AA modes. */
object DesktopVideoQuality {
    // CarLife's VIDEO_ENCODER_INIT carries width, height and frameRate, not a 4 Mbit/s cap.
    // This remains a target: the existing HU, thermal and transport policies may lower it.
    const val BITRATE_CEILING_BPS = 8_000_000

    /** Desktop default for 720p-class screens: 8 Mbit/s on Wi-Fi does not survive. */
    const val DEFAULT_BITRATE_BPS = 6_000_000
    const val MAX_FPS = 60

    /**
     * Desktop AUTO follows the HU request. With [cap30] (CR-V 2026 preset) it stops at 30 fps:
     * 60 fps caused the same brightness pumping as 8 Mbit/s. FPS60 stays available explicitly.
     */
    fun chooseFps(setting: FpsSetting, requested: Int, huMaxFps: Int? = null, cap30: Boolean = false): Int {
        val max = if (cap30) 30 else MAX_FPS
        val selected = setting.fixed ?: requested.takeIf { it in 1..max } ?: 30
        return huMaxFps?.let { minOf(selected, it) } ?: selected
    }

    fun frameRateChangeAllowed(fps: Int): Boolean = fps in 3..MAX_FPS

    /**
     * Desktop encoder policy. Off the CR-V preset this is the generic 8 Mbit/s policy.
     * With [crv] the ceiling comes from the selected video mode (WIDE_HD = 6 Mbit/s) and Wi-Fi
     * starts at the ceiling and never climbs above it: the 6 -> 8 Mbit/s step after 60 s was
     * the flicker trigger (CR-V 2026 Hybrid). Down steps are softer than the generic WIFI profile.
     */
    fun bitratePolicy(profile: BitrateProfile, ceilingBps: Int = DEFAULT_BITRATE_BPS, crv: Boolean = false): BitratePolicy {
        if (!crv) return BitratePolicy(BITRATE_CEILING_BPS, profile = profile)
        val ceiling = ceilingBps.coerceIn(1, BITRATE_CEILING_BPS)
        val desktopProfile = if (profile == BitrateProfile.WIFI) profile.copy(
            startFraction = 1.0,
            downStep = 0.85,
            upWaitMs = 30_000L,
            aboveStartAfterMs = Long.MAX_VALUE,
        ) else profile
        return BitratePolicy(ceiling, profile = desktopProfile)
    }
}
