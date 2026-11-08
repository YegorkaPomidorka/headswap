// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

/** Main-looper retry budget for one CarLife connection, independent of Activity recreation. */
internal class DesktopRecoveryPolicy {
    data class Attempt internal constructor(val token: Long, val number: Int, val delayMs: Long)
    private var serial = 0L
    private var attempts = 0
    private var pending: Attempt? = null

    /** Only a new transport session resets the budget; a recovered frame does not. */
    fun newConnection() {
        cancel()
        attempts = 0
        hostDeaths.clear()
    }

    fun begin(eligible: Boolean): Attempt? {
        if (!eligible || pending != null || attempts >= 2) return null
        attempts++
        return Attempt(++serial, attempts, if (attempts == 1) 1_000 else 3_000).also { pending = it }
    }

    private val hostDeaths = ArrayDeque<Long>()

    /**
     * h768: смерть shell-хоста VirtualDevice. Samsung рвёт его на каждом включении и гашении экрана без кабеля, за поездку
     * это десятки раз, поэтому бюджет свой и скользящий: [HOST_MAX] попыток за [HOST_WINDOW_MS], общий не тратится.
     * Пауза [HOST_DELAY_MS] покрывает перезапуск хоста (1,7–2,8 с в логе h768), дальше создание экрана само ждёт хост.
     */
    fun beginAfterHostDeath(eligible: Boolean, nowMs: Long): Attempt? {
        if (!eligible || pending != null) return null
        while (hostDeaths.isNotEmpty() && nowMs - hostDeaths.first() > HOST_WINDOW_MS) hostDeaths.removeFirst()
        if (hostDeaths.size >= HOST_MAX) return null
        hostDeaths.addLast(nowMs)
        return Attempt(++serial, hostDeaths.size, HOST_DELAY_MS).also { pending = it }
    }

    fun isCurrent(attempt: Attempt): Boolean = pending === attempt

    fun consume(attempt: Attempt): Boolean {
        if (!isCurrent(attempt)) return false
        pending = null
        return true
    }

    /** Stop/detach cancels a queued callback without manufacturing another retry budget. */
    fun cancel() { pending = null; serial++ }

    companion object {
        const val HOST_MAX = 5
        const val HOST_WINDOW_MS = 2 * 60_000L
        const val HOST_DELAY_MS = 2_000L
    }
}
