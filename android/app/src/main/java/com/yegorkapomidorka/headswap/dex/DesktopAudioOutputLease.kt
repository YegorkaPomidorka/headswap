// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

/** One transport session. Only a completed physical write renews capture eligibility. */
internal class DesktopAudioOutputLease(private val startedAtMs: Long) {
    private var completedAtMs: Long? = null
    private var closed = false

    @Synchronized fun writeCompleted(nowMs: Long) {
        if (!closed && nowMs >= startedAtMs && nowMs >= (completedAtMs ?: startedAtMs))
            completedAtMs = nowMs
    }

    @Synchronized fun canCapture(nowMs: Long): Boolean {
        if (closed) return false
        // The initial grace is anchored to the session, never to a capture/reconcile attempt.
        val anchor = completedAtMs ?: startedAtMs
        return nowMs >= anchor && nowMs - anchor <= FRESHNESS_MS
    }

    @Synchronized fun close() { closed = true }

    companion object { const val FRESHNESS_MS = 3_000L }
}
