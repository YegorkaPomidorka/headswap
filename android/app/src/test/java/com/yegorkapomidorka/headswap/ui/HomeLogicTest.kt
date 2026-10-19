package com.yegorkapomidorka.headswap.ui

import com.yegorkapomidorka.headswap.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeLogicTest {
    private fun inp(
        running: Boolean = false,
        status: String = "не запущено",
        protocol: Boolean = false,
        serverUp: Boolean = false,
        autoReady: Boolean = false,
        lost: Boolean = false,
        graceLeft: Int = -1,
        usbAudio: Boolean = true,
        setupError: UiText? = null,
        consent: Boolean = false,
        wireless: Boolean = false,
        offered: Boolean = false,
        waiting: Boolean = false,
    ) = Inputs(
        running, status, protocol, serverUp, autoReady, lost, graceLeft, 8, usbAudio, setupError, aaConsent = consent, wireless = wireless,
        wirelessOffered = offered, wirelessWaiting = waiting,
    )

    /** h185: беспровод уже использовался, моста нет - под основной кнопкой «Ждать машину по Wi-Fi». */
    @Test
    fun wirelessWaitButtonWhenOffered() {
        assertEquals(false, HomeLogic.compute(inp()).wirelessWait)
        val s = HomeLogic.compute(inp(offered = true))
        assertEquals(true, s.wirelessWait)
        assertEquals(PrimaryAction.START_SERVER, s.primary)
    }

    /** h185: ожидание по воздуху без моста - «Остановить»; во время беспроводной сессии «Ждать» нет. */
    @Test
    fun stopWhileWaitingOverAir() {
        val s = HomeLogic.compute(inp(offered = true, waiting = true))
        assertEquals(OutlineAction.STOP, s.outline)
        assertEquals(true, s.wirelessWait)
        val serverUp = HomeLogic.compute(inp(serverUp = true, waiting = true))
        assertEquals(OutlineAction.STOP, serverUp.outline)
        val conn = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife", protocol = true, wireless = true, offered = true, waiting = true))
        assertEquals(false, conn.wirelessWait)
        assertEquals(OutlineAction.STOP, conn.outline)
    }

    @Test
    fun errorWhenCertificateMissing() {
        val err = UiText.res(R.string.home_err_no_cert)
        val s = HomeLogic.compute(inp(setupError = err))
        assertEquals(Phase.ERROR, s.phase)
        assertEquals(err, s.sub)
        assertEquals(Phase.WAITING, HomeLogic.compute(inp(serverUp = true)).phase)
    }

    @Test
    fun offWhenNothingStarted() {
        val s = HomeLogic.compute(inp())
        assertEquals(Phase.OFF, s.phase)
        assertEquals(PrimaryAction.START_SERVER, s.primary)
        assertEquals(StageState.TODO, s.stages[0].second.first)
    }

    @Test
    fun waitingWhenServerStartedOrAuto() {
        assertEquals(Phase.WAITING, HomeLogic.compute(inp(serverUp = true)).phase)
        val auto = HomeLogic.compute(inp(autoReady = true))
        assertEquals(Phase.WAITING, auto.phase)
        assertEquals(OutlineAction.OPEN_AA, auto.outline)
    }

    @Test
    fun connectedFromBridgeStatus() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife (перекодирование)"))
        assertEquals(Phase.CONNECTED, s.phase)
        assertEquals(OutlineAction.STOP, s.outline)
        assertEquals(UiText.raw("USB"), s.stages[4].second.second)
        assertEquals(UiText.res(R.string.notif_video_usb), s.notifText)
        val noAudio = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife", usbAudio = false))
        assertEquals(StageState.PEND, noAudio.stages[4].second.first)
        assertEquals(UiText.res(R.string.notif_video), noAudio.notifText)
        assertEquals(HeroIcon.CAR_USB, s.icon)
    }

    @Test
    fun connectedOverWifiShowsWifi() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife (перекодирование)", wireless = true))
        assertEquals(Phase.CONNECTED, s.phase)
        assertEquals(HeroIcon.CAR_WIFI, s.icon)
        assertEquals(UiText.res(R.string.home_sub_ok_wifi), s.sub)
        assertEquals(UiText.raw("CarLife 2.0 · Wi-Fi"), s.stages[1].second.second)
        assertEquals(UiText.raw("Wi-Fi"), s.stages[4].second.second)
        assertEquals(UiText.res(R.string.notif_video_wifi), s.notifText)
        val noAudio = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife", usbAudio = false, wireless = true))
        assertEquals(StageState.PEND, noAudio.stages[4].second.first)
        assertEquals(UiText.res(R.string.notif_video), noAudio.notifText)
    }

    @Test
    fun reconnectingShowsCountdown() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: USB потерян, AA жива 8 с", graceLeft = 5))
        assertEquals(Phase.RECONNECTING, s.phase)
        assertEquals(5, s.graceLeftS)
        assertEquals(UiText.res(R.string.notif_reconnecting, 5), s.notifText)
        assertEquals(StageState.PAUSE, s.stages[4].second.first)
    }

    @Test
    fun errorAfterLossWhenServiceStopped() {
        val s = HomeLogic.compute(inp(lost = true, status = "мост: AA отключился"))
        assertEquals(Phase.ERROR, s.phase)
        assertEquals(PrimaryAction.RESTART_SERVER, s.primary)
        assertEquals(true, s.logLink)
        assertEquals(UiText.res(R.string.notif_restart_title), s.notifTitle)
    }

    @Test
    fun cableButServerRefusesIsOff() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: жду сервер ГУ AA", protocol = true))
        assertEquals(Phase.OFF, s.phase)
        assertEquals(StageState.DONE, s.stages[1].second.first)
    }

    @Test
    fun cableConnectingIsWaiting() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: старт"))
        assertEquals(Phase.WAITING, s.phase)
        assertEquals(UiText.res(R.string.home_title_connecting), s.title)
        assertNull(s.primary)
    }

    @Test
    fun consentOverridesConnectedAndReconnecting() {
        for (st in listOf("мост: AA -> CarLife (перекодирование)", "мост: USB потерян, AA жива 8 с")) {
            val s = HomeLogic.compute(inp(running = true, status = st, consent = true))
            assertEquals(st, Phase.WAITING, s.phase)
            assertEquals(Tone.WARN, s.tone)
            assertEquals(UiText.res(R.string.home_title_aa_consent), s.title)
            assertEquals(OutlineAction.OPEN_AA, s.outline)
            assertEquals(StageState.ACTIVE, s.stages[2].second.first)
            assertEquals(UiText.res(R.string.notif_aa_consent), s.notifText)
        }
        // мост не запущен - подсказка не нужна
        assertEquals(Phase.OFF, HomeLogic.compute(inp(consent = true)).phase)
        // ошибка настройки важнее
        assertEquals(Phase.ERROR, HomeLogic.compute(inp(setupError = UiText.raw("x"), consent = true)).phase)
    }
}
