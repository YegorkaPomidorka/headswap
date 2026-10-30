package com.yegorkapomidorka.headswap.aa

/** Состояние звонка телефона (на устройстве - PhoneCalls поверх TelephonyManager; в JVM-тестах - заглушка). */
interface CallStateSource {
    /** Подписка: [onState] получает TelephonyManager.CALL_STATE_* (0 IDLE, 1 RINGING, 2 OFFHOOK). false = не вышло (нет разрешения). */
    fun start(onState: (Int) -> Unit): Boolean

    fun stop()

    object NONE : CallStateSource {
        override fun start(onState: (Int) -> Unit) = false
        override fun stop() = Unit
    }

    companion object {
        /** API, с которого TelephonyCallback требует READ_PHONE_STATE; ниже PhoneStateListener работает без разрешения. */
        const val PERMISSION_SDK = 31

        /** Слушать ли телефонию: флажок «Сообщать ГУ о звонках» включён и (на API 31+) выдан READ_PHONE_STATE. */
        fun shouldListen(enabled: Boolean, sdk: Int, permissionGranted: Boolean) =
            enabled && (sdk < PERMISSION_SDK || permissionGranted)

        const val IDLE = 0
        const val RINGING = 1
        const val OFFHOOK = 2
    }
}
