package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * Настройка «Частота музыки»: Авто (48 кГц, для ГУ Hyundai CN7 - 32 кГц) или фиксированная для любой ГУ.
 * Мост фиксирует частоту в момент MEDIA INIT, поэтому смена действует со следующего INIT (подключение или новый трек).
 */
enum class MusicRate(val titleRes: Int, val hz: Int?) {
    AUTO(R.string.mrate_auto, null),
    HZ48(R.string.mrate_48, 48000),
    HZ44(R.string.mrate_44, 44100),
    HZ32(R.string.mrate_32, 32000),
    HZ24(R.string.mrate_24, 24000),
    HZ22(R.string.mrate_22, 22050);

    companion object {
        val DEFAULT = AUTO

        /** По имени из prefs; неизвестное/пустое = по умолчанию. */
        fun parse(key: String?): MusicRate = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
