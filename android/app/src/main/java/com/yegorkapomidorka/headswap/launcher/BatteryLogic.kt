package com.yegorkapomidorka.headswap.launcher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.roundToInt

/** Заряд телефона для строки статуса: [percent] 0..100, -1 - неизвестен; [charging] - стоит на зарядке (или полон на ней). */
data class BatteryInfo(val percent: Int = -1, val charging: Boolean = false) {
    companion object { val UNKNOWN = BatteryInfo() }
}

/** Разбор липкого ACTION_BATTERY_CHANGED без обращения к системе: проверяется юнит-тестом. */
internal object BatteryLogic {
    /** Процент из EXTRA_LEVEL/EXTRA_SCALE; нет данных (-1, scale 0) - -1. */
    fun percent(level: Int, scale: Int): Int =
        if (level < 0 || scale <= 0) -1 else (level * 100f / scale).roundToInt().coerceIn(0, 100)

    /** Заряжается: статус «заряжается», либо «полон», пока воткнут (plugged != 0). Разряд и «не заряжается» - нет. */
    fun charging(status: Int, plugged: Int): Boolean =
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            (status == BatteryManager.BATTERY_STATUS_FULL && plugged != 0)

    /** Доля залитой части значка 0..1 по шагам в 1/8 (внутри значка восемь делений); неизвестный - пустой. */
    fun fill(percent: Int): Float {
        if (percent <= 0) return 0f
        return (percent.coerceAtMost(100) * 8 / 100f).roundToInt().coerceAtLeast(1) / 8f
    }

    fun parse(i: Intent?): BatteryInfo {
        if (i == null) return BatteryInfo.UNKNOWN
        return BatteryInfo(
            percent(i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)),
            charging(i.getIntExtra(BatteryManager.EXTRA_STATUS, -1), i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)),
        )
    }

    /** Липкий интент читается без приёмника (receiver = null): ничего не регистрируется и не утекает. */
    fun read(ctx: Context): BatteryInfo =
        runCatching { parse(ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))) }
            .getOrDefault(BatteryInfo.UNKNOWN)
}
