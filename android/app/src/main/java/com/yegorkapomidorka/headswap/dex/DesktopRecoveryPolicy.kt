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
    }

    fun begin(eligible: Boolean): Attempt? {
        if (!eligible || pending != null || attempts >= 2) return null
        attempts++
        return Attempt(++serial, attempts, if (attempts == 1) 1_000 else 3_000).also { pending = it }
    }

    fun isCurrent(attempt: Attempt): Boolean = pending === attempt

    fun consume(attempt: Attempt): Boolean {
        if (!isCurrent(attempt)) return false
        pending = null
        return true
    }

    /** Stop/detach cancels a queued callback without manufacturing another retry budget. */
    fun cancel() { pending = null; serial++ }
}
