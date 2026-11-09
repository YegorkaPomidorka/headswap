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
        val completed = completedAtMs
        // nowMs is sampled before taking the lock; the USB writer may complete a write in between.
        // A write newer than the caller's clock is fresh, not invalid (#190: capture dropped every ~15 min).
        if (completed != null && nowMs < completed) return true
        val anchor = completed ?: startedAtMs
        return nowMs >= anchor && nowMs - anchor <= FRESHNESS_MS
    }

    @Synchronized fun close() { closed = true }

    companion object { const val FRESHNESS_MS = 3_000L }
}
