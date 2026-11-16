package com.yegorkapomidorka.headswap.link.car

/**
 * VW 20082120 по воздуху (4pda, лог 16.11.2026; ADR 0041): Bluetooth к ГУ при подключении мигает (ACL connected ->
 * disconnected за 0,3-0,6 с), авторежим через 10 с считает, что машина ушла, и после EOF от ГУ отпускает сеть машины.
 * ГУ гасит первую сессию через ~9 с и переподключилась бы сама, но её уже никто не ждёт. Для этой ГУ отбой по
 * Bluetooth не гасит ожидание по воздуху: оно заканчивается по своему таймауту (600 с) или по «Стоп».
 */
object AirAutoOffQuirk {
    const val VW_20082120_CHANNEL = "20082120"

    /** Новое значение «последняя ГУ по воздуху - VW 20082120» после сессии. Без STATISTIC_INFO или по кабелю - не меняем. */
    fun remember(prev: Boolean, air: Boolean, channel: String?): Boolean =
        if (!air || channel == null) prev else channel == VW_20082120_CHANNEL

    /** Отбой по Bluetooth пропускаем: ждём по воздуху, запущены авторежимом, и последняя ГУ по воздуху - VW 20082120. */
    fun ignoreBtAutoOff(lastAirHuVw20082120: Boolean, wifiBMode: Boolean, autoStarted: Boolean): Boolean =
        lastAirHuVw20082120 && wifiBMode && autoStarted
}
