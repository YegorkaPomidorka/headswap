package com.yegorkapomidorka.headswap.link.bt

import org.junit.Assert.assertEquals
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
}
