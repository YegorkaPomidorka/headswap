package com.yegorkapomidorka.headswap.aa

/**
 * Режим моста: что ГУ получает по CarLife. ANDROID_AUTO - мост к серверу ГУ Android Auto (по умолчанию),
 * DESKTOP - свой рабочий стол на виртуальном экране телефона (нужна активация с ПК, Android 13+).
 * Режим снимается на подключение (хост и сессия), а не читается на лету.
 */
enum class BridgeMode(val key: String) {
    ANDROID_AUTO("aa"),
    DESKTOP("desktop");

    companion object {
        val DEFAULT = ANDROID_AUTO

        /** Trusted-дисплеи и права активации есть с Android 13; ниже режим DESKTOP недоступен. */
        const val DESKTOP_MIN_SDK = 33

        /** По ключу из prefs; неизвестное/пустое = по умолчанию. */
        fun parse(key: String?): BridgeMode = values().firstOrNull { it.key == key } ?: DEFAULT

        fun desktopSupported(sdk: Int) = sdk >= DESKTOP_MIN_SDK

        /** Режим, который реально применяется: DESKTOP на Android ниже 13 трактуется как Android Auto. */
        fun effective(stored: BridgeMode, sdk: Int): BridgeMode =
            if (stored == DESKTOP && !desktopSupported(sdk)) ANDROID_AUTO else stored

        /** Живой хост (grace после обрыва USB) годится новой сессии только того же режима. */
        fun hostReusable(hostMode: BridgeMode, wanted: BridgeMode) = hostMode == wanted
    }
}
