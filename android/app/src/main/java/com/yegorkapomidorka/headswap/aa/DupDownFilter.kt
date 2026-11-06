package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * Настройка «Двойное касание от ГУ» (раздел для разработчиков): отбрасывать ли повторный DOWN в той же точке без UP.
 * Авто = только Honda (h725, h864), Всегда/Выключен действуют на любой ГУ. Сессия читает значение на каждое событие.
 */
enum class DupDownFilter(val titleRes: Int) {
    AUTO(R.string.dup_down_auto),
    ON(R.string.dup_down_on),
    OFF(R.string.dup_down_off);

    companion object {
        val DEFAULT = AUTO

        /** По имени из prefs; неизвестное/пустое = по умолчанию. */
        fun parse(key: String?): DupDownFilter = values().firstOrNull { it.name == key } ?: DEFAULT

        /** Включён ли фильтр при выбранном режиме; [huHonda] — ГУ определилась как Honda. */
        fun active(mode: DupDownFilter, huHonda: Boolean): Boolean = when (mode) {
            ON -> true
            OFF -> false
            AUTO -> huHonda
        }
    }
}
