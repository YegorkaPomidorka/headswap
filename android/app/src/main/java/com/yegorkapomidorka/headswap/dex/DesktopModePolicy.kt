// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

/** Desktop selection is independent from the transport and never changes the phone's HOME app. */
object DesktopModePolicy {
    /** Kept for protocol compatibility; the delegated (PC-activated) backend rejects it. */
    const val SAMSUNG_DEX = 0
    const val ANDROID_DESKTOP = 1
    /** Class name in our namespace; the applicationId may carry a suffix (.smoke), the package part is runtime. */
    const val ANDROID_HOME_CLASS = "com.yegorkapomidorka.headswap.desktop.AndroidDesktopActivity"

    fun requireMode(mode: Int) {
        require(mode == SAMSUNG_DEX || mode == ANDROID_DESKTOP) { "Неизвестный режим рабочего стола" }
    }

    fun label(mode: Int): String {
        requireMode(mode)
        return if (mode == SAMSUNG_DEX) "Samsung DeX" else "Android Desktop"
    }

    /** PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | TRUSTED | DESTROY_CONTENT. */
    const val ANDROID_DISPLAY_FLAGS = 1 or 2 or 8 or 64 or 1024 or 256
    private const val VIRTUAL_DISPLAY_OWN_FOCUS = 1 shl 14

    fun displayFlags(mode: Int, sdkInt: Int): Int {
        requireMode(mode)
        if (mode == SAMSUNG_DEX) return DexUserServicePolicy.VIRTUAL_DISPLAY_FLAGS
        // A tap is forwarded parent -> child. Independent focus prevents the parent's DOWN
        // from clearing the embedded app's pressed state before its UP arrives. Android 13
        // has no per-display flag; retain its existing behavior. Do not disable stealing top
        // focus: that separate flag prevents the child from showing its software keyboard.
        return ANDROID_DISPLAY_FLAGS or (if (sdkInt >= 34) VIRTUAL_DISPLAY_OWN_FOCUS else 0)
    }

    /** HOME and APP_SWITCH must never reach a primary-display global navigation handler. */
    fun consumesNavigationKey(mode: Int, keycode: Int): Boolean {
        requireMode(mode)
        return mode != SAMSUNG_DEX && (keycode == 3 || keycode == 187)
    }
}
