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

    /** Левая рейка лаунчера для свайпа по экрану ГУ ([com.yegorkapomidorka.headswap.aa.RailSwipe], ADR 0062). */
    interface Rail {
        /** true - рейка скрыта, false - видна, null - прятать нечего (чужой лаунчер, активность не на экране, портрет). Любой поток. */
        fun hidden(): Boolean?
        /** Скрыть или вернуть рейку. Любой поток. */
        fun setHidden(hide: Boolean)
    }

    @Volatile var rail: Rail? = null

    /**
     * Нажатие клавиши в дисплей рабочего стола процессом активации (у shell на Xiaomi INJECT_EVENTS нет). Нужен Gemini:
     * KEYCODE_VOICE_ASSIST открывает помощника по умолчанию штатно, как долгое нажатие питания. null - сессии нет.
     */
    @Volatile var injectKey: ((Int) -> Unit)? = null

    /**
     * Кнопки «−»/«+» громкости нижней рейки портретного лаунчера: тот же шаг STREAM_MUSIC, что у жеста двумя пальцами
     * ([DesktopMediaControl.adjustVolume]). steps: +1 громче, -1 тише. null - сессии нет. Любой поток.
     */
    @Volatile var adjustVolume: ((Int) -> Unit)? = null

    /** Панель Алисы открылась (true) / закрылась: мост шлёт ГУ MODULE_STATUS VR. null - сессии нет. Любой поток. */
    @Volatile var voiceStatus: ((Boolean) -> Unit)? = null
}
