package com.yegorkapomidorka.headswap.link.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotStateTest {
    private fun ifaces(vararg names: String) = names.filter { HotspotState.isHotspotIface(it) }

    @Test
    fun hotspotInterfaceNames() {
        for (n in listOf("swlan0", "ap0", "ap1", "wlan1", "wlan2", "wlan10")) assertTrue(n, HotspotState.isHotspotIface(n))
        for (n in listOf("wlan0", "p2p-wlan0-0", "p2p0", "rmnet_data1", "rmnet0", "tun0", "lo", "eth0", "wlan", "dummy0")) assertFalse(n, HotspotState.isHotspotIface(n))
    }

    @Test
    fun broadcastEnabledMeansUpEvenWithoutInterface() {
        assertTrue(HotspotState.decide(HotspotState.AP_ENABLED, emptyList()))
        assertTrue(HotspotState.decide(13, ifaces("wlan0")))
    }

    @Test
    fun broadcastDisabledMeansDownEvenWithStaleInterface() {
        assertFalse(HotspotState.decide(HotspotState.AP_DISABLED, ifaces("wlan2")))
        assertFalse(HotspotState.decide(11, ifaces("swlan0")))
    }

    @Test
    fun transitionalAndFailedStatesAreNotUpYet() {
        assertFalse(HotspotState.decide(HotspotState.AP_ENABLING, ifaces("wlan2")))
        assertFalse(HotspotState.decide(HotspotState.AP_DISABLING, ifaces("wlan2")))
        assertFalse(HotspotState.decide(HotspotState.AP_FAILED, ifaces("wlan2")))
    }

    @Test
    fun noBroadcastFallsBackToInterfaces() {
        assertTrue(HotspotState.decide(null, ifaces("wlan0", "wlan2")))
        assertTrue(HotspotState.decide(null, ifaces("swlan0")))
        assertFalse(HotspotState.decide(null, ifaces("wlan0", "p2p-wlan0-0", "rmnet_data1", "tun0")))
        assertFalse(HotspotState.decide(null, emptyList()))
        assertEquals(true, HotspotState.decide(99, ifaces("ap0")))      // чужое число = как нет рассылки
    }
}
