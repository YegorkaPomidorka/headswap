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

    /** h211: после распада группы при живом BT просим TARGET_INFO сами - сначала быстро, потом раз в 10 с, потом хватит. */
    @Test
    fun rejoinBackoff() {
        assertEquals(2_000L, P2pPolicy.rejoinDelayMs(0))
        assertEquals(5_000L, P2pPolicy.rejoinDelayMs(1))
        assertEquals(10_000L, P2pPolicy.rejoinDelayMs(2))
        assertEquals(10_000L, P2pPolicy.rejoinDelayMs(P2pPolicy.REJOIN_ATTEMPTS - 1))
        assertEquals(null, P2pPolicy.rejoinDelayMs(P2pPolicy.REJOIN_ATTEMPTS))
    }
}
