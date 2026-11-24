package com.yegorkapomidorka.headswap.launcher

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Новогодний режим плитки «Часы» (ADR 0063): сезонный, от набора цветов не зависит.
 * 31.12 с 23:00:00 - отсчёт до полуночи; 01.01 00:00 .. 05.01 23:59 - поздравление над часами.
 */
sealed interface NewYearMode {
    data object None : NewYearMode
    /** До Нового года [remainingSec] секунд (1..3600). */
    data class Countdown(val remainingSec: Long) : NewYearMode {
        /** «ММ:СС» оставшегося времени (в первую секунду окна - «60:00»). */
        val text: String get() = "%02d:%02d".format(remainingSec / 60, remainingSec % 60)
        /** Доля пройденного последнего часа 0..1. */
        val progress: Float get() = (1f - remainingSec / 3600f).coerceIn(0f, 1f)
    }
    /** Поздравление с [year]-м годом. */
    data class Greeting(val year: Int) : NewYearMode

    companion object {
        const val COUNTDOWN_FROM_HOUR = 23
        const val GREETING_LAST_DAY = 5

        fun at(t: LocalDateTime): NewYearMode {
            if (t.monthValue == 12 && t.dayOfMonth == 31 && t.hour >= COUNTDOWN_FROM_HOUR) {
                val midnight = t.toLocalDate().plusDays(1).atStartOfDay()
                return Countdown(Duration.between(t.withNano(0), midnight).seconds)
            }
            if (t.monthValue == 1 && t.dayOfMonth in 1..GREETING_LAST_DAY) return Greeting(t.year)
            return None
        }

        /** Через сколько мс перерисовать плитку: в отсчёте - к следующей секунде, иначе к следующей минуте (границы окон на них). */
        fun nextTickMs(t: LocalDateTime): Long {
            val ms = t.nano / 1_000_000L
            return if (at(t) is Countdown) 1000L - ms else (60L - t.second) * 1000L - ms
        }

        /** «ЧЧ:ММ:СС» под отсчётом; [twelveHour] - как в «12-часовой формат». */
        fun timeWithSeconds(t: LocalTime, twelveHour: Boolean): String {
            val h = if (twelveHour) (t.hour % 12).let { if (it == 0) 12 else it } else t.hour
            return if (twelveHour) "%d:%02d:%02d".format(h, t.minute, t.second) else "%02d:%02d:%02d".format(h, t.minute, t.second)
        }
    }
}

/** Часы плитки: системные, со сдвигом для проверки на стенде ([shiftMs] ставит только [LauncherDebug]). */
object NewYearClock {
    @Volatile var shiftMs = 0L
    fun now(): LocalDateTime = LocalDateTime.now().plusNanos(shiftMs * 1_000_000L)
}

/** Искры вокруг поздравления: детерминированные точки в долях плитки, без центра, где стоит текст. */
object NewYearSparks {
    data class Spark(val x: Float, val y: Float, val phase: Float, val size: Float, val white: Boolean)

    const val COUNT = 20

    fun layout(count: Int = COUNT): List<Spark> {
        var seed = 12345L
        fun next(): Float { seed = (seed * 1103515245L + 12345L) and 0x7fffffffL; return (seed % 10000L) / 10000f }
        val out = ArrayList<Spark>(count)
        while (out.size < count) {
            val x = next(); val y = next(); val phase = next(); val size = 0.6f + 0.8f * next(); val white = next() < 0.4f
            if (kotlin.math.abs(x - 0.5f) < 0.3f && kotlin.math.abs(y - 0.5f) < 0.2f) continue
            out += Spark(x, y, phase, size, white)
        }
        return out
    }

    /** Мерцание искры: 0..1 по кругу фазы, острый пик раз в цикл (редкие вспышки). */
    fun twinkle(phase: Float, offset: Float): Float {
        val u = ((phase + offset) % 1f + 1f) % 1f
        return if (u < 0.25f) kotlin.math.sin(u / 0.25f * Math.PI.toFloat()) else 0f
    }
}
