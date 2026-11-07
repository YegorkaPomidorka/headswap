package com.yegorkapomidorka.headswap.dex

/**
 * Шайба и кнопки ГУ в режиме рабочего стола. Свой лаунчер живёт в процессе приложения и забирает их напрямую,
 * без инжекта в дисплей (ADR 0024): поворот шайбы в Android аналога без мета-клавиш не имеет, а NAVI/MUSIC/HOME
 * означают действия лаунчера, а не клавиши. Нет приёмника (чужой лаунчер, активность не на экране) - старый путь.
 */
object DesktopKeys {
    interface Sink {
        /** Поворот шайбы: delta > 0 по часовой. false - не взял, вызывающий решает сам. Любой поток. */
        fun rotate(delta: Int): Boolean
        /** Android keycode от моста (AaBridge.HARD_KEY_TO_ANDROID). false - не взял. Любой поток. */
        fun key(keycode: Int): Boolean
        /** Кнопка голоса: панель Алисы в лаунчере. false - не взял (чужой лаунчер, активность не на экране). Любой поток. */
        fun voice(): Boolean
    }

    @Volatile var sink: Sink? = null

    /**
     * Нажатие клавиши в дисплей рабочего стола процессом активации (у shell на Xiaomi INJECT_EVENTS нет). Нужен Gemini:
     * KEYCODE_VOICE_ASSIST открывает помощника по умолчанию штатно, как долгое нажатие питания. null - сессии нет.
     */
    @Volatile var injectKey: ((Int) -> Unit)? = null

    /** Панель Алисы открылась (true) / закрылась: мост шлёт ГУ MODULE_STATUS VR. null - сессии нет. Любой поток. */
    @Volatile var voiceStatus: ((Boolean) -> Unit)? = null
}
