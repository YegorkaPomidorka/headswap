package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * Настройка «Подсказки навигатора» («Звук и микрофон», h1318, ADR 0071), три режима.
 * VR = речь AA в канал 5 без NAVI=1, как ответ помощника Baidu: музыка останавливается и для навигатора, и для Алисы.
 * TTS = канал 4, MODULE_STATUS NAVI=1: ГУ приглушает музыку под навигатор, для Алисы останавливает.
 * MIX = на рабочем столе голос навигатора идёт вместе с музыкой в MEDIA, без отдельного nav-микса (как до 4.3.4);
 * в проекции AA ведёт себя как TTS. Мост читает значение на каждый пакет речи, MIX на рабочем столе — при старте захвата звука.
 */
enum class NavChannel(val titleRes: Int, val subRes: Int) {
    VR(R.string.navch_vr, R.string.navch_vr_sub),
    TTS(R.string.navch_tts, R.string.navch_tts_sub),
    MIX(R.string.navch_mix, R.string.navch_mix_sub);

    /** Открывать ли на рабочем столе отдельный микс голоса навигатора (DesktopAudioLoopback.withNav). */
    val separateNavMix: Boolean get() = this != MIX

    companion object {
        /** TTS: на HSAE (h1455) и CN7 (h1452) VR-поток ставит музыку на паузу или рвёт её, ADR 0066. VR и MIX — вручную. */
        val DEFAULT = TTS

        /** По имени из prefs; неизвестное/пустое = по умолчанию. */
        fun parse(key: String?): NavChannel = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
