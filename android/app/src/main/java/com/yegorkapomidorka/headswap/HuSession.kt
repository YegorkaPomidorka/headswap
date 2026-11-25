package com.yegorkapomidorka.headswap

/** Сессия с ГУ глазами BridgeService: протокол (CarLife, ICCOA) за ней не виден. */
interface HuSession {
    /** Транспорт сессии для логов и выбора «кабель/воздух» (TransportCaps.kind). */
    val transportKind: String
    fun start()
    fun close(reason: String)
    /** Отдать экран штатному меню ГУ (долгое «Домой» в лаунчере). */
    fun goToDesktop()
    fun hasFreshDesktopVideo(): Boolean
    fun canCaptureDesktopAudio(): Boolean
}
