// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopAudioOutputLeaseTest {
    @Test fun initialGraceExpiresWithoutCompletedWrites() {
        val lease = DesktopAudioOutputLease(100)
        assertTrue(lease.canCapture(100))
        assertTrue(lease.canCapture(3_100))
        assertFalse(lease.canCapture(3_101))
        assertFalse(lease.canCapture(90_000))
    }

    @Test fun queuedAudioOrRepeatedCaptureChecksCannotRenewGrace() {
        val lease = DesktopAudioOutputLease(0)
        // Enqueuing is deliberately not a renewal API. Polling/restarting capture cannot renew it.
        repeat(100) { assertTrue(lease.canCapture(it * 20L)) }
        for (time in 3_001L..20_000L step 250) assertFalse(lease.canCapture(time))
    }

    @Test fun actualWriteResumesAndRenewalExpiresAgain() {
        val lease = DesktopAudioOutputLease(0)
        assertFalse(lease.canCapture(4_000))
        lease.writeCompleted(4_000)
        assertTrue(lease.canCapture(4_000))
        assertTrue(lease.canCapture(7_000))
        assertFalse(lease.canCapture(7_001))
        lease.writeCompleted(7_002)
        assertTrue(lease.canCapture(7_002))
    }

    @Test fun silentPacketsRemainEligibleWhenActuallyWritten() {
        val lease = DesktopAudioOutputLease(0)
        for (time in 0L..60_000L step 20) {
            lease.writeCompleted(time)
            assertTrue(lease.canCapture(time + 250))
        }
        assertFalse(lease.canCapture(63_001))
    }

    @Test fun closedOldSessionCannotRenewOrAffectSuccessor() {
        val old = DesktopAudioOutputLease(0)
        old.writeCompleted(2_000)
        old.close()
        val current = DesktopAudioOutputLease(3_000)
        old.writeCompleted(7_000) // late callback from the retired USB writer
        assertFalse(old.canCapture(7_000))
        assertFalse(current.canCapture(7_000))
        current.writeCompleted(7_001)
        assertTrue(current.canCapture(7_001))
        assertFalse(old.canCapture(7_001))
    }

    @Test fun InvalidOrReorderedClockValuesDoNotExtendEligibility() {
        val lease = DesktopAudioOutputLease(100)
        assertFalse(lease.canCapture(99))
        lease.writeCompleted(50)
        lease.writeCompleted(200)
        lease.writeCompleted(150)
        assertTrue(lease.canCapture(3_200))
        assertFalse(lease.canCapture(3_201))
        assertFalse(lease.canCapture(199))
    }
}
