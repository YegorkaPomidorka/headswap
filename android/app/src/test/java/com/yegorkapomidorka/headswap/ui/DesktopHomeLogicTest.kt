package com.yegorkapomidorka.headswap.ui

import com.yegorkapomidorka.headswap.R
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopHomeLogicTest {
    private fun inp(
        ready: Boolean = true,
        paired: Boolean = false,
        running: Boolean = false,
        desktop: Boolean = false,
        recovering: Boolean = false,
        status: String = "Рабочий стол не запущен",
        error: String? = null,
        wireless: Boolean = false,
        name: String = "Headswap",
        usbAudio: Boolean = false,
        usbDebug: Boolean = false,
    ) = DesktopInputs(
        ready, paired, running, desktop, recovering, status, error,
        wireless = wireless, videoRes = "1920×1080", desktopName = name, usbAudio = usbAudio, needUsbDebug = usbDebug,
    )

    private fun DesktopHomeState.stage(i: Int) = stages[i].second

    @Test
    fun activationFirstAndEveryOtherStatePending() {
        val s = DesktopHomeLogic.compute(inp(ready = false))
        assertEquals(DesktopPhase.ACTIVATE, s.phase)
        assertEquals(Tone.PRI, s.tone)
        assertEquals(HeroIcon.KEY, s.icon)
        assertEquals(UiText.res(R.string.home_desk_title_activate), s.title)
        assertEquals(UiText.res(R.string.home_desk_sub_activate), s.sub)
        assertEquals(StageState.TODO to UiText.res(R.string.home_desk_st_needed), s.stage(0))
        for (i in 1..4) assertEquals(StageState.PEND, s.stage(i).first)
        assertEquals(listOf(DesktopButton.ACTIVATE), s.buttons)
    }

    @Test
    fun pairedPhoneGetsRepeatActivationText() {
        val s = DesktopHomeLogic.compute(inp(ready = false, paired = true))
        assertEquals(UiText.res(R.string.home_desk_sub_activate_paired), s.sub)
        assertEquals(StageState.TODO to UiText.res(R.string.home_desk_st_needed_again), s.stage(0))
    }

    @Test
    fun activationBeatsEverything() {
        val s = DesktopHomeLogic.compute(inp(ready = false, running = true, desktop = true, error = "x", recovering = true))
        assertEquals(DesktopPhase.ACTIVATE, s.phase)
    }

    @Test
    fun waitsForHeadUnitWhenNothingRuns() {
        val s = DesktopHomeLogic.compute(inp())
        assertEquals(DesktopPhase.WAITING, s.phase)
        assertEquals(Tone.PRI, s.tone)
        assertEquals(HeroIcon.PLUG, s.icon)
        assertEquals(UiText.res(R.string.home_title_ready), s.title)
        assertEquals(UiText.res(R.string.home_sub_ready), s.sub)
        assertEquals(StageState.DONE to UiText.res(R.string.home_desk_st_active), s.stage(0))
        assertEquals(StageState.ACTIVE to UiText.res(R.string.home_stage_wait_cable), s.stage(1))
        for (i in 2..4) assertEquals(StageState.PEND, s.stage(i).first)
        assertEquals(emptyList<DesktopButton>(), s.buttons)
    }

    @Test
    fun serviceUpButDesktopNotYetIsConnectingNotWaitingForCable() {
        val s = DesktopHomeLogic.compute(inp(running = true, status = "Запускаю рабочий стол…"))
        assertEquals(DesktopPhase.WAITING, s.phase)
        assertEquals(UiText.res(R.string.home_title_connecting), s.title)
        assertEquals(UiText.res(R.string.home_desk_sub_starting), s.sub)
        assertEquals(listOf(DesktopButton.STOP), s.buttons)
    }

    @Test
    fun recoveringShowsStatusAndPausesTheRest() {
        val s = DesktopHomeLogic.compute(inp(running = true, recovering = true, status = "Восстанавливаю рабочий стол (1/2)…"))
        assertEquals(DesktopPhase.RECOVERING, s.phase)
        assertEquals(Tone.WARN, s.tone)
        assertEquals(HeroIcon.REFRESH, s.icon)
        assertEquals(UiText.res(R.string.home_title_reconnecting), s.title)
        assertEquals(UiText.raw("Восстанавливаю рабочий стол (1/2)…"), s.sub)
        assertEquals(StageState.DONE, s.stage(0).first)
        assertEquals(StageState.ACTIVE to UiText.res(R.string.home_stage_reconnecting), s.stage(1))
        for (i in 2..4) assertEquals(StageState.PAUSE to UiText.res(R.string.home_stage_paused), s.stage(i))
        assertEquals(listOf(DesktopButton.STOP), s.buttons)
    }

    @Test
    fun staleRecoveryTextWithoutServiceIsJustWaiting() {
        assertEquals(DesktopPhase.WAITING, DesktopHomeLogic.compute(inp(running = false, recovering = true)).phase)
    }

    @Test
    fun errorShowsTextAndDiagWithStopOnlyWhileAlive() {
        val alive = DesktopHomeLogic.compute(inp(running = true, error = "Служба рабочего стола недоступна"))
        assertEquals(DesktopPhase.ERROR, alive.phase)
        assertEquals(Tone.ERR, alive.tone)
        assertEquals(UiText.res(R.string.home_desk_title_error), alive.title)
        assertEquals(UiText.raw("Служба рабочего стола недоступна"), alive.sub)
        assertEquals(StageState.DONE, alive.stage(1).first)
        assertEquals(StageState.ERR to UiText.res(R.string.home_desk_st_error), alive.stage(2))
        assertEquals(StageState.PEND, alive.stage(3).first)
        assertEquals(listOf(DesktopButton.DIAG, DesktopButton.STOP), alive.buttons)

        val dead = DesktopHomeLogic.compute(inp(running = false, error = "boom"))
        assertEquals(StageState.PEND, dead.stage(1).first)
        assertEquals(listOf(DesktopButton.DIAG), dead.buttons)
    }

    @Test
    fun errorBeatsRunningAndAbortedRecovery() {
        assertEquals(DesktopPhase.ERROR, DesktopHomeLogic.compute(inp(running = true, desktop = true, error = "x")).phase)
        // восстановление оборвано (BridgeService:1188): error выставлен, а строка «(1/2)…» осталась в статусе
        assertEquals(DesktopPhase.ERROR, DesktopHomeLogic.compute(inp(running = true, error = "x", recovering = true)).phase)
    }

    @Test
    fun workingShowsRealValues() {
        val s = DesktopHomeLogic.compute(inp(running = true, desktop = true))
        assertEquals(DesktopPhase.WORKING, s.phase)
        assertEquals(Tone.OK, s.tone)
        assertEquals(HeroIcon.CAR_USB, s.icon)
        assertEquals(UiText.res(R.string.home_title_ok), s.title)
        assertEquals(UiText.res(R.string.home_desk_sub_ok, "Headswap"), s.sub)
        assertEquals(StageState.DONE to UiText.raw("CarLife 2.0"), s.stage(1))
        assertEquals(StageState.DONE to UiText.raw("Headswap"), s.stage(2))
        assertEquals(StageState.DONE to UiText.raw("⁦1920×1080⁩"), s.stage(3)) // LTR-изоляция под RTL
        assertEquals(StageState.DONE to UiText.raw("Bluetooth"), s.stage(4))
        assertEquals(listOf(DesktopButton.STOP), s.buttons)
    }

    @Test
    fun workingNamesThirdPartyLauncherAndUsbSound() {
        val s = DesktopHomeLogic.compute(inp(running = true, desktop = true, name = "CarDash", usbAudio = true))
        assertEquals(UiText.res(R.string.home_desk_sub_ok, "CarDash"), s.sub)
        assertEquals(StageState.DONE to UiText.raw("CarDash"), s.stage(2))
        assertEquals(StageState.DONE to UiText.raw("USB"), s.stage(4))
    }

    @Test
    fun wirelessSessionHasNoCable() {
        val s = DesktopHomeLogic.compute(inp(running = true, desktop = true, wireless = true, usbAudio = true))
        assertEquals(HeroIcon.CAR_WIFI, s.icon)
        assertEquals(StageState.DONE to UiText.raw("CarLife 2.0 · Wi-Fi"), s.stage(1))
        assertEquals(StageState.DONE to UiText.raw("Wi-Fi"), s.stage(4))
    }

    @Test
    fun desktopRunningWithoutServiceIsNotWorking() {
        assertEquals(DesktopPhase.WAITING, DesktopHomeLogic.compute(inp(running = false, desktop = true)).phase)
    }

    @Test
    fun android17WithoutUsbDebuggingAsksForIt() {
        val activate = DesktopHomeLogic.compute(inp(ready = false, paired = true, usbDebug = true))
        assertEquals(UiText.res(R.string.home_desk_sub_usb_debug), activate.sub)
        val waiting = DesktopHomeLogic.compute(inp(usbDebug = true))
        assertEquals(DesktopPhase.WAITING, waiting.phase)
        assertEquals(Tone.WARN, waiting.tone)
        assertEquals(UiText.res(R.string.home_desk_sub_usb_debug), waiting.sub)
        // работающий рабочий стол не трогаем
        assertEquals(DesktopPhase.WORKING, DesktopHomeLogic.compute(inp(running = true, desktop = true, usbDebug = true)).phase)
    }
}
