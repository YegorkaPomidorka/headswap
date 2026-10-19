package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.AnnexB
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.yegorkapomidorka.headswap.aa.AapSessionTest.Phone as FakePhone

class FakeLink : CarlifeLink {
    val sent = CopyOnWriteArrayList<ByteArray>()
    val texts = CopyOnWriteArrayList<String>()
    @Volatile var hides = 0
    @Volatile var accept = true
    override fun sendVideo(payload: ByteArray): Boolean {
        if (!accept) return false
        sent.add(payload)
        return true
    }

    override fun showPlaceholder(text: String) {
        texts.add(text)
    }

    override fun hidePlaceholder() {
        hides++
    }

    @Volatile var takeovers = 0
    override fun sendVideoTakeover(payload: ByteArray): Boolean {
        val ok = sendVideo(payload)
        if (ok) takeovers++
        return ok
    }

    class Audio(val chan: Int, val type: Int, val payload: ByteArray)

    val audio = CopyOnWriteArrayList<Audio>()
    override fun sendAudio(channel: Int, type: Int, payload: ByteArray): Boolean {
        if (!accept) return false
        audio.add(Audio(channel, type, payload))
        events.add("A${channel}:0x%x".format(type))
        return true
    }

    val modules = CopyOnWriteArrayList<Pair<Int, Int>>()
    /** Всё аудио-управление в порядке отправки: "A<канал>:0x<тип>" и "M<модуль>=<статус>". */
    val events = CopyOnWriteArrayList<String>()
    override fun sendModuleStatus(module: Int, status: Int): Boolean {
        if (!accept) return false
        modules.add(module to status)
        events.add("M$module=$status")
        return true
    }

    @Volatile var desktops = 0
    override fun goToDesktop() {
        desktops++
    }
}

class FakePeer : AaPeer {
    /** Проекция одного пальца (action, x, y) первого указателя - для старых проверок. */
    val touches = CopyOnWriteArrayList<Triple<Int, Int, Int>>()

    /** Полная форма: (action, action_index, пальцы). */
    val events = CopyOnWriteArrayList<Triple<Int, Int, List<TouchPoint>>>()
    @Volatile var keyframes = 0
    override fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        events.add(Triple(action, actionIndex, points))
        touches.add(Triple(action, points[0].x, points[0].y))
    }

    override fun requestKeyFrame() {
        keyframes++
    }

    val keys = CopyOnWriteArrayList<Int>()
    override fun sendKey(keycode: Int) {
        keys.add(keycode)
    }

    val rotary = CopyOnWriteArrayList<Int>()
    override fun sendRotary(delta: Int) {
        rotary.add(delta)
    }

    @Volatile var natives = 0
    override fun sendVideoFocusNative() {
        natives++
    }

    val nights = CopyOnWriteArrayList<Boolean>()
    override fun sendNightMode(night: Boolean) {
        nights.add(night)
    }
}

class AaBridgeCoreTest {
    private val sps = Golden.hex("00000001 67 42c01f da0140")
    private val pps = Golden.hex("00000001 68 ce3c80")
    private val cfg = sps + pps
    private val idr = Golden.hex("00000001 65") + ByteArray(100) { (it + 1).toByte() }
    private val p1 = Golden.hex("00000001 41") + ByteArray(30) { 0x22 }
    private var now = 0L
    private val link = FakeLink()
    private val logs = CopyOnWriteArrayList<String>()
    private val core = AaBridgeCore({ l, _ -> logs.add(l) }, { now }).also { it.attach(link) }

    private fun types(b: ByteArray) = AnnexB.nalTypes(b)

    @Test
    fun huThemeDrivesAaNightModeAndSurvivesPeerReconnect() {
        val p = FakePeer()
        core.peer = p
        assertEquals(listOf(false), p.nights.toList())        // до CHANGE_MD_THEME - день
        core.onCarlifeTheme(AaBridgeCore.THEME_DARK)
        core.onCarlifeTheme(AaBridgeCore.THEME_AUTO)
        core.onCarlifeTheme(null)                              // без поля - тему не трогаем
        core.onCarlifeTheme(AaBridgeCore.THEME_DARK)
        assertEquals(listOf(false, true, false, true), p.nights.toList())
        assertEquals(AaBridgeCore.THEME_DARK, core.huTheme)
        val p2 = FakePeer()
        core.peer = p2                                         // новая AA-сессия получает текущую тему сразу
        assertEquals(listOf(true), p2.nights.toList())
    }

    @Test
    fun startsWithPlaceholderAndHidesItOnFirstIdrGluedWithConfig() {
        assertEquals(listOf(AaBridgeCore.TEXT_START_SERVER), link.texts.toList())
        assertEquals(AaBridgeCore.Source.PLACEHOLDER, core.activeSource)
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        assertEquals(1, link.sent.size)
        assertArrayEquals(cfg + idr, link.sent[0])            // SPS+PPS+IDR в одном сообщении
        assertEquals(listOf(7, 8, 5), types(link.sent[0]))
        assertEquals(1, link.hides)
        assertEquals(1, link.takeovers)                       // первый AA-IDR атомарно отключает кадры заглушки
        assertEquals(AaBridgeCore.Source.AA, core.activeSource)
    }

    @Test
    fun dropsPFramesUntilIdrThenPassesThem() {
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(p1, 1)
        core.onFrame(p1, 2)
        assertEquals(0, link.sent.size)
        assertEquals(2L, core.dropped)
        assertEquals(0, link.hides)                           // пока IDR не было, заглушка остаётся
        core.onFrame(idr, 3)
        core.onFrame(p1, 4)
        assertEquals(2, link.sent.size)
        assertArrayEquals(p1, link.sent[1])
        assertEquals(2L, core.dropped)
    }

    @Test
    fun idrWithInlineSpsPpsIsNotGluedTwice() {
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(cfg + idr, 1)
        assertArrayEquals(cfg + idr, link.sent[0])
        assertEquals(listOf(7, 8, 5), types(link.sent[0]))
    }

    @Test
    fun threeByteStartCodesAreNormalizedToFour() {
        val cfg3 = Golden.hex("000001 67 42c01f da0140 000001 68 ce3c80")
        val idr3 = Golden.hex("000001 65 aabbcc")
        val p3 = Golden.hex("000001 41 11")
        core.onCarlifeStart()
        core.onCodecConfig(cfg3)
        core.onFrame(idr3, 1)
        core.onFrame(p3, 2)
        assertArrayEquals(cfg + Golden.hex("00000001 65 aabbcc"), link.sent[0])
        assertArrayEquals(Golden.hex("00000001 41 11"), link.sent[1])
    }

    @Test
    fun idrWithoutAnyConfigIsDropped() {
        core.onCarlifeStart()
        core.onFrame(idr, 1)
        assertEquals(0, link.sent.size)
        assertEquals(AaBridgeCore.Source.PLACEHOLDER, core.activeSource)
    }

    @Test
    fun nothingGoesBeforeCarlifeStart() {
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        assertEquals(0, link.sent.size)
        assertEquals(1L, core.dropped)
    }

    @Test
    fun carlifeStartResetsGateAndRequestsKeyframeWhenAaIsAlreadyStreaming() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)                                  // gate открыт
        core.onFrame(p1, 2)
        assertEquals(2, link.sent.size)
        now = 10_000
        core.onCarlifeStart()                                 // ГУ перезапустил видео: нужен новый IDR
        core.onFrame(p1, 3)                                   // P до IDR отброшен
        assertEquals(2, link.sent.size)
        core.tick()
        assertEquals("ключевой кадр сразу: AA уже играет", 1, peer.keyframes)
        core.tick()
        assertEquals("не чаще раза в 4 с", 1, peer.keyframes)
        now += 4000
        core.tick()
        assertEquals(2, peer.keyframes)
        core.onFrame(idr, 4)                                  // после IDR запросы прекращаются
        assertArrayEquals(cfg + idr, link.sent[2])
        now += 10_000
        core.tick()
        assertEquals(2, peer.keyframes)
    }

    /** Passthrough, IDR ушёл в now=0, затем P-кадры; peer подключён. */
    private fun h295Setup(hondaFlag: Boolean): FakePeer {
        val peer = FakePeer()
        core.peer = peer
        if (hondaFlag) core.onHuStatistic(AaBridgeCore.HONDA_20772121_CHANNEL)
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        core.onFrame(p1, 2)
        return peer
    }

    @Test
    fun h295TouchAfterIdleAndStaleIdrRequestsKeyframeCycleAndKeepsGateOpen() {
        val peer = h295Setup(true)
        val sentBefore = link.sent.size
        now = 5000
        core.onCarlifeTouch(0, 10, 10)
        assertEquals("тач ушёл в AA", 1, peer.touches.size)
        assertEquals("сразу полный цикл", 1, peer.keyframes)
        assertTrue(logs.any { it.startsWith("BRIDGE h295: тач после 5000 мс без IDR, пауза в тачах ") && it.endsWith(" мс -> keyframe-цикл") })
        core.onFrame(p1, 3)                                   // gate открыт: P-кадр проходит
        assertEquals(sentBefore + 1, link.sent.size)
        assertArrayEquals(p1, link.sent.last())
        core.onTouch(0)                                       // второй DOWN ГУ (~2 мс): пауза в тачах ~0, триггера нет
        assertEquals(1, peer.keyframes)
        core.tick()                                           // отдельного фолбэка нет
        assertEquals(1, peer.keyframes)
        now = 5400
        core.onFrame(idr, 4)                                  // IDR в обычной ветке (gate открыт), без takeover
        assertEquals(1, link.takeovers)
        assertEquals(1, link.hides)
        core.onFrame(p1, 5)
        assertArrayEquals(p1, link.sent.last())
    }

    @Test
    fun h295AwaitedBareIdrIsGluedWithAaConfig() {
        h295Setup(true)
        now = 5000
        core.onTouch(0)
        now = 7500
        core.onFrame(idr, 4)
        assertArrayEquals(cfg + idr, link.sent.last())
        assertEquals(listOf(7, 8, 5), types(link.sent.last()))
        assertTrue(logs.any { it.startsWith("BRIDGE h295: IDR через 2500 мс после тача, nal=[5] -> [7, 8, 5]") })
        now = 7600
        core.onFrame(idr, 5)                                  // ожидание закончено: следующий IDR как в main
        assertArrayEquals(idr, link.sent.last())
        core.onFrame(p1, 6)
        assertArrayEquals(p1, link.sent.last())
    }

    @Test
    fun h295AwaitedIdrWithInlineSpsPpsIsNotGluedAgain() {
        h295Setup(true)
        now = 5000
        core.onTouch(0)
        now = 6000
        core.onFrame(cfg + idr, 4)
        assertArrayEquals(cfg + idr, link.sent.last())
        assertTrue(logs.any { it.startsWith("BRIDGE h295: IDR через 1000 мс после тача, nal=[7, 8, 5] -> [7, 8, 5]") })
    }

    @Test
    fun h295IdrWithoutTriggerIsNotGlued() {
        h295Setup(true)
        now = 100
        core.onFrame(idr, 4)
        assertArrayEquals(idr, link.sent.last())
        now = 2000                                            // IDR свежий: триггера нет и после паузы
        core.onTouch(0)
        core.onFrame(idr, 5)
        assertArrayEquals(idr, link.sent.last())
        assertTrue(logs.none { it.startsWith("BRIDGE h295: IDR через") })
    }

    @Test
    fun h295PFramesAfterTriggerAreNotTouched() {
        h295Setup(true)
        now = 5000
        core.onTouch(0)
        core.onFrame(p1, 3)
        assertArrayEquals(p1, link.sent.last())
    }

    @Test
    fun h295IdrAfterGiveUpIsNotGlued() {
        h295Setup(true)
        now = 5000
        core.onTouch(0)
        now = 15_100
        core.tick()                                           // 10.1 с без IDR: ожидание сброшено
        core.onFrame(idr, 4)
        assertArrayEquals(idr, link.sent.last())
        assertTrue(logs.none { it.startsWith("BRIDGE h295: IDR через") })
    }

    @Test
    fun h295NoTriggerIfPreviousTouchWasLessThanIdleAgo() {
        val peer = h295Setup(true)
        now = 1000
        core.onTouch(0)                                       // IDR свежий, но тач запомнен
        now = 3000
        core.onTouch(2)                                       // MOVE тоже считается тачем
        now = 6900                                            // IDR старый, прошлый тач 3.9 с назад (< 4 с)
        core.onTouch(0)
        assertEquals("пауза 3.9 с < 4 с", 0, peer.keyframes)
        now = 10_900                                          // прошлый тач ровно 4 с назад
        core.onTouch(0)
        assertEquals(1, peer.keyframes)
    }

    @Test
    fun h295FreshIdrDoesNotTriggerEvenAfterIdle() {
        val peer = h295Setup(true)
        now = 2000                                            // IDR 2 с назад (<= 2.5 с)
        core.onTouch(0)
        assertEquals(0, peer.keyframes)
    }

    @Test
    fun h295RateLimitsRepeatedTriggers() {
        val peer = h295Setup(true)
        now = 5000
        core.onTouch(0)
        assertEquals(1, peer.keyframes)
        now = 9100                                            // пауза 4.1 с, но с триггера 4.1 с > 3 с: можно снова
        core.onTouch(0)
        assertEquals(2, peer.keyframes)
        now = 13_200
        core.onFrame(idr, 4)
        now = 20_000
        core.tick()
        assertEquals("tick циклов не добавляет", 2, peer.keyframes)
    }

    @Test
    fun h295DoesNothingWithoutHondaFlag() {
        val peer = h295Setup(false)
        now = 60_000
        core.onTouch(0)
        assertEquals(0, peer.keyframes)
        core.onFrame(p1, 3)
        assertArrayEquals(p1, link.sent.last())               // gate открыт
    }

    @Test
    fun h295MoveUpAreNotTriggersAndAttachResetsFlag() {
        val peer = h295Setup(true)
        now = 60_000
        core.onTouch(2)                                       // MOVE - не триггер
        core.onTouch(1)                                       // UP - не триггер
        assertEquals(0, peer.keyframes)
        assertTrue(core.huHonda20772121)
        core.attach(link)                                     // новая сессия сбрасывает флаг ГУ
        assertFalse(core.huHonda20772121)
    }

    private fun AaBridgeCore.onTouch(action: Int) = onCarlifeTouch(action, 10, 10)

    @Test
    fun keyframeRequestWaitsForDelayWhenAaVideoAppearsAfterStart() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        now = 1000
        core.onCodecConfig(cfg)                               // AA видео началось, но IDR нет
        core.tick()                                           // отсчёт ожидания
        now = 2000
        core.tick()
        assertEquals(0, peer.keyframes)                       // 1 с < 1.5 с
        now = 2600
        core.tick()
        assertEquals(1, peer.keyframes)
    }

    @Test
    fun touchMapping() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeTouch(0, 40, 675)
        core.onCarlifeTouch(2, 41, 676)
        core.onCarlifeTouch(1, 41, 676)
        core.onCarlifeTouch(3, 1, 1)                          // нет аналога
        assertEquals(listOf(Triple(0, 40, 675), Triple(2, 41, 676), Triple(1, 41, 676)), peer.touches.toList())
        core.peer = null
        core.onCarlifeTouch(0, 5, 5)                          // AA не подключён: игнор без исключений
        assertEquals(3, peer.touches.size)
        assertTrue(logs.any { it.contains("DOWN 40,675") })
    }

    private fun pts(vararg p: Int) = p.toList().chunked(3).map { TouchPoint(it[0], it[1], it[2]) }

    @Test
    fun pinchSecondFingerUpFirst() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeTouch(0, 100, 100)
        core.onCarlifeTouch(5 or (1 shl 8), 100, 100, 300, 300)        // 261
        core.onCarlifeTouch(2, 90, 90, 310, 310)
        core.onCarlifeTouch(2, 85, 85)                                 // px/py = 0: второй палец на прежнем месте
        core.onCarlifeTouch(2, 80, 80, 320, 320)
        core.onCarlifeTouch(6 or (1 shl 8), 80, 80, 320, 320)          // 262
        core.onCarlifeTouch(2, 81, 81)
        core.onCarlifeTouch(1, 81, 81)
        assertEquals(
            listOf(
                Triple(0, 0, pts(0, 100, 100)),
                Triple(5, 1, pts(0, 100, 100, 1, 300, 300)),
                Triple(2, 0, pts(0, 90, 90, 1, 310, 310)),
                Triple(2, 0, pts(0, 85, 85, 1, 310, 310)),
                Triple(2, 0, pts(0, 80, 80, 1, 320, 320)),
                Triple(6, 1, pts(0, 80, 80, 1, 320, 320)),
                Triple(2, 0, pts(0, 81, 81)),
                Triple(1, 0, pts(0, 81, 81)),
            ),
            peer.events.toList(),
        )
        assertTrue(logs.any { it.contains("POINTER_DOWN[1] пальцев 2") })
    }

    @Test
    fun pinchFirstFingerUpKeepsId() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeTouch(0, 10, 10)
        core.onCarlifeTouch(261, 10, 10, 50, 50)
        core.onCarlifeTouch(6, 12, 12, 52, 52)                         // поднят палец индекса 0
        core.onCarlifeTouch(2, 55, 55)                                 // оставшийся палец теперь в x,y, id 1
        core.onCarlifeTouch(1, 56, 56)
        core.onCarlifeTouch(0, 7, 7)                                   // новый жест: снова id 0
        core.onCarlifeTouch(1, 7, 7)
        assertEquals(
            listOf(
                Triple(0, 0, pts(0, 10, 10)),
                Triple(5, 1, pts(0, 10, 10, 1, 50, 50)),
                Triple(6, 0, pts(0, 12, 12, 1, 52, 52)),
                Triple(2, 0, pts(1, 55, 55)),
                Triple(1, 0, pts(1, 56, 56)),
                Triple(0, 0, pts(0, 7, 7)),
                Triple(1, 0, pts(0, 7, 7)),
            ),
            peer.events.toList(),
        )
    }

    @Test
    fun pinchLostPointerUpAndThirdFinger() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeTouch(0, 10, 10)
        core.onCarlifeTouch(261, 10, 10)                               // без pointer-полей: второй палец в x,y (как Baidu)
        core.onCarlifeTouch(5 or (2 shl 8), 10, 10, 20, 20)            // третий палец: не передаём
        core.onCarlifeTouch(1, 11, 11)                                 // POINTER_UP потерян: сначала снимаем второй
        assertEquals(
            listOf(
                Triple(0, 0, pts(0, 10, 10)),
                Triple(5, 1, pts(0, 10, 10, 1, 10, 10)),
                Triple(6, 1, pts(0, 10, 10, 1, 10, 10)),
                Triple(1, 0, pts(0, 11, 11)),
            ),
            peer.events.toList(),
        )
    }

    @Test
    fun hardKeyMapping() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeHardKey(16)                             // SEEK_ADD
        core.onCarlifeHardKey(15)                             // SEEK_SUB
        core.onCarlifeHardKey(0x1F)                           // MEDIA_START
        core.onCarlifeHardKey(0x2F)                           // DEL: нет аналога
        assertEquals(listOf(87, 88, 126), peer.keys.toList())
        assertTrue(logs.any { it.contains("руль keycode=16 -> AA KEYCODE 87") })
        core.peer = null
        core.onCarlifeHardKey(16)                             // AA не подключён: игнор без исключений
        assertEquals(3, peer.keys.size)
        assertTrue(AaBridgeCore.HARD_KEY_TO_ANDROID.values.all { it in AapMessages.KEYCODES_SUPPORTED })
    }

    @Test
    fun selectorKnobToRotary() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeHardKey(6)                              // SELECTOR_NEXT
        core.onCarlifeHardKey(7)                              // SELECTOR_PREVIOUS
        core.onCarlifeHardKey(0x14)                           // OK: нажатие шайбы
        assertEquals(listOf(1, -1), peer.rotary.toList())
        assertEquals(listOf(23), peer.keys.toList())
        assertTrue(logs.any { it.contains("шайба keycode=6 -> AA rotary 1") })
        assertTrue(AapMessages.KEYCODE_ROTARY_CONTROLLER in AapMessages.KEYCODES_SUPPORTED)
        core.peer = null
        core.onCarlifeHardKey(6)                              // AA не подключён: игнор без исключений
        assertEquals(2, peer.rotary.size)
    }

    @Test
    fun sourceSwitchPlaceholderToAaAndBackThenToNewAaStream() {
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        assertEquals(AaBridgeCore.Source.AA, core.activeSource)
        // AA завершилась после установки: заглушка с нужным текстом, CarLife не трогаем
        core.onAaEnded(established = true)
        assertEquals(AaBridgeCore.Source.PLACEHOLDER, core.activeSource)
        assertEquals(AaBridgeCore.TEXT_AA_LOST, link.texts.last())
        // сервер перезапущен: новая сессия с другим config -> новый SPS+PPS+IDR
        core.onAaConnecting()
        val sps2 = Golden.hex("00000001 67 4d401f ac2c a5")
        val cfg2 = sps2 + pps
        core.onFrame(idr, 5)                                  // IDR без config от новой сессии: нечем клеить
        assertEquals(1, link.sent.size)
        core.onCodecConfig(cfg2)
        core.onFrame(p1, 6)
        assertEquals(1, link.sent.size)
        core.onFrame(idr, 7)
        assertEquals(2, link.sent.size)
        assertArrayEquals(cfg2 + idr, link.sent[1])
        assertEquals(AaBridgeCore.Source.AA, core.activeSource)
        assertEquals(2, link.hides)
    }

    @Test
    fun refusedConnectionKeepsStartServerText() {
        core.onAaConnecting()
        core.onAaEnded(established = false)
        assertEquals(listOf(AaBridgeCore.TEXT_START_SERVER), link.texts.toList())
        assertEquals(AaBridgeCore.TEXT_START_SERVER, core.currentPlaceholderText)
    }

    @Test
    fun rejectedFrameClosesGateUntilNextIdr() {
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        link.accept = false
        core.onFrame(p1, 2)                                   // очередь CarLife переполнена: кадр потерян
        link.accept = true
        core.onFrame(p1, 3)                                   // дальше P нельзя: ждём IDR
        assertEquals(1, link.sent.size)
        core.onFrame(idr, 4)
        assertEquals(2, link.sent.size)
    }

    // --- «Выйти» в AA (VIDEO_FOCUS NATIVE) -> CarLife GO_TO_DESKTOP ---
    @Test
    fun exitRequestSendsGoToDesktopThenNativeOnHuPause() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        val keyframesBefore = peer.keyframes
        core.onAaNativeFocusRequest()
        assertEquals(1, link.desktops)
        assertEquals("AA сразу получает цикл NATIVE -> PROJECTED (Desay не шлёт PAUSE/START)", keyframesBefore + 1, peer.keyframes)
        assertEquals("NATIVE уходит в AA только когда ГУ реально вышел (PAUSE)", 0, peer.natives)
        core.onCarlifePause()
        assertEquals(1, peer.natives)
        assertTrue(logs.any { it.contains("«Выйти»") })
        core.onCarlifePause()                                 // повторный PAUSE: флаг уже снят
        assertEquals(1, peer.natives)
    }

    @Test
    fun huPauseWithoutExitRequestSendsNothingToAa() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        core.onCarlifePause()
        assertEquals(0, peer.natives)
        assertEquals(0, link.desktops)
    }

    @Test
    fun exitRequestWithoutCarlifeStartOrLinkIsIgnored() {
        val peer = FakePeer()
        core.peer = peer
        core.onAaNativeFocusRequest()                         // CarLife ещё не START
        core.onCarlifePause()
        assertEquals(0, link.desktops)
        assertEquals(0, peer.natives)
        val bare = AaBridgeCore({ _, _ -> }, { now })         // ссылки на CarLife нет вовсе
        bare.peer = peer
        bare.onAaNativeFocusRequest()
        bare.onCarlifePause()
        assertEquals(0, peer.natives)
    }

    @Test
    fun exitPendingIsClearedByCloseAndReattach() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        core.onAaNativeFocusRequest()
        core.onCarlifeClosed(link)
        core.attach(link)
        core.onCarlifeStart()
        core.onCarlifePause()
        assertEquals(0, peer.natives)
    }

    @Test
    fun returnToCarlifeAfterExitRequestsKeyFrameCycle() {
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeStart()
        core.onCodecConfig(cfg)
        core.onFrame(idr, 1)
        core.onAaNativeFocusRequest()
        core.onCarlifePause()
        assertEquals(1, peer.natives)
        val before = peer.keyframes
        core.onCarlifeStart()                                 // ГУ вернулся: цикл NATIVE -> PROJECTED
        assertEquals(before + 1, peer.keyframes)
    }
}

/** Виртуальное время для таймеров аудио ядра: advance(ms) выполняет задачи по порядку, отменённые пропускает. */
class FakeTimers {
    private class T(val at: Long, val task: Runnable) : MicTimerHandle {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }

    private val list = ArrayList<T>()
    var now = 0L
        private set

    fun schedule(delayMs: Long, task: Runnable): MicTimerHandle = T(now + delayMs, task).also { list.add(it) }

    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val t = list.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
            list.remove(t)
            now = t.at
            t.task.run()
        }
        now = end
    }
}

class AaBridgeAudioTest {
    private val link = FakeLink()
    private val logs = CopyOnWriteArrayList<String>()
    private val timers = FakeTimers()
    private val core = AaBridgeCore({ l, _ -> logs.add(l) }, audioSchedule = timers::schedule).also { it.attach(link) }
    private val MEDIA = AapMessages.CH_AUDIO_MEDIA
    private val SPEECH = AapMessages.CH_AUDIO_SPEECH
    private fun pcm(n: Int, v: Int = 1) = ByteArray(n) { (v + it % 5).toByte() }
    private fun hexs(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun mediaInitThenDataOnMediaChannelWithExactCodesAndBodies() {
        core.onCarlifeStart()
        val d = pcm(3840)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, d, 1)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, d, 2)
        assertEquals(3, link.audio.size)
        val init = link.audio[0]
        assertEquals(3, init.chan)
        assertEquals(0x00030001, init.type)
        assertEquals("0880f70210021810", hexs(init.payload))           // CarlifeMusicInit{48000, 2, 16}
        assertEquals(3, link.audio[1].chan)
        assertEquals(0x00030006, link.audio[1].type)
        assertArrayEquals(d, link.audio[1].payload)
        assertEquals(0x00030006, link.audio[2].type)                   // второй пакет без повторного INIT
    }

    @Test
    fun speechGoesToTtsChannel() {
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(640), 1)
        assertEquals(listOf(4 to 0x00040001, 4 to 0x00040003), link.audio.map { it.chan to it.type })
        assertEquals("08807d10011810", hexs(link.audio[0].payload))     // CarlifeTTSInit{16000, 1, 16}
    }

    @Test
    fun bigChunkIsSplitAtTenKib() {
        core.onCarlifeStart()
        val d = pcm(25000)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, d, 1)
        val data = link.audio.filter { it.type == 0x00030006 }
        assertEquals(listOf(10240, 10240, 4520), data.map { it.payload.size })
        assertArrayEquals(d, data.fold(ByteArray(0)) { a, c -> a + c.payload })
    }

    @Test
    fun mediaAndGuidanceSwitchEachInitOnceAndStopIndependently() {
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(100), 2)       // подсказка поверх музыки
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 3)
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(100), 4)
        core.onAudioStop(AapMessages.CH_AUDIO_SPEECH)                    // подсказка кончилась
        timers.advance(1500)                                             // TTS_STOP уходит с задержкой 1500 мс
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 5)        // музыка продолжается без нового INIT
        assertEquals(
            listOf(
                3 to 0x30001, 3 to 0x30006, 4 to 0x40001, 4 to 0x40003, 3 to 0x30006, 4 to 0x40003,
                4 to 0x40002, 3 to 0x30006,
            ),
            link.audio.map { it.chan to it.type }
        )
        assertEquals(0, link.audio[6].payload.size)                      // STOP без тела
        // следующая подсказка: новый INIT
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(100), 6)
        assertEquals(listOf(4 to 0x40001, 4 to 0x40003), link.audio.takeLast(2).map { it.chan to it.type })
    }

    @Test
    fun mediaStopSendsPauseOnlyIfStreamWasInitedAndNextDataReinits() {
        core.onCarlifeStart()
        core.onAudioStop(AapMessages.CH_AUDIO_MEDIA)                     // ничего не играло: ничего не шлём
        assertEquals(0, link.audio.size)
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onAudioStop(AapMessages.CH_AUDIO_MEDIA)                     // PAUSE 0x30003, не STOP
        core.onAudioStop(AapMessages.CH_AUDIO_MEDIA)                     // повторный PAUSE не дублируется
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        assertEquals(listOf(0x30001, 0x30006, 0x30003, 0x30001, 0x30006), link.audio.map { it.type })
        assertEquals(listOf(3 to 1, 3 to 0, 3 to 1), link.modules.toList())   // MUSIC=1 на INIT, 0 на PAUSE
    }

    @Test
    fun aaSessionEndStopsOpenStreams() {
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onAudioData(AapMessages.CH_AUDIO_SPEECH, pcm(100), 2)
        core.onAaEnded(established = true)
        assertEquals(setOf(3 to 0x30002, 4 to 0x40002), link.audio.takeLast(2).map { it.chan to it.type }.toSet())
    }

    @Test
    fun carlifeStartAfterPauseResetsInit() {
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onCarlifePause()
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        assertEquals(listOf(0x30001, 0x30006, 0x30001, 0x30006), link.audio.map { it.type })
    }

    @Test
    fun carlifeStartWithoutPauseKeepsAudio() {
        // Desay G6S: START на каждое нажатие NAVI/MEDIA без PAUSE - повторный INIT рвал звук
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        assertEquals(listOf(0x30001, 0x30006, 0x30006), link.audio.map { it.type })
        assertEquals(listOf(3 to 1), link.modules.toList())
    }

    @Test
    fun startAfterReattachResetsInit() {
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        val link2 = FakeLink()
        core.attach(link2)
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        assertEquals(listOf(0x30001, 0x30006), link2.audio.map { it.type })
    }

    @Test
    fun systemChannelAndNotStartedAreDropped() {
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)          // CarLife ещё не START
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_SYSTEM, pcm(100), 2)
        assertEquals(0, link.audio.size)
        assertEquals(2L, core.audioDropped)
    }

    @Test
    fun bluetoothModeAndUiSwitchStopUsbAudio() {
        assertTrue(core.audioViaUsb)
        core.onHuFeatureConfig(0)
        assertTrue(core.audioViaUsb)
        core.onHuFeatureConfig(1)
        assertFalse(core.audioViaUsb)
        assertTrue(logs.any { it.contains("AUDIO_TRANSMISSION_MODE=1") && it.contains("Bluetooth") })
        core.onCarlifeStart()
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        assertEquals(0, link.audio.size)
        val c2 = AaBridgeCore({ _, _ -> }).also { it.attach(FakeLink()) }
        c2.usbAudioEnabled = false
        assertFalse(c2.audioViaUsb)
    }

    @Test
    fun rejectedInitIsRetriedOnNextPacket() {
        core.onCarlifeStart()
        link.accept = false
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 1)
        link.accept = true
        core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm(100), 2)
        assertEquals(listOf(0x30001, 0x30006), link.audio.map { it.type })
    }

    // ---------------------------------------------------------------- NAVI, debounce TTS, очередь MEDIA, голос
    @Test
    fun naviStatusGoesBeforeFirstTtsInitOnlyOncePerSession() {
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        core.onAudioData(SPEECH, pcm(640), 2)
        assertEquals(listOf("M2=1", "A4:0x40001", "A4:0x40003", "A4:0x40003"), link.events.toList())
        core.onAudioStop(SPEECH)
        timers.advance(1500)
        core.onAudioData(SPEECH, pcm(640), 3)                            // новый INIT, но NAVI уже держится
        assertEquals(1, link.modules.count { it == 2 to 1 })
    }

    @Test
    fun naviResetByStopAllAndByCarlifeStart() {
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        core.onAaEnded(established = true)
        assertEquals(listOf("M2=1", "A4:0x40001", "A4:0x40003", "A4:0x40002", "M2=0"), link.events.toList())
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 2)
        assertEquals(2, link.modules.count { it == 2 to 1 })             // флаг сброшен: в новой сессии снова NAVI=1
        core.onCarlifeClosed(link)
        assertEquals(1, link.modules.count { it == 2 to 0 })             // закрытие сессии NAVI=0 не шлёт (USB мёртв)
    }

    @Test
    fun vwCns3TtsGetsSilencePrerollAfterInitOnlyOnRealInit() {
        core.onHuInfo("Volkswagen", "CNS3")
        core.onHuStatistic("20082122")                                   // h222: шипение в начале подсказки
        core.onCarlifeStart()
        val speech = pcm(2048, 7)
        core.onAudioStart(SPEECH)
        core.onAudioData(SPEECH, speech, 1)
        val tts = link.audio.filter { it.chan == 4 }
        assertEquals(0x40001, tts[0].type)
        val pre = tts.drop(1).dropLast(1)
        assertEquals(listOf(4096, 4096, 4096, 512), pre.map { it.payload.size })  // 400 мс 16 кГц моно = 12800 Б кусками Baidu
        assertTrue(pre.all { p -> p.type == 0x40003 && p.payload.all { it == 0.toByte() } })
        assertArrayEquals(speech, tts.last().payload)                    // речь после тишины, без изменений
        // STOP отменён новым START: INIT не повторяется, пре-ролла тоже нет
        core.onAudioStop(SPEECH)
        timers.advance(1000)
        core.onAudioStart(SPEECH)
        core.onAudioData(SPEECH, speech, 2)
        assertEquals(tts.size + 1, link.audio.count { it.chan == 4 })
        // после настоящего TTS_STOP следующий INIT снова с пре-роллом
        core.onAudioStop(SPEECH)
        timers.advance(1500)
        val before = link.audio.size
        core.onAudioData(SPEECH, speech, 3)
        assertEquals(listOf(0x40001) + List(5) { 0x40003 }, link.audio.drop(before).map { it.type })
    }

    @Test
    fun ttsWithoutVwFlagHasNoPreroll() {
        core.onHuInfo("", "")
        core.onHuStatistic("20622116")                                   // Desay: другая ГУ
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        assertEquals(listOf(0x40001, 0x40003), link.audio.map { it.type })
    }

    @Test
    fun ttsStartStopStartWithinOneSecondGivesOneInitAndNoStopBetween() {
        core.onCarlifeStart()
        core.onAudioStart(SPEECH)
        core.onAudioData(SPEECH, pcm(640), 1)
        core.onAudioStop(SPEECH)
        timers.advance(1000)
        core.onAudioStart(SPEECH)                                        // SPEECH START отменяет отложенный STOP
        core.onAudioData(SPEECH, pcm(640), 2)
        timers.advance(5000)
        assertEquals(listOf(0x40001, 0x40003, 0x40003), link.audio.map { it.type })
        // данные ch4 тоже отменяют таймер
        core.onAudioStop(SPEECH)
        timers.advance(1400)
        core.onAudioData(SPEECH, pcm(640), 3)
        timers.advance(5000)
        assertEquals(0, link.audio.count { it.type == 0x40002 })
        // и настоящий STOP наступает ровно через 1500 мс
        core.onAudioStop(SPEECH)
        timers.advance(1499)
        assertEquals(0, link.audio.count { it.type == 0x40002 })
        timers.advance(1)
        assertEquals(1, link.audio.count { it.type == 0x40002 })
    }

    @Test
    fun stopAllAudioSendsTtsStopImmediatelyAndCancelsTimer() {
        core.onCarlifeStart()
        core.onAudioData(SPEECH, pcm(640), 1)
        core.onAudioStop(SPEECH)
        core.onAaEnded(established = true)
        assertEquals(1, link.audio.count { it.type == 0x40002 })
        timers.advance(5000)
        assertEquals(1, link.audio.count { it.type == 0x40002 })         // таймер отменён: второго STOP нет
    }

    @Test
    fun mediaStopDuringTtsWaitsForTtsStopPlus300() {
        core.onCarlifeStart()
        core.onAudioData(MEDIA, pcm(100), 1)
        core.onAudioData(SPEECH, pcm(100), 2)
        core.onAudioStop(MEDIA)                                          // PAUSE откладывается
        assertEquals(0, link.audio.count { it.type == 0x30003 })
        core.onAudioStop(SPEECH)
        timers.advance(1500)                                             // фактический TTS_STOP
        assertEquals(1, link.audio.count { it.type == 0x40002 })
        timers.advance(299)
        assertEquals(0, link.audio.count { it.type == 0x30003 })
        timers.advance(1)
        assertEquals(
            listOf("A3:0x30001", "M3=1", "A3:0x30006", "M2=1", "A4:0x40001", "A4:0x40003", "A4:0x40002", "A3:0x30003", "M3=0"),
            link.events.toList()
        )
    }

    @Test
    fun mediaDataIsDroppedWhileTtsBusyAndMediaNotInitedThenReinitsAfterRelease() {
        core.onCarlifeStart()
        core.onAudioData(MEDIA, pcm(100), 1)
        core.onAudioStop(MEDIA)                                          // PAUSE сразу: TTS не идёт
        core.onAudioData(SPEECH, pcm(100), 2)
        for (i in 0 until 1001) core.onAudioData(MEDIA, pcm(100), 10L + i)   // MEDIA не инициализирован, TTS занят
        assertEquals(listOf(0x30001, 0x30006, 0x30003, 0x40001, 0x40003), link.audio.map { it.type })
        assertEquals(3, logs.count { it.contains("MEDIA данные отброшены") })   // #1, #500, #1000
        core.onAudioStop(SPEECH)
        timers.advance(1500 + 299)
        core.onAudioData(MEDIA, pcm(100), 3)                             // ещё в окне +300
        assertEquals(1, link.audio.count { it.type == 0x30001 })         // повторного INIT пока нет
        timers.advance(1)
        core.onAudioData(MEDIA, pcm(100), 4)
        assertEquals(listOf(0x40002, 0x30001, 0x30006), link.audio.takeLast(3).map { it.type })
    }

    @Test
    fun mediaDataKeepsFlowingWhenInitedAndTtsPlays() {
        core.onCarlifeStart()
        core.onAudioData(MEDIA, pcm(100), 1)
        core.onAudioData(SPEECH, pcm(100), 2)
        core.onAudioData(MEDIA, pcm(100), 3)                             // ГУ сам дакает
        assertEquals(listOf(0x30001, 0x30006, 0x40001, 0x40003, 0x30006), link.audio.map { it.type })
    }

    @Test
    fun voiceStartPausesMediaStopsForwardingAndBurstExitsOnce() {
        core.onCarlifeStart()
        core.onAudioData(MEDIA, pcm(100), 1)
        core.onVoiceSession(1)
        assertEquals(listOf("A3:0x30001", "M3=1", "A3:0x30006", "A3:0x30003", "M3=0"), link.events.toList())
        core.onAudioData(MEDIA, pcm(100), 2)                             // идёт голос: MEDIA_DATA не пересылаем
        assertEquals(3, link.audio.size)
        // пачка 2/2/1/2/2: выход один, через 500 мс после последней двойки
        core.onVoiceSession(2)
        timers.advance(200)
        core.onVoiceSession(2)
        timers.advance(200)
        core.onVoiceSession(1)                                           // выход отменён
        timers.advance(1000)
        core.onAudioData(MEDIA, pcm(100), 3)
        assertEquals(3, link.audio.size)                                 // голос всё ещё идёт
        core.onVoiceSession(2)
        timers.advance(100)
        core.onVoiceSession(2)
        timers.advance(399)
        core.onAudioData(MEDIA, pcm(100), 4)
        assertEquals(3, link.audio.size)
        timers.advance(1)
        assertEquals(1, logs.count { it.contains("голосовая сессия: выход, ") })
        core.onAudioData(MEDIA, pcm(100), 5)                             // после выхода: INIT на первых данных
        assertEquals(listOf(0x30001, 0x30006, 0x30003, 0x30001, 0x30006), link.audio.map { it.type })
        timers.advance(5000)
        assertEquals(1, logs.count { it.contains("голосовая сессия: выход, ") })
    }

    @Test
    fun voicePauseWaitsForTtsSerialization() {
        core.onCarlifeStart()
        core.onAudioData(MEDIA, pcm(100), 1)
        core.onAudioData(SPEECH, pcm(100), 2)
        core.onVoiceSession(1)
        assertEquals(0, link.audio.count { it.type == 0x30003 })
        core.onAudioStop(SPEECH)
        timers.advance(1799)
        assertEquals(0, link.audio.count { it.type == 0x30003 })
        timers.advance(1)
        assertEquals(1, link.audio.count { it.type == 0x30003 })
    }

    @Test
    fun ttsFirstPacketsLogPeakAndRms() {
        core.onCarlifeStart()
        val pcm = ByteArray(8)                                           // сэмплы 0, 1000, -2000, 0
        pcm[2] = (1000 and 0xFF).toByte(); pcm[3] = (1000 shr 8).toByte()
        pcm[4] = ((-2000) and 0xFF).toByte(); pcm[5] = ((-2000) shr 8).toByte()
        for (i in 1..5) core.onAudioData(SPEECH, pcm, i.toLong())
        val diag = logs.filter { it.contains("BRIDGE TTS пакет") }
        assertEquals(3, diag.size)
        assertTrue(diag[0].contains("peak=2000") && diag[0].contains("rms=1118"))
        core.onAudioData(SPEECH, ByteArray(8), 6L)
        assertEquals(3, logs.count { it.contains("BRIDGE TTS пакет") })
        // новая TTS-сессия: снова три пакета, нули помечены
        core.onAudioStop(SPEECH)
        timers.advance(1500)
        for (i in 1..4) core.onAudioData(SPEECH, ByteArray(8), i.toLong())
        val diag2 = logs.filter { it.contains("BRIDGE TTS пакет") }.drop(3)
        assertEquals(3, diag2.size)
        assertTrue(diag2.all { it.contains("peak=0") && it.contains("ВСЕ НУЛИ") })
    }

    @Test
    fun mediaInitedFlagFollowsInitAndPause() {
        core.onCarlifeStart()
        assertFalse(core.isMediaInited)
        core.onAudioData(MEDIA, pcm(100), 1)
        assertTrue(core.isMediaInited)
        core.onAudioStop(MEDIA)
        assertFalse(core.isMediaInited)
    }

    @Test
    fun flagsOffRestoreImmediateBehaviour() {
        try {
            AaBridgeCore.TTS_DEBOUNCE_ENABLED = false
            AaBridgeCore.NAVI_STATUS_ENABLED = false
            AaBridgeCore.MODULE_MUSIC_ENABLED = false
            AaBridgeCore.MEDIA_PAUSE_ENABLED = false
            AaBridgeCore.VOICE_SESSION_ENABLED = false
            core.onCarlifeStart()
            core.onAudioData(MEDIA, pcm(100), 1)
            core.onAudioData(SPEECH, pcm(100), 2)
            core.onAudioStop(MEDIA)                                      // без очереди и без PAUSE: сразу MEDIA_STOP
            core.onAudioStop(SPEECH)                                     // без debounce: сразу TTS_STOP
            core.onVoiceSession(1)
            assertEquals(listOf(0x30001, 0x30006, 0x40001, 0x40003, 0x30002, 0x40002), link.audio.map { it.type })
            assertEquals(0, link.modules.size)
        } finally {
            AaBridgeCore.TTS_DEBOUNCE_ENABLED = true
            AaBridgeCore.NAVI_STATUS_ENABLED = true
            AaBridgeCore.MODULE_MUSIC_ENABLED = true
            AaBridgeCore.MEDIA_PAUSE_ENABLED = true
            AaBridgeCore.VOICE_SESSION_ENABLED = true
        }
    }
}

/** AA-сессия моста против скриптового телефона по loopback: видео -> CarLife-ссылка, тачи CarLife -> телефон. */
class AaBridgeIntegrationTest {
    @Test
    fun aaVideoReachesCarlifeAndCarlifeTouchReachesPhoneThenPlaceholderOnEnd() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val link = FakeLink()
        val logs = CopyOnWriteArrayList<String>()
        val core = AaBridgeCore({ l, _ -> logs.add(l) }).also { it.attach(link) }
        core.onCarlifeStart()
        val sessionRef = AtomicReference<AapSession>()
        val sessionCreated = CountDownLatch(1)
        val creds = AapCerts.load(TestAssets.opener, "jvc")
        val connector = AaConnector(
            core, creds, { l, _ -> logs.add(l) },
            options = { AapOptions(port = server.localPort, preVideoTimeoutMs = 10_000) },
            retryMs = 200,
            sessionFactory = { sink, o, lg, end ->
                AapSession(creds, sink, o, lg, onEnd = end).also {
                    if (sessionRef.compareAndSet(null, it)) sessionCreated.countDown()
                }
            },
        )
        connector.start()
        val sock = server.accept()
        assertTrue(sessionCreated.await(5, TimeUnit.SECONDS))
        val phone = FakePhone(sock, "jvc", false, "hu_bye", externalTouch = true)
        phone.session = sessionRef.get()
        phone.start()
        // ждём, пока видео дойдёт до CarLife-ссылки
        val t0 = System.currentTimeMillis()
        while (link.sent.size < 2 && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(20)
        assertEquals("видео не дошло: " + logs.joinToString("\n"), 2, link.sent.size)
        assertArrayEquals(FakePhone.SPS_PPS + FakePhone.IDR, link.sent[0])           // SPS+PPS+IDR склеены
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(link.sent[0]))
        assertArrayEquals(FakePhone.SMALL, link.sent[1])
        assertEquals(1, link.hides)
        // тачи ГУ -> AA-телефон
        core.onCarlifeTouch(0, 40, 675)
        core.onCarlifeTouch(1, 40, 675)
        // телефон после двух тачей сам завершает сессию (requestStop -> ByeBye)
        phone.join(20_000)
        assertEquals(null, phone.error)
        assertEquals(listOf(Triple(0, 40, 675), Triple(1, 40, 675)), phone.touches.toList())
        assertTrue(phone.touchTs.all { it > 0 })
        // AA закончилась: заглушка с текстом, USB (link) не закрывается
        val t1 = System.currentTimeMillis()
        while (link.texts.last() != AaBridgeCore.TEXT_AA_LOST && System.currentTimeMillis() - t1 < 10_000) Thread.sleep(20)
        assertEquals(AaBridgeCore.TEXT_AA_LOST, link.texts.last())
        // сервер пропал: connection refused, коннектор повторяет подключение
        server.close()
        val a0 = connector.attempts
        val t2 = System.currentTimeMillis()
        while (connector.attempts < a0 + 3 && System.currentTimeMillis() - t2 < 10_000) Thread.sleep(20)
        assertTrue("повторов подключения нет: ${connector.attempts} (было $a0)", connector.attempts >= a0 + 3)
        assertEquals("после отказов текст остаётся", AaBridgeCore.TEXT_AA_LOST, link.texts.last())
        val stopped = CountDownLatch(1)
        connector.stop { stopped.countDown() }
        assertTrue("коннектор не остановился", stopped.await(10, TimeUnit.SECONDS))
        assertFalse(logs.isEmpty())
    }

    @Test
    fun refusedConnectionRetriesAndShowsStartServerText() {
        val port = ServerSocket(0).use { it.localPort }        // порт свободен: connection refused
        val link = FakeLink()
        val logs = CopyOnWriteArrayList<String>()
        val core = AaBridgeCore({ l, _ -> logs.add(l) }).also { it.attach(link) }
        val creds = AapCerts.load(TestAssets.opener, "jvc")
        val connector = AaConnector(core, creds, { l, _ -> logs.add(l) }, options = { AapOptions(port = port) }, retryMs = 100)
        connector.start()
        val t0 = System.currentTimeMillis()
        while (connector.attempts < 4 && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(20)
        assertTrue(connector.attempts >= 4)
        assertEquals(listOf(AaBridgeCore.TEXT_START_SERVER), link.texts.toList())
        assertEquals(AaBridgeCore.Source.PLACEHOLDER, core.activeSource)
        assertEquals("в логе одна запись про недоступность", 1, logs.count { it.contains("BRIDGE сервер ГУ Android Auto недоступен") })
        val stopped = CountDownLatch(1)
        connector.stop { stopped.countDown() }
        assertTrue(stopped.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun fullAudioSinksAreAnnouncedAndPcmReachesCarlifeChannels() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val link = FakeLink()
        val logs = CopyOnWriteArrayList<String>()
        val core = AaBridgeCore({ l, _ -> logs.add(l) }).also { it.attach(link) }
        core.onCarlifeStart()
        val sessionRef = AtomicReference<AapSession>()
        val created = CountDownLatch(1)
        val creds = AapCerts.load(TestAssets.opener, "jvc")
        val connector = AaConnector(
            core, creds, { l, _ -> logs.add(l) },
            options = { AapOptions(port = server.localPort, sdProvider = { AapSdOptions(fullAudio = core.audioViaUsb) }) },
            retryMs = 200,
            sessionFactory = { sink, o, lg, end ->
                AapSession(creds, sink, o, lg, onEnd = end).also { if (sessionRef.compareAndSet(null, it)) created.countDown() }
            },
        )
        connector.start()
        val sock = server.accept()
        assertTrue(created.await(5, TimeUnit.SECONDS))
        val phone = FakePhone(sock, "jvc", false, "hu_bye", externalTouch = true, fullAudio = true)
        phone.session = sessionRef.get()
        phone.start()
        val t0 = System.currentTimeMillis()
        while (link.audio.size < 4 && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(20)
        // телефон ждёт два тача, потом сам завершает сессию
        core.onCarlifeTouch(0, 1, 1)
        core.onCarlifeTouch(1, 1, 1)
        phone.join(20_000)
        assertEquals("ошибка телефона (SDR с аудио / ack): ${phone.error}\n" + logs.joinToString("\n"), null, phone.error)
        // порядок как у телефона: MEDIA init+data, SPEECH init+data, MEDIA stop (-> CarLife PAUSE, TTS идёт: пауза ждёт); SYSTEM (ch5) не пересылается
        val seq = link.audio.map { it.chan to it.type }
        assertEquals(listOf(3 to 0x30001, 3 to 0x30006, 4 to 0x40001, 4 to 0x40003), seq.take(4))
        val t1 = System.currentTimeMillis()
        while (link.audio.size < 6 && System.currentTimeMillis() - t1 < 10_000) Thread.sleep(20)     // конец AA: stopAllAudio -> MEDIA_STOP + TTS_STOP
        assertEquals(setOf(3 to 0x30002, 4 to 0x40002), link.audio.drop(4).take(2).map { it.chan to it.type }.toSet())
        assertEquals(3840, link.audio[1].payload.size)
        assertEquals(640, link.audio[3].payload.size)
        assertEquals("0880f70210021810", link.audio[0].payload.joinToString("") { "%02x".format(it) })
        val stopped = CountDownLatch(1)
        server.close()
        connector.stop { stopped.countDown() }
        assertTrue(stopped.await(15, TimeUnit.SECONDS))
    }
}
