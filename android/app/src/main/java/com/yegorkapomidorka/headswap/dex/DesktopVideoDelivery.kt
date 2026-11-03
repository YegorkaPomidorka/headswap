// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import com.yegorkapomidorka.headswap.Ch
import com.yegorkapomidorka.headswap.Msg

/** One transport session's completed video writes. No initial grace or enqueue-based success. */
internal class DesktopVideoDelivery {
    private var startedAt: Long? = null
    private var completedAt: Long? = null
    private var lastVideoTraffic: Long? = null
    private var closed = false

    @Synchronized fun start(nowMs: Long) {
        if (closed) return
        startedAt = nowMs
        completedAt = null // A repeated START/resume needs a newly completed video write.
        lastVideoTraffic = null
    }
    @Synchronized fun pause() { startedAt = null; completedAt = null; lastVideoTraffic = null }
    @Synchronized fun close() { closed = true; pause() }
    @Synchronized fun writeCompleted(channel: Int, type: Int, payloadBytes: Int, nowMs: Long) {
        val start = startedAt ?: return
        if (closed || channel != Ch.VIDEO || nowMs < (lastVideoTraffic ?: start)) return
        if (type == Msg.VIDEO_DATA && payloadBytes > 0) {
            completedAt = nowMs
            lastVideoTraffic = nowMs
        } else if (type == Msg.VIDEO_HEARTBEAT && completedAt != null) {
            // Static desktops stop generating new frames. A completed VIDEO write keeps only
            // an already proven, unpaused session alive; a heartbeat can never establish one.
            lastVideoTraffic = nowMs
        }
    }
    @Synchronized fun hasPicture(): Boolean = !closed && startedAt != null && completedAt != null
    @Synchronized fun isFlowing(nowMs: Long): Boolean {
        val write = completedAt ?: return false
        return !closed && startedAt != null && nowMs >= write && nowMs - write <= FRESHNESS_MS
    }
    @Synchronized fun isAliveForIdleTimer(nowMs: Long): Boolean {
        val traffic = lastVideoTraffic ?: return false
        return !closed && completedAt != null && nowMs >= traffic && nowMs - traffic <= FRESHNESS_MS
    }
    companion object { const val FRESHNESS_MS = 3_000L }
}

/** Publication uses session identity; an old close cannot clear a replacement session. */
internal object DesktopConnectionState {
    private var current: DesktopVideoDelivery? = null
    @Synchronized fun attach(value: DesktopVideoDelivery) { current = value }
    @Synchronized fun detach(value: DesktopVideoDelivery) { if (current === value) current = null }
    @Synchronized fun videoIsFlowing(nowMs: Long): Boolean = current?.isFlowing(nowMs) == true
    @Synchronized fun aliveForIdleTimer(nowMs: Long): Boolean = current?.isAliveForIdleTimer(nowMs) == true
}
