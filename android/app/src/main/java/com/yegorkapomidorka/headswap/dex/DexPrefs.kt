// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.content.Context
import com.yegorkapomidorka.headswap.aa.DesktopGeometryPolicy
import com.yegorkapomidorka.headswap.aa.FpsSetting

/** Desktop preferences are separate from the AA / bridge settings. */
class DexPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("headswap_dex", Context.MODE_PRIVATE)
    private val aaPrefs = context.getSharedPreferences("aa", Context.MODE_PRIVATE)
    /**
     * Звук рабочего стола по USB/Wi-Fi (ADR 0025): тот же переключатель «Звук через Headswap», что в режиме AA
     * (ключ usbAudio в prefs "aa"). Выключен - звук по Bluetooth. Старый ключ desktop_usb_playback_audio не читаем.
     */
    var usbPlaybackAudio: Boolean
        get() = aaPrefs.getBoolean("usbAudio", true)
        set(value) { aaPrefs.edit().putBoolean("usbAudio", value).apply() }
    /** Only Android Desktop is available here; any other stored value falls back to it. */
    var desktopMode: Int
        get() = prefs.getInt("desktop_mode", DesktopModePolicy.ANDROID_DESKTOP).let {
            if (it == DesktopModePolicy.ANDROID_DESKTOP) it else DesktopModePolicy.ANDROID_DESKTOP
        }
        set(value) {
            DesktopModePolicy.requireMode(value)
            prefs.edit().putInt("desktop_mode", value).apply()
        }
    /** Zero selects proportional DPI (DesktopGeometryPolicy.density: клавиатура не делится пополам). */
    var desktopDensity: Int
        get() = prefs.getInt("desktop_density", 0).let {
            if (it == 0) 0 else it.coerceIn(DesktopGeometryPolicy.MIN_DENSITY, DesktopGeometryPolicy.MAX_DENSITY)
        }
        set(value) { if (value != 0) DesktopGeometryPolicy.requireDensity(value); prefs.edit().putInt("desktop_density", value).apply() }
    /** Сторонний лаунчер «пакет/класс» в дочернем окне над нашей панелью навигации (LauncherActivity всегда на ГУ); null - Headswap. */
    var desktopComponent: String?
        get() = prefs.getString("desktop_component", null)?.takeIf(DesktopPick::isComponent)
        set(value) { prefs.edit().putString("desktop_component", value?.takeIf(DesktopPick::isComponent)).apply() }
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
    /** Сервис моста сейчас работает в режиме Headswap Desktop: уведомление показывает статус рабочего стола, а не AA. */
    @Volatile var desktopSession: Boolean = false
}
