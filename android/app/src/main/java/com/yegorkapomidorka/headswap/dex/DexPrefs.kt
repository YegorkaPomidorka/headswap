// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.content.Context
import com.yegorkapomidorka.headswap.aa.DesktopResolution
import com.yegorkapomidorka.headswap.aa.DesktopGeometryPolicy
import com.yegorkapomidorka.headswap.aa.DesktopVideoSelection
import com.yegorkapomidorka.headswap.aa.FpsSetting

/** Desktop preferences are separate from the AA / bridge settings. */
class DexPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("headswap_dex", Context.MODE_PRIVATE)
    /** First activation offers USB playback. Changing the selection takes effect on the next activation. */
    var usbPlaybackAudio: Boolean
        get() = prefs.getBoolean("desktop_usb_playback_audio", true)
        set(value) { prefs.edit().putBoolean("desktop_usb_playback_audio", value).apply() }
    /** Only Android Desktop is available here; any other stored value falls back to it. */
    var desktopMode: Int
        get() = prefs.getInt("desktop_mode", DesktopModePolicy.ANDROID_DESKTOP).let {
            if (it == DesktopModePolicy.ANDROID_DESKTOP) it else DesktopModePolicy.ANDROID_DESKTOP
        }
        set(value) {
            DesktopModePolicy.requireMode(value)
            prefs.edit().putInt("desktop_mode", value).apply()
        }
    var desktopResolution: DesktopResolution
        get() = DesktopResolution.restore(prefs.getString("desktop_resolution", null))
        set(value) { prefs.edit().putString("desktop_resolution", value.name).apply() }
    private fun customDesktopSize(): Pair<Int, Int> {
        val parts = prefs.getString("desktop_custom_size", null)?.split('x')
        val width = parts?.takeIf { it.size == 2 }?.get(0)?.toIntOrNull()
        val height = parts?.takeIf { it.size == 2 }?.get(1)?.toIntOrNull()
        return if (width != null && height != null && DesktopGeometryPolicy.accepts(width, height)) width to height
            else 1600 to 900
    }
    val customDesktopWidth: Int get() = customDesktopSize().first
    val customDesktopHeight: Int get() = customDesktopSize().second
    /** One preference value keeps width and height atomic; invalid input changes nothing. */
    fun setCustomDesktopSize(width: Int, height: Int) {
        DesktopGeometryPolicy.requireDimensions(width, height)
        prefs.edit().putString("desktop_custom_size", "${width}x$height")
            .putString("desktop_resolution", DesktopResolution.CUSTOM.name).apply()
    }
    val desktopResolutionLabel: String get() = if (desktopResolution == DesktopResolution.CUSTOM)
        customDesktopSize().let { "${it.first} × ${it.second}" } else desktopResolution.title
    fun desktopVideoSelection(): DesktopVideoSelection = customDesktopSize().let {
        DesktopVideoSelection(desktopResolution, desktopDensity, it.first, it.second)
    }
    /** Zero selects proportional DPI. */
    var desktopDensity: Int
        get() = prefs.getInt("desktop_density", 160).let {
            if (it == 0) 0 else it.coerceIn(DesktopGeometryPolicy.MIN_DENSITY, DesktopGeometryPolicy.MAX_DENSITY)
        }
        set(value) { if (value != 0) DesktopGeometryPolicy.requireDensity(value); prefs.edit().putInt("desktop_density", value).apply() }
    /** Direct desktop has an independent 30 fps default; the AA preferences are unchanged. */
    var desktopFps: FpsSetting
        get() = prefs.getString("desktop_fps", null).let { name -> FpsSetting.entries.firstOrNull { it.name == name } ?: FpsSetting.FPS30 }
        set(value) { prefs.edit().putString("desktop_fps", value.name).apply() }
}

object DexState {
    @Volatile var status: String = "Рабочий стол не запущен"
    @Volatile var error: String? = null
    @Volatile var running: Boolean = false
    @Volatile var displayId: Int = -1
}
