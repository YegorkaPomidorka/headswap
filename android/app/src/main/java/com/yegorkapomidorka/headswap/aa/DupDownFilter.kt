package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * Настройка «Двойное касание от ГУ» (раздел для разработчиков): отбрасывать ли повторный DOWN в той же точке без UP.
 * Honda (h725, h864) шлёт DOWN, DOWN, UP с разрывом 1–70 мс. Авто = любая ГУ, но только повтор не позже
 * [AUTO_WINDOW_MS]: у ГУ, теряющей UP, следующий тап в ту же точку приходит позже и проходит.
 * Всегда = без окна, Выключен = не трогать. Сессия читает значение на каждое событие.
 */
enum class DupDownFilter(val titleRes: Int) {
    AUTO(R.string.dup_down_auto),
    ON(R.string.dup_down_on),
    OFF(R.string.dup_down_off);

    companion object {
        val DEFAULT = AUTO
        const val AUTO_WINDOW_MS = 150L

        /** По имени из prefs; неизвестное/пустое = по умолчанию. */
        fun parse(key: String?): DupDownFilter = values().firstOrNull { it.name == key } ?: DEFAULT

        /** Отбросить ли DOWN: [sameHeld] — прошлый DOWN той же точки ещё не отпущен, [sinceDownMs] — сколько прошло. */
        fun drop(mode: DupDownFilter, sameHeld: Boolean, sinceDownMs: Long): Boolean = sameHeld && when (mode) {
            ON -> true
            OFF -> false
            AUTO -> sinceDownMs in 0..AUTO_WINDOW_MS
        }
    }
}
