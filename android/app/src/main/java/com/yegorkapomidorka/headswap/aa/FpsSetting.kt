package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * Настройка «Частота кадров»: Авто (как просит ГУ в VIDEO_ENCODER_INIT) или фиксированная; по умолчанию Авто.
 * Читается один раз на подключение (SessionConfig), в passthrough не действует.
 */
enum class FpsSetting(val titleRes: Int, val subRes: Int, val fixed: Int?) {
    AUTO(R.string.fps_auto_title, R.string.fps_auto_sub, null),
    FPS15(R.string.fps_15_title, R.string.fps_15_sub, 15),
    FPS30(R.string.fps_30_title, R.string.fps_30_sub, 30),
    FPS60(R.string.fps_60_title, R.string.fps_60_sub, 60);

    companion object {
        val DEFAULT = AUTO

        /** По имени из prefs; неизвестное/пустое = по умолчанию. */
        fun parse(key: String?): FpsSetting = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}

/**
 * Гейт кадров перед энкодером: по таймстемпам решает, отдавать ли кадр. Энкодер с Surface-входом кодирует
 * каждый swap, поэтому KEY_FRAME_RATE частоту не ограничивает - её режет только этот гейт.
 * По умолчанию цель >= 30 (или <= 0) пропускает всё; прямой Surface рабочего стола задаёт limitHighFps=true.
 * Для AA: вход AA быстрее 30 не бывает, а допуск по таймстемпам на 30/30 терял бы кадры
 * от джиттера входа. Ниже 30 - сетка слотов: слот каждые period, кадр берётся за полпериода до слота и позже.
 * Вызывается с одной нити (GL-реле); targetFps можно менять с любой.
 */
class FrameGate(initialFps: Int, private val limitHighFps: Boolean = false) {
    @Volatile var targetFps: Int = initialFps
    private var nextNs = NONE

    /** true = кадр отдать, false = пропустить. */
    fun shouldEmit(nowNs: Long): Boolean {
        val fps = targetFps
        if (fps <= 0 || (!limitHighFps && fps >= PASS_ALL_FPS)) {
            nextNs = NONE           // при переключении на меньшую цель сетка начнётся заново
            return true
        }
        val period = 1_000_000_000L / fps
        if (nextNs == NONE) {
            nextNs = nowNs + period
            return true
        }
        // High-refresh desktop input needs a narrow rounding tolerance, not AA's
        // half-period jitter allowance (which can emit adjacent fast frames).
        val tolerance = if (limitHighFps) minOf(1_000_000L, period / 2) else period / 2
        if (nowNs < nextNs - tolerance) return false
        nextNs += period
        if (nowNs - nextNs > period) nextNs = nowNs + period     // отстали больше чем на период (вход медленнее цели): сетка заново
        return true
    }

    /** Период целевой частоты в нс (0 = без ограничения). */
    fun periodNs(): Long = targetFps.let { if (it <= 0) 0L else 1_000_000_000L / it }

    /** Новая AA-сессия: первый кадр отдаётся сразу. */
    fun reset() {
        nextNs = NONE
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val PASS_ALL_FPS = 30
    }
}
