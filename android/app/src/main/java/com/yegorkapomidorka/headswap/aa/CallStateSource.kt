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
        const val IDLE = 0
        const val RINGING = 1
        const val OFFHOOK = 2
    }
}
