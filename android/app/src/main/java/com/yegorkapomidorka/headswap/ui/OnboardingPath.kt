package com.yegorkapomidorka.headswap.ui

import com.yegorkapomidorka.headswap.aa.BridgeMode

/** Какие шаги онбординга идут для выбранного режима и в каком порядке. Без Android: проверяется тестами. */
object OnboardingPath {
    /** Android Auto: прежние десять шагов. */
    val AA = listOf(
        StepId.AA_DEV, StepId.AA_UNKNOWN, StepId.AUTOSTART, StepId.NOTIF, StepId.BACKGROUND,
        StepId.MIC, StepId.BLUETOOTH, StepId.LOCATION, StepId.FIRST, StepId.CAR,
    )

    /** Свой лаунчер: режим разработчика и активация, дальше общие шаги без сервера ГУ Android Auto. */
    val LAUNCHER = listOf(
        StepId.DEVMODE, StepId.ACTIVATE, StepId.NOTIF, StepId.BACKGROUND,
        StepId.MIC, StepId.BLUETOOTH, StepId.LOCATION, StepId.FIRST, StepId.CAR,
    )

    /** Шаги, которые бывают только на пути лаунчера. */
    private val LAUNCHER_ONLY = setOf(StepId.DEVMODE, StepId.ACTIVATE)

    /** Путь целиком; выбор режима первым, если режим «Свой лаунчер» вообще доступен (Android 13+). */
    fun full(mode: BridgeMode, desktopSupported: Boolean): List<StepId> =
        (if (desktopSupported) listOf(StepId.MODE) else emptyList()) + (if (mode == BridgeMode.DESKTOP) LAUNCHER else AA)

    /** Отладка (--es step devmode|activate): шаг лаунчера открывается и при сохранённом Android Auto, сами настройки не трогаем. */
    fun modeFor(stored: BridgeMode, startAt: StepId?): BridgeMode =
        if (startAt in LAUNCHER_ONLY) BridgeMode.DESKTOP else stored

    /**
     * Очередь показа: при первом прохождении выполненные шаги пропускаются (режим разработчика уже включён, активация уже есть,
     * выбор режима уже сделан), при повторном показываются все. [keep] остаётся в очереди, даже если выполнен.
     */
    fun queue(mode: BridgeMode, desktopSupported: Boolean, showAll: Boolean, isDone: (StepId) -> Boolean, keep: StepId? = null): List<StepId> =
        full(mode, desktopSupported).filter { showAll || it == keep || !isDone(it) }

    /** «Шаг n из N» для шага [id] в списке [ids]; нет в списке - первый. */
    fun progress(ids: List<StepId>, id: StepId): Pair<Int, Int> = (ids.indexOf(id) + 1).coerceAtLeast(1) to ids.size
}
