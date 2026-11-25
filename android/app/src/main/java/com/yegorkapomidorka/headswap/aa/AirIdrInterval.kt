package com.yegorkapomidorka.headswap.aa

/**
 * Интервал естественных IDR энкодера моста (KEY_I_FRAME_INTERVAL, секунды). ADR 0040.
 *
 * VW 20082120 и CNS3 20082130 по воздуху (4pda, лог 12.11.2026): в первые секунды Wi-Fi тянет 300-500 КиБ/с, а энкодер
 * шлёт IDR 130-175 КБ раз в секунду - размер почти не зависит от битрейта (175 КБ и на 806 кбит/с).
 * Бэклог доходит до 1 с, и ГУ закрывает сокет через 2-41 с. TCP не теряет кадры, поэтому длинный GOP
 * безопасен: внеочередные IDR и так идут по START/возобновлению, по запросу ГУ и по переполнению очереди.
 */
object AirIdrInterval {
    const val DEFAULT_S = 1
    const val VW_AIR_S = 10

    fun seconds(vwAirLongGop: Boolean, air: Boolean): Int =
        if (vwAirLongGop && air) VW_AIR_S else DEFAULT_S
}
