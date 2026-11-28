package com.yegorkapomidorka.headswap.link.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WlScreenLogicTest {
    private fun screen(
        mode: WlMode, stage: WlStage = WlStage.OFF, choosing: Boolean = false, device: Boolean = false,
        net: Boolean = false, bt: Boolean = false, hotspot: Boolean = false,
    ) = WlScreenLogic.screen(mode, choosing, stage, device, net, bt, hotspot)

    @Test
    fun chooseWhenNoModeOrChanging() {
        assertEquals(WlKind.CHOOSE, screen(WlMode.NONE).kind)
        assertEquals(WlKind.CHOOSE, screen(WlMode.P2P, choosing = true, device = true).kind)
    }

    @Test
    fun p2pFlow() {
        assertEquals(WlMain.PICK_CAR, screen(WlMode.P2P).main)
        val ready = screen(WlMode.P2P, device = true)
        assertEquals(WlKind.READY, ready.kind)
        assertEquals(WlMain.WAIT_WIFI, ready.main)
        val wait = screen(WlMode.P2P, WlStage.WAIT, device = true, bt = true)
        assertEquals(WlTitle.WAITING_CAR, wait.title)
        assertEquals(
            listOf(WlStep.BT_CAR to WlStepState.DONE, WlStep.WIFI_DIRECT to WlStepState.ACTIVE, WlStep.CAR_LIVE to WlStepState.WAIT),
            wait.steps,
        )
        assertTrue(wait.stop)
        val up = screen(WlMode.P2P, WlStage.UP, device = true)
        assertEquals(WlTitle.CONNECTED, up.title)
        assertEquals(WlDot.OK, up.dot)
        assertEquals(WlStepState.WAIT, up.steps[0].second)      // BT не отвечал: ступенька ждёт, сессия от этого не страдает
        assertEquals(WlStepState.DONE, up.steps[2].second)
    }

    @Test
    fun carWifiNeedsNetThenWaits() {
        assertEquals(WlMain.SAVE_NET, screen(WlMode.CAR_WIFI).main)
        assertEquals(WlKind.READY, screen(WlMode.CAR_WIFI, net = true).kind)
        val link = screen(WlMode.CAR_WIFI, WlStage.LINK, net = true)
        assertEquals(listOf(WlStep.JOIN_CAR_WIFI to WlStepState.DONE, WlStep.CAR_LIVE to WlStepState.WAIT), link.steps)
        assertTrue(link.deviceRow)
    }

    @Test
    fun hotspotShowsOffUntilSessionUp() {
        assertEquals(WlKind.SETUP, screen(WlMode.HOTSPOT).kind)
        assertEquals(WlMain.WAIT_WIFI, screen(WlMode.HOTSPOT).main)
        val off = screen(WlMode.HOTSPOT, WlStage.WAIT)
        assertEquals(WlTitle.HOTSPOT_OFF, off.title)
        assertEquals(WlDot.WARN, off.dot)
        assertEquals(WlStepState.ERROR, off.steps[0].second)
        assertEquals(WlMain.HOTSPOT_ON, off.main)
        assertTrue(off.stop)
        val on = screen(WlMode.HOTSPOT, WlStage.WAIT, hotspot = true)
        assertEquals(WlTitle.WAITING_CAR, on.title)
        assertEquals(WlStepState.ACTIVE, on.steps[1].second)
        assertFalse(on.deviceRow)
        // сессия идёт - точка по определению включена, даже если определить не вышло
        assertEquals(WlTitle.CONNECTED, screen(WlMode.HOTSPOT, WlStage.UP).title)
    }

    @Test
    fun boxFlow() {
        assertEquals(WlMain.PICK_BOX, screen(WlMode.BOX).main)
        val idle = screen(WlMode.BOX, device = true)
        assertEquals(WlKind.BOX_WAIT, idle.kind)
        assertFalse(idle.stop)
        assertEquals(WlMain.CONNECT_BOX, idle.main)      // без оглядки на Bluetooth: у бокса нет звуковых профилей
        assertEquals(WlMain.CONNECT_BOX, screen(WlMode.BOX, device = true, bt = true).main)
        val wait = screen(WlMode.BOX, WlStage.WAIT, device = true, bt = true)
        assertEquals(WlMain.NONE, wait.main)
        assertTrue(wait.stop)
        val link = screen(WlMode.BOX, WlStage.LINK, device = true)
        assertEquals(WlTitle.CONNECTING, link.title)
        assertEquals(WlStepState.ACTIVE, link.steps[2].second)
        assertEquals(WlTitle.CONNECTED, screen(WlMode.BOX, WlStage.UP, device = true).title)
        val stuck = screen(WlMode.BOX, WlStage.NO_LINK, device = true)
        assertEquals(WlTitle.NO_BOX_WIFI, stuck.title)
        assertEquals(WlStepState.ERROR, stuck.steps[1].second)
        assertEquals(WlMain.OPEN_WIFI, stuck.main)
        assertTrue(stuck.stop)
    }

    @Test
    fun btAskedOnlyWhereShown() {
        assertTrue(WlScreenLogic.needsBt(WlMode.P2P, true, WlStage.WAIT))
        assertFalse(WlScreenLogic.needsBt(WlMode.P2P, true, WlStage.OFF))
        assertFalse(WlScreenLogic.needsBt(WlMode.BOX, true, WlStage.OFF))
        assertFalse(WlScreenLogic.needsBt(WlMode.BOX, true, WlStage.WAIT))
        assertFalse(WlScreenLogic.needsBt(WlMode.HOTSPOT, true, WlStage.WAIT))
    }
}
