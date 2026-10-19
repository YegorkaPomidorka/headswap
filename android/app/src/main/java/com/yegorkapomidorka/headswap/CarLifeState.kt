package com.yegorkapomidorka.headswap

import com.yegorkapomidorka.headswap.aa.AaDensity
import com.yegorkapomidorka.headswap.aa.VideoGeometry

/** Состояние для экрана; пишут сервис и сессия, читает activity (один процесс). */
object CarLifeState {
    @Volatile var status: String = "не запущено"
    @Volatile var accessory: String = "-"
    @Volatile var protocol: String = "-"
    @Volatile var auth: String = "-"
    @Volatile var video: String = "-"
    @Volatile var touch: String = "-"

    /** Видео-режим текущей/последней сессии (фактический, с учётом passthrough); null - сессии не было. */
    @Volatile var videoMode: VideoGeometry? = null

    /** Ручной DPI AA той же сессии (AaDensity.AUTO = density режима). */
    @Volatile var densityOverride: Int = AaDensity.AUTO
    @Volatile var running: Boolean = false

    /** Текущая сессия идёт по Wi-Fi (TCP), а не по кабелю; false - сессии нет или она по USB. */
    @Volatile var wirelessSession: Boolean = false

    /** Беспровод (бета, ветка B): что сейчас происходит, для экрана; null - режим не включён. */
    @Volatile var wireless: String? = null

    /** Bluetooth-проба ветки A: что прислала ГУ по RFCOMM `carlife`; null - проба не запускалась. */
    @Volatile var btProbe: String? = null

    /** Авторежим беспровода: выбранная машина рядом по Bluetooth (пишет BridgeService). */
    @Volatile var carAuto: Boolean = false

    /** Запрос сети Wi-Fi машины: idle / requesting / available / unavailable (пишет BridgeService). */
    @Volatile var carNet: String = "idle"

    fun resetSession() {
        protocol = "-"
        auth = "-"
        video = "-"
        touch = "-"
    }

    fun summary(): String =
        "статус: $status\nаксессуар: $accessory\nпротокол: $protocol\nauth: $auth\nвидео: $video\nтач: $touch" +
            (wireless?.let { "\nбеспровод: $it" } ?: "")
}
