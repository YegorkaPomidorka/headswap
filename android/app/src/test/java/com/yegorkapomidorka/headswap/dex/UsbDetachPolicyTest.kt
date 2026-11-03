// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.*
import org.junit.Test

class UsbDetachPolicyTest {
    @Test fun onlyConfirmedPhysicalAbsenceOfOwnedUsbMarksDetached() {
        assertTrue(UsbDetachPolicy.confirmedDetached(true, false))
        assertFalse("IO/protocol error with accessory present keeps reconnect grace", UsbDetachPolicy.confirmedDetached(true, true))
        assertFalse("failed UsbManager query is not proof of detach", UsbDetachPolicy.confirmedDetached(true, null))
        assertFalse("unrelated USB broadcast cannot stop TCP", UsbDetachPolicy.confirmedDetached(false, false))
        assertFalse(UsbDetachPolicy.confirmedDetached(false, null))
    }

    @Test fun staleQueuedDetachDoesNotOverrideAlreadyReconnectedAccessory() {
        assertFalse(UsbDetachPolicy.confirmedDetached(ownedUsbSession = true, accessoryPresent = true))
    }
}
