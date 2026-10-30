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
        serverRunning: Boolean? = null,
        ended: Boolean = false,
        graceLeft: Int = -1,
        usbAudio: Boolean = true,
        setupError: UiText? = null,
        wireless: Boolean = false,
        refusedMs: Long = 0,
        hangs: Int = 0,
        worked: Boolean = false,
        refusedFresh: Boolean = false,
        checking: Boolean = false,
    ) = Inputs(
        running, status, protocol, serverRunning, ended, graceLeft, 8, usbAudio, setupError, wireless = wireless,
        refusedForMs = refusedMs, versionHangs = hangs, serverWorked = worked, refusedFresh = refusedFresh, serverChecking = checking,
    )

    @Test
    fun checkingWhileAutoProbeRuns() {
        // проба при открытии идёт, итога ещё нет: не «Проверьте», а «Проверяю», без кнопок
        val s = HomeLogic.compute(inp(checking = true))
        assertEquals(UiText.res(R.string.home_title_checking), s.title)
        assertEquals(StageState.ACTIVE to UiText.res(R.string.home_stage_checking), s.stages[0].second)
        assertEquals(null, s.primary)
        // прошлый итог пробы важнее: пока идёт повторная, экран не мигает
        assertEquals(UiText.res(R.string.home_title_ready), HomeLogic.compute(inp(checking = true, serverRunning = true)).title)
        assertEquals(Phase.OFF, HomeLogic.compute(inp(checking = true, serverRunning = false)).phase)
        // мост важнее пробы
        assertEquals(Phase.WAITING, HomeLogic.compute(inp(checking = true, running = true, status = "мост: жду кабель")).phase)
    }

    @Test
    fun errorWhenCertificateMissing() {
        val err = UiText.res(R.string.home_err_no_cert)
        val s = HomeLogic.compute(inp(setupError = err))
        assertEquals(Phase.ERROR, s.phase)
        assertEquals(err, s.sub)
        assertEquals(Phase.WAITING, HomeLogic.compute(inp(serverRunning = true)).phase)
    }

    @Test
    fun offWhenNothingStarted() {
        // проба получила отказ: точно не запущен
        val s = HomeLogic.compute(inp(serverRunning = false))
        assertEquals(Phase.OFF, s.phase)
        assertEquals(PrimaryAction.START_SERVER, s.primary)
        assertEquals(StageState.TODO, s.stages[0].second.first)
        // сервер ГУ включает пользователь: янтарный тон (не warn), заголовок-призыв и блок из трёх пунктов
        assertEquals(Tone.PRI, s.tone)
        assertEquals(UiText.res(R.string.home_title_off), s.title)
        assertEquals(UiText.res(R.string.home_sub_start_chain), s.sub)
        assertEquals(true, s.showChain)
    }

    @Test
    fun waitingWhenServerStarted() {
        val s = HomeLogic.compute(inp(serverRunning = true))
        assertEquals(Phase.WAITING, s.phase)
        assertEquals(UiText.res(R.string.home_title_ready), s.title)
        assertEquals(OutlineAction.OPEN_AA, s.outline)
    }

    @Test
    fun unknownWhenProbeGaveNoAnswer() {
        // проба не дала ответа (сбой или ещё не было): не утверждаем ни «запущен», ни «не запущен»
        val s = HomeLogic.compute(inp(serverRunning = null))
        assertEquals(Phase.OFF, s.phase)
        assertEquals(Tone.PRI, s.tone)
        assertEquals(HeroIcon.POWER, s.icon)
        assertEquals(UiText.res(R.string.home_title_unknown), s.title)
        assertEquals(UiText.res(R.string.home_sub_unknown), s.sub)
        assertEquals(StageState.PEND to UiText.res(R.string.home_stage_unknown), s.stages[0].second)
        assertEquals(PrimaryAction.START_SERVER, s.primary)
        assertNull(s.outline)
        assertEquals(false, s.showChain)
        assertEquals(UiText.res(R.string.notif_server_unknown), s.notifText)
    }

    @Test
    fun parkedTextDependsOnServerProbe() {
        // проба после поездки подтверждает сервер: прежний текст про фон
        val up = HomeLogic.compute(inp(ended = true, serverRunning = true))
        assertEquals(Phase.PARKED, up.phase)
        assertEquals(UiText.res(R.string.home_sub_parked), up.sub)
        assertEquals(UiText.res(R.string.notif_parked), up.notifText)
        // проба не дала ответа: нейтральный текст
        val unknown = HomeLogic.compute(inp(ended = true, serverRunning = null))
        assertEquals(Phase.PARKED, unknown.phase)
        assertEquals(UiText.res(R.string.home_sub_parked_unknown), unknown.sub)
        assertEquals(UiText.res(R.string.notif_parked_unknown), unknown.notifText)
        // сервер остановили: «Запустите сервер ГУ»
        val down = HomeLogic.compute(inp(ended = true, serverRunning = false))
        assertEquals(Phase.OFF, down.phase)
        assertEquals(UiText.res(R.string.home_title_off), down.title)
    }

    @Test
    fun shortRefusalIsConnectingWhenProbeSaysRunning() {
        val status = "мост: жду сервер ГУ AA"
        val up = HomeLogic.compute(inp(running = true, status = status, protocol = true, serverRunning = true))
        assertEquals(Phase.WAITING, up.phase)
        assertEquals(UiText.res(R.string.home_title_connecting), up.title)
        // без итога пробы прежнее «Запустите сервер ГУ»
        val unknown = HomeLogic.compute(inp(running = true, status = status, protocol = true, serverRunning = null))
        assertEquals(Phase.OFF, unknown.phase)
        assertEquals(UiText.res(R.string.home_title_off), unknown.title)
    }

    @Test
    fun freshRefusalOnWorkedServerIsStartServerImmediately() {
        val status = "мост: жду сервер ГУ AA"
        // свежий старт моста: отказ не в окне после закрытия своей сессии - сервер не запущен, ждать нечего
        val fresh = HomeLogic.compute(inp(running = true, status = status, protocol = true, worked = true, refusedFresh = true, refusedMs = 500))
        assertEquals(Phase.OFF, fresh.phase)
        assertEquals(UiText.res(R.string.home_title_off), fresh.title)
        assertEquals(PrimaryAction.START_SERVER, fresh.primary)
        // сразу после закрытия своей сессии отказ норма: «Подключаюсь»
        val afterBye = HomeLogic.compute(inp(running = true, status = status, protocol = true, worked = true, refusedFresh = false, refusedMs = 500))
        assertEquals(Phase.WAITING, afterBye.phase)
        assertEquals(UiText.res(R.string.home_title_connecting), afterBye.title)
        // проба сказала «отказ»: просим запустить, даже внутри окна
        val stopped = HomeLogic.compute(inp(running = true, status = status, protocol = true, worked = true, serverRunning = false, refusedFresh = false, refusedMs = 500))
        assertEquals(Phase.OFF, stopped.phase)
        assertEquals(UiText.res(R.string.home_title_off), stopped.title)
        // проба сказала «запущен»: свежесть отказа не важна
        val running = HomeLogic.compute(inp(running = true, status = status, protocol = true, worked = true, serverRunning = true, refusedFresh = true))
        assertEquals(Phase.WAITING, running.phase)
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
    fun parkedAfterSessionEnded() {
        // мост сам штатно закрыл установленную сессию: «Машина отключена», сервер ГУ в фоне «работает», без «проверяю» и кнопок
        val s = HomeLogic.compute(inp(ended = true, serverRunning = true))
        assertEquals(Phase.PARKED, s.phase)
        assertEquals(Tone.PRI, s.tone)
        assertEquals(HeroIcon.CAR, s.icon)
        assertEquals(UiText.res(R.string.home_title_parked), s.title)
        assertEquals(UiText.res(R.string.home_sub_parked), s.sub)
        assertNull(s.primary)
        assertNull(s.outline)
        assertEquals(false, s.logLink)
        assertEquals(StageState.DONE to UiText.res(R.string.home_stage_working), s.stages[0].second)
        assertEquals(StageState.PEND, s.stages[1].second.first)
        assertEquals(UiText.res(R.string.home_stage_hu_parked), s.stages[1].second.second)
        assertEquals(UiText.res(R.string.notif_parked), s.notifText)
        // отказы и зависания прошлых подключений PARKED не меняют: SERVER_DOWN только при живом мосте
        val stale = HomeLogic.compute(inp(ended = true, serverRunning = true, refusedMs = 60_000, hangs = 3, worked = true))
        assertEquals(Phase.PARKED, stale.phase)
    }

    @Test
    fun serverDownWhenRefusedLongerThanFifteenSeconds() {
        val status = "мост: жду сервер ГУ AA"
        val s = HomeLogic.compute(inp(running = true, status = status, protocol = true, refusedMs = HomeLogic.REFUSED_DOWN_MS, worked = true))
        assertEquals(Phase.ERROR, s.phase)
        assertEquals(Tone.ERR, s.tone)
        assertEquals(HeroIcon.ALERT, s.icon)
        assertEquals(UiText.res(R.string.home_title_server_down), s.title)
        assertEquals(UiText.res(R.string.home_sub_server_down), s.sub)
        assertEquals(StageState.ERR to UiText.res(R.string.home_stage_no_answer), s.stages[0].second)
        assertEquals(PrimaryAction.START_SERVER, s.primary)
        assertEquals(true, s.logLink)
        assertEquals(UiText.res(R.string.home_title_server_down), s.notifTitle)
        assertEquals(UiText.res(R.string.notif_server_down), s.notifText)
        // короткий отказ (~6 с после ByeBye) норма: обычное «Подключаюсь», без красного и без «Запустите сервер ГУ»
        val short = HomeLogic.compute(inp(running = true, status = status, protocol = true, refusedMs = HomeLogic.REFUSED_DOWN_MS - 1, worked = true))
        assertEquals(Phase.WAITING, short.phase)
        assertEquals(false, short.showChain)
    }

    @Test
    fun serverDownWhenTwoAttemptsInARowHangWithoutVersionResponse() {
        val status = "мост: AA отключился"
        val s = HomeLogic.compute(inp(running = true, status = status, protocol = true, hangs = HomeLogic.HANGS_DOWN, worked = true))
        assertEquals(Phase.ERROR, s.phase)
        assertEquals(UiText.res(R.string.home_title_server_down), s.title)
        assertEquals(UiText.res(R.string.notif_server_down), s.notifText)
        // одно зависание ещё не вывод: обычное «Подключаюсь»
        val one = HomeLogic.compute(inp(running = true, status = status, protocol = true, hangs = 1, worked = true))
        assertEquals(Phase.WAITING, one.phase)
        assertEquals(UiText.res(R.string.home_title_connecting), one.title)
    }

    @Test
    fun serverDownNeedsEvidenceThatItWasUp() {
        // новичок: сервер ни разу не работал, длинный отказ и зависания не дают красного - прежнее «Запустите сервер ГУ»
        val fresh = HomeLogic.compute(inp(running = true, status = "мост: жду сервер ГУ AA", refusedMs = 60_000))
        assertEquals(Phase.OFF, fresh.phase)
        assertEquals(UiText.res(R.string.home_title_off), fresh.title)
        assertEquals(true, fresh.showChain)
        assertEquals(Phase.WAITING, HomeLogic.compute(inp(running = true, status = "мост: AA отключился", hangs = 5)).phase)
    }

    @Test
    fun serverDownOnlyWhileBridgeAttemptsAndNotOnceConnected() {
        // моста нет (кабеля нет): прошлые отказы ничего не значат
        assertEquals(Phase.OFF, HomeLogic.compute(inp(serverRunning = false, refusedMs = 60_000, hangs = 3, worked = true)).phase)
        // сессия идёт: обычное «Всё работает», даже если счётчики ещё не обнулились
        val connected = HomeLogic.compute(inp(running = true, status = "мост: AA -> CarLife", refusedMs = 60_000, hangs = 3, worked = true))
        assertEquals(Phase.CONNECTED, connected.phase)
        // обрыв USB: grace держит сессию, отказов нет
        val grace = HomeLogic.compute(inp(running = true, status = "мост: USB потерян, AA жива 8 с", refusedMs = 60_000, worked = true))
        assertEquals(Phase.RECONNECTING, grace.phase)
    }

    @Test
    fun cableButServerRefusesIsOff() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: жду сервер ГУ AA", protocol = true))
        assertEquals(Phase.OFF, s.phase)
        assertEquals(Tone.PRI, s.tone)
        assertEquals(true, s.showChain)
        assertEquals(StageState.DONE, s.stages[1].second.first)
    }

    @Test
    fun cableConnectingIsWaiting() {
        val s = HomeLogic.compute(inp(running = true, status = "мост: старт"))
        assertEquals(Phase.WAITING, s.phase)
        assertEquals(UiText.res(R.string.home_title_connecting), s.title)
        assertNull(s.primary)
    }
}
