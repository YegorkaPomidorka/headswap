package com.yegorkapomidorka.headswap.aa

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Параметры [BitratePolicy] под транспорт. [USB] - прежнее поведение (кабель: старт с потолка, вниз до 0.8 слива,
 * вверх +10% раз в 10 с). [WIFI] - воздух (план Э3, 5.3): старт с 0.75 потолка, вниз не меньше чем ×0.7,
 * вверх +20% раз в 5 с после 10 с затишья, выше старта - только после 60 с без спадов.
 */
data class BitrateProfile(
    val name: String,
    /** Старт сессии: доля потолка. */
    val startFraction: Double,
    /** Шаг вниз не меньше: цель не выше current * downStep (1.0 = только по сливу). */
    val downStep: Double,
    /** Вниз и без измеренного слива (только шагом [downStep]). */
    val downWithoutDrain: Boolean,
    val upFactor: Double,
    /** Затишье после спада, прежде чем подниматься. */
    val upWaitMs: Long,
    /** Между ступенями подъёма. */
    val upIntervalMs: Long,
    /** Выше стартового битрейта - только после стольких мс без спадов (с начала сессии или последнего спада). */
    val aboveStartAfterMs: Long,
) {
    companion object {
        val USB = BitrateProfile("usb", 1.0, 1.0, false, 1.1, 10_000L, 10_000L, 0L)
        val WIFI = BitrateProfile("wifi", 0.75, 0.7, true, 1.2, 10_000L, 5_000L, 60_000L)
    }
}

/**
 * Целевой битрейт энкодера по состоянию видео-очереди CarLife: ГУ принимает видео с ограниченной скоростью
 * (Kia Seltos ~210 КиБ/с), энкодер на 6 Мбит/с забивает очередь и задержка растёт до секунд. Вниз - до 0.8 скорости слива,
 * вверх - ступенями после затишья (см. [BitrateProfile]). Чистая логика: время приходит параметром, кодек не трогает.
 * [update] зовётся с одной нити, [lowerCeiling] - с любой; [current] читается с любой.
 */
class BitratePolicy(ceilingBps: Int, floorBps: Int = FLOOR_BPS, val profile: BitrateProfile = BitrateProfile.USB) {
    val floorBps = min(floorBps, ceilingBps)

    /** Потолок, бит/с. Только вниз и на лету - [lowerCeiling] (ГУ с лимитом на размер кадра). */
    @Volatile var ceilingBps = ceilingBps
        private set

    /** Битрейт старта сессии: потолок (USB) или его доля (воздух). */
    val startBps = max(this.floorBps, (ceilingBps * profile.startFraction).toInt())

    /** Текущая цель, бит/с. */
    @Volatile var current = startBps
        private set

    /** Бэклог после сглаживания, мс (для лога и тестов). */
    var smoothedMs = 0.0
        private set

    /** Описание последнего изменения (для одной строки лога у вызывающего). */
    var lastReason = ""
        private set

    var downs = 0
        private set
    var ups = 0
        private set
    var minBps = startBps
        private set

    private var lastSampleMs = NEVER
    private var lastDownMs = NEVER
    private var calmSinceMs = NEVER
    private var upNotBeforeMs = NEVER
    private var cleanSinceMs = NEVER

    /**
     * Очередной замер: бэклог очереди (мс) и скорость слива (байт/с, 0 = ещё не измерена). Возвращает новый битрейт,
     * если он изменился, иначе null. Бэклог сглаживается EWMA по времени (tau 1 с), поэтому одиночный IDR на 160 КБ
     * не даёт отката, а результат не зависит от частоты вызовов.
     */
    @Synchronized
    fun update(nowMs: Long, backlogMs: Double, drainBytesPerSec: Double): Int? {
        smoothedMs = if (lastSampleMs == NEVER) backlogMs else {
            val dt = max(nowMs - lastSampleMs, 0L)
            smoothedMs + (1 - exp(-dt / TAU_MS)) * (backlogMs - smoothedMs)
        }
        lastSampleMs = nowMs
        if (cleanSinceMs == NEVER) cleanSinceMs = nowMs

        if (smoothedMs < UP_BACKLOG_MS) {
            if (calmSinceMs == NEVER) calmSinceMs = nowMs
        } else {
            calmSinceMs = NEVER
        }

        val drainOk = drainBytesPerSec > 0 || profile.downWithoutDrain
        if (smoothedMs > DOWN_BACKLOG_MS && drainOk && (lastDownMs == NEVER || nowMs - lastDownMs >= DOWN_INTERVAL_MS)) {
            var target = current
            if (drainBytesPerSec > 0) target = min(target, (drainBytesPerSec * 8 * DOWN_FACTOR).toInt())
            if (profile.downStep < 1.0) target = min(target, (current * profile.downStep).toInt())
            target = max(floorBps, target)
            if (target < current) {
                lastReason = "бэклог %.0f мс (сглаженный) > %d мс, слив %.0f КиБ/с".format(smoothedMs, DOWN_BACKLOG_MS, drainBytesPerSec / 1024)
                current = target
                lastDownMs = nowMs
                cleanSinceMs = nowMs
                calmSinceMs = NEVER
                upNotBeforeMs = nowMs + profile.upWaitMs
                downs++
                if (target < minBps) minBps = target
                return target
            }
        }

        if (calmSinceMs != NEVER && nowMs - calmSinceMs >= profile.upWaitMs && nowMs >= upNotBeforeMs && current < ceilingBps) {
            val cap = if (nowMs - cleanSinceMs >= profile.aboveStartAfterMs) ceilingBps else min(startBps, ceilingBps)
            if (current >= cap) return null
            val target = min(cap, max(current + 1, (current * profile.upFactor).toInt()))
            lastReason = "бэклог < %d мс уже %d с".format(UP_BACKLOG_MS, (nowMs - calmSinceMs) / 1000)
            current = target
            upNotBeforeMs = nowMs + profile.upIntervalMs
            ups++
            return target
        }
        return null
    }

    /**
     * Опустить потолок (не ниже [floorBps]); выше текущего он не поднимается. Если [current] выше нового потолка, он
     * срезается до него. true = потолок изменился (вызывающий сверяет [current] и применяет к энкодеру).
     */
    @Synchronized
    fun lowerCeiling(newCeilingBps: Int): Boolean {
        val c = max(floorBps, newCeilingBps)
        if (c >= ceilingBps) return false
        ceilingBps = c
        if (current > c) {
            current = c
            lastReason = "потолок снижен до %d кбит/с".format(c / 1000)
            if (c < minBps) minBps = c
        }
        return true
    }

    companion object {
        const val FLOOR_BPS = 800_000
        const val DOWN_BACKLOG_MS = 300L
        const val UP_BACKLOG_MS = 100L
        const val DOWN_INTERVAL_MS = 1000L
        const val UP_WAIT_MS = 10_000L
        const val DOWN_FACTOR = 0.8
        const val UP_FACTOR = 1.1
        private const val TAU_MS = 1000.0
        private const val NEVER = Long.MIN_VALUE
    }
}
