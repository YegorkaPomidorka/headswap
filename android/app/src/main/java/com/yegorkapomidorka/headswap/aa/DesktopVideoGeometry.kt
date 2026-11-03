// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.aa

/** Native Samsung desktop geometry. Unlike CustomVideoMode, it has no larger AA canvas. */
object DesktopVideoGeometry : VideoGeometry {
    const val WIDTH = 1600
    const val HEIGHT = 900

    override val outW = WIDTH
    override val outH = HEIGHT
    override val aaW = WIDTH
    override val aaH = HEIGHT
    // Shared geometry fields describe the direct input here; no AA SDR is sent for this source.
    override val aaResolution = "${WIDTH}x$HEIGHT"
    override val marginW = 0
    override val marginH = 0
    override val density = 160
    override val bitrate = DesktopVideoQuality.BITRATE_CEILING_BPS
    override val label = "DeX ${WIDTH}x$HEIGHT"

    override fun describe(densityOverride: Int): String =
        "DeX: Surface ${WIDTH}x$HEIGHT -> CarLife ${WIDTH}x$HEIGHT, dpi ${densityFor(densityOverride)}, без кропа и масштаба"

    /** Reject a larger canvas cropped/scaled to the desktop, including AA custom geometries. */
    fun matchesNative(mode: VideoGeometry): Boolean =
        (mode === this || mode is NativeDesktopGeometry) &&
            DesktopGeometryPolicy.accepts(mode.outW, mode.outH) && mode.aaW == mode.outW && mode.aaH == mode.outH &&
            mode.marginW == 0 && mode.marginH == 0 && mode.crop.isNone
}
