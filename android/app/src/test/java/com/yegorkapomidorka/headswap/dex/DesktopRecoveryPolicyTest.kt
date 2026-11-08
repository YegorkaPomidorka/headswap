// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopRecoveryPolicyTest {
    @Test fun twoHelperDeathsCanRecoverButRepeatedSuccessDoesNotRenewTheBudget() {
        val policy = DesktopRecoveryPolicy()
        policy.newConnection()
        val first = checkNotNull(policy.begin(eligible = true))
        assertEquals(1, first.number)
        assertEquals(1_000L, first.delayMs)
        assertNull("duplicate failure while cleanup is pending", policy.begin(eligible = true))
        assertTrue(policy.consume(first))
        // First replacement starts and renders frames; the same USB connection has only one retry left.
        val second = checkNotNull(policy.begin(eligible = true))
        assertEquals(2, second.number)
        assertEquals(3_000L, second.delayMs)
        assertTrue(second.token > first.token)
        assertTrue(policy.consume(second))
        repeat(10) { assertNull(policy.begin(eligible = true)) }
    }

    @Test fun stopOrDetachInvalidatesDelayedCallbackWithoutResettingItsBudget() {
        val policy = DesktopRecoveryPolicy()
        val old = checkNotNull(policy.begin(eligible = true))
        policy.cancel()
        assertFalse(policy.isCurrent(old))
        assertFalse(policy.consume(old))
        assertNull(policy.begin(eligible = false)) // User Stop / locked / missing authorization.
        val remaining = checkNotNull(policy.begin(eligible = true))
        assertEquals(2, remaining.number)
        assertFalse("old callback cannot consume its successor", policy.consume(old))
        assertTrue(policy.isCurrent(remaining))
    }

    @Test fun onlyANewConnectionRearmsRetryAndOldCallbackCannotStealItsTicket() {
        val policy = DesktopRecoveryPolicy()
        val old = checkNotNull(policy.begin(eligible = true))
        policy.newConnection()
        val current = checkNotNull(policy.begin(eligible = true))
        assertEquals(1, current.number)
        assertFalse(policy.consume(old))
        assertTrue(policy.consume(current))
        assertFalse("same scheduled callback may run at most once", policy.consume(current))
    }

    @Test fun hostDeathHasOwnSlidingBudgetAndDoesNotSpendHelperRetries() {
        val policy = DesktopRecoveryPolicy()
        policy.newConnection()
        var now = 1_000_000L
        repeat(DesktopRecoveryPolicy.HOST_MAX) { i ->
            val a = checkNotNull(policy.beginAfterHostDeath(eligible = true, nowMs = now))
            assertEquals(i + 1, a.number)
            assertEquals(DesktopRecoveryPolicy.HOST_DELAY_MS, a.delayMs)
            assertNull("one pending attempt at a time", policy.beginAfterHostDeath(eligible = true, nowMs = now))
            assertTrue(policy.consume(a))
            now += 10_000
        }
        assertNull("limit within the window", policy.beginAfterHostDeath(eligible = true, nowMs = now))
        // helper-death budget is untouched
        assertEquals(1, checkNotNull(policy.begin(eligible = true)).number)
        policy.cancel()
        // window slides: the oldest death falls out after HOST_WINDOW_MS
        val later = 1_000_000L + DesktopRecoveryPolicy.HOST_WINDOW_MS + 1
        assertNotNull(policy.beginAfterHostDeath(eligible = true, nowMs = later))
    }

    @Test fun hostDeathIneligibleDoesNotSpendBudgetAndNewConnectionResetsIt() {
        val policy = DesktopRecoveryPolicy()
        repeat(20) { assertNull(policy.beginAfterHostDeath(eligible = false, nowMs = 0)) }
        repeat(DesktopRecoveryPolicy.HOST_MAX) { policy.consume(checkNotNull(policy.beginAfterHostDeath(true, 0))) }
        assertNull(policy.beginAfterHostDeath(true, 0))
        policy.newConnection()
        assertEquals(1, checkNotNull(policy.beginAfterHostDeath(true, 0)).number)
    }

    @Test fun UnavailableServerOrNonRecoverableErrorNeverSchedulesOrSpendsRetry() {
        val policy = DesktopRecoveryPolicy()
        repeat(20) { assertNull(policy.begin(eligible = false)) }
        assertEquals(1, checkNotNull(policy.begin(eligible = true)).number)
    }
}
