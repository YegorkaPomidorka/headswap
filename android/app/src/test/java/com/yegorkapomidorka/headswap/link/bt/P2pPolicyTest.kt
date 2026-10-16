package com.yegorkapomidorka.headswap.link.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pPolicyTest {
    @Test
    fun firstTargetStarts() {
        assertEquals(P2pOnTarget.START, P2pPolicy.onTarget(active = false, joined = false))
    }

    /** h126: попытка не вошла (BUSY), ГУ переподключила BT и прислала TARGET_INFO снова - начинаем заново, а не «уже вхожу». */
    @Test
    fun newTargetRestartsStuckAttempt() {
        assertEquals(P2pOnTarget.RESTART, P2pPolicy.onTarget(active = true, joined = false))
    }

    @Test
    fun newTargetSkippedWhenAlreadyInGroup() {
        assertEquals(P2pOnTarget.SKIP, P2pPolicy.onTarget(active = true, joined = true))
    }

    @Test
    fun busyRetriedUpToLimit() {
        for (n in 0 until P2pPolicy.BUSY_RETRIES) assertTrue(P2pPolicy.retryDiscover(busy = true, retriesDone = n))
        assertFalse(P2pPolicy.retryDiscover(busy = true, retriesDone = P2pPolicy.BUSY_RETRIES))
    }

    @Test
    fun otherFailuresNotRetried() {
        assertFalse(P2pPolicy.retryDiscover(busy = false, retriesDone = 0))
    }

    @Test
    fun retriesFitInJoinTimeout() {
        assertTrue(P2pPolicy.BUSY_RETRIES * P2pPolicy.BUSY_RETRY_MS < 30_000L)
    }
}
