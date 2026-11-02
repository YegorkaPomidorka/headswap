// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

/** Desktop selection is independent from the transport and never changes the phone's HOME app. */
object DesktopModePolicy {
    /** Kept for protocol compatibility; the delegated (PC-activated) backend rejects it. */
    const val SAMSUNG_DEX = 0
    const val ANDROID_DESKTOP = 1
    /** Class name in our namespace; the applicationId may carry a suffix (.smoke), the package part is runtime. */
    const val ANDROID_HOME_CLASS = "com.yegorkapomidorka.headswap.launcher.LauncherActivity"

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
    /** Own DisplayGroup (needs ADD_TRUSTED_DISPLAY): the display does not sleep and lock together with the phone's screen. */
    const val VIRTUAL_DISPLAY_OWN_DISPLAY_GROUP = 1 shl 11
    /**
     * Keyguard never covers the display (needs ADD_ALWAYS_UNLOCKED_DISPLAY). DisplayManagerService drops the flag
     * without OWN_DISPLAY_GROUP and throws SecurityException without the permission, so both are requested only together
     * and only when [alwaysUnlocked] (the permission is held).
     */
    const val VIRTUAL_DISPLAY_ALWAYS_UNLOCKED = 1 shl 12

    fun displayFlags(mode: Int, sdkInt: Int, alwaysUnlocked: Boolean = false): Int {
        requireMode(mode)
        if (mode == SAMSUNG_DEX) return DexUserServicePolicy.VIRTUAL_DISPLAY_FLAGS
        // A tap is forwarded parent -> child. Independent focus prevents the parent's DOWN
        // from clearing the embedded app's pressed state before its UP arrives. Android 13
        // has no per-display flag; retain its existing behavior. Do not disable stealing top
        // focus: that separate flag prevents the child from showing its software keyboard.
        return ANDROID_DISPLAY_FLAGS or (if (sdkInt >= 34) VIRTUAL_DISPLAY_OWN_FOCUS else 0) or
            (if (alwaysUnlocked) VIRTUAL_DISPLAY_OWN_DISPLAY_GROUP or VIRTUAL_DISPLAY_ALWAYS_UNLOCKED else 0)
    }

    /** HOME and APP_SWITCH must never reach a primary-display global navigation handler. */
    fun consumesNavigationKey(mode: Int, keycode: Int): Boolean {
        requireMode(mode)
        return mode != SAMSUNG_DEX && (keycode == 3 || keycode == 187)
    }
}
