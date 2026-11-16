package com.yegorkapomidorka.headswap.launcher

import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/**
 * Формат часов десктопа: 24 ч «14:05» или 12 ч «2:05» ([LauncherPrefs.clock12h]). AM/PM не пишем: рейка рассчитана
 * на пять моноширинных знаков, а «Средняя» режет время по двоеточию. Цифры латинские при любом языке (Locale.ROOT).
 */
object ClockFormat {
    private val h24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val h12 = DateTimeFormatter.ofPattern("h:mm", Locale.ROOT)

    fun format(t: TemporalAccessor, twelveHour: Boolean): String = (if (twelveHour) h12 else h24).format(t)

    /** Часы и минуты с полуночи, для времени прибытия навигатора. */
    fun format(hour: Int, minute: Int, twelveHour: Boolean): String =
        if (twelveHour) "%d:%02d".format(Locale.ROOT, (hour + 11) % 12 + 1, minute)
        else "%02d:%02d".format(Locale.ROOT, hour, minute)
}
