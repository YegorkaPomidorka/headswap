package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * Экспериментальная настройка «Канал подсказок навигации» (раздел для разработчиков, h1318): в какой CarLife-канал мост
 * шлёт речь AA (подсказки навигатора). TTS = как раньше (канал 4, MODULE_STATUS NAVI=1), ГУ крутит громкость ползунком
 * «музыка». VR = канал 5 без NAVI=1, как ответ помощника Baidu: ГУ может переключить кнопки громкости на «голос».
 * Мост читает значение на каждый пакет речи, новый канал берётся с началом следующей подсказки.
 */
enum class NavChannel(val titleRes: Int) {
    VR(R.string.navch_vr),
    TTS(R.string.navch_tts);

    companion object {
        /** В тестовой сборке h1318 по умолчанию VR. */
        val DEFAULT = VR

        /** По имени из prefs; неизвестное/пустое = по умолчанию. */
        fun parse(key: String?): NavChannel = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
