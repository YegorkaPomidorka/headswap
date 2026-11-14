package com.yegorkapomidorka.headswap.aa

/**
 * Интервал естественных IDR энкодера моста (KEY_I_FRAME_INTERVAL, секунды). ADR 0040.
 *
 * VW 20082120 по воздуху (4pda, лог 12.11.2026): в первые секунды Wi-Fi тянет 300-500 КиБ/с, а энкодер
 * шлёт IDR 130-175 КБ раз в секунду - размер почти не зависит от битрейта (175 КБ и на 806 кбит/с).
 * Бэклог доходит до 1 с, и ГУ закрывает сокет через 2-41 с. TCP не теряет кадры, поэтому длинный GOP
 * безопасен: внеочередные IDR и так идут по START/возобновлению, по запросу ГУ и по переполнению очереди.
 */
object AirIdrInterval {
    const val DEFAULT_S = 1
    const val VW_20082120_AIR_S = 10

    fun seconds(vw20082120: Boolean, air: Boolean): Int =
        if (vw20082120 && air) VW_20082120_AIR_S else DEFAULT_S
}
