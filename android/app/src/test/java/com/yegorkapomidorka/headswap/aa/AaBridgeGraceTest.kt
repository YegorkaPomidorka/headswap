package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.yegorkapomidorka.headswap.aa.AapSessionTest.Phone as FakePhone

class FakeLifecycle(private val stopDelayMs: Long = 0) : AaLifecycle {
    val starts = AtomicInteger()
    val stops = AtomicInteger()
    @Volatile var stopDoneAt = 0L
    override fun start() {
        starts.incrementAndGet()
    }

    override fun stop(onDone: () -> Unit) {
        stops.incrementAndGet()
        Thread {
            Thread.sleep(stopDelayMs)
            stopDoneAt = System.nanoTime()
            onDone()
        }.start()
    }
}

class BridgeHostTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val logs = CopyOnWriteArrayList<String>()
    private fun core() = AaBridgeCore({ l, _ -> logs.add(l) })

    private fun waitFor(ms: Long = 3000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < ms) Thread.sleep(10)
    }

    @Test
    fun failedOneShotSourceCannotBeReusedBeforeItsFailureCallbackReachesTheHost() {
        var failed = false
        var starts = 0
        var stops = 0
        var stopAck: (() -> Unit)? = null
        val source = object : AaLifecycle, TerminalSource, DesktopNavigation, DesktopPowerControl {
            override val isTerminated get() = failed
            override fun start() { starts++ }
            override fun stop(onDone: () -> Unit) { stops++; stopAck = onDone }
            override fun showDesktop() = error("Failed source must not receive navigation")
            override fun setPhoneScreenOff(off: Boolean) = error("Failed source must not receive panel control")
        }
        val host = BridgeHost(core(), source, scheduler, { _, _ -> })
        try {
            assertTrue(host.onUsbSessionStarted())
            failed = true // Binder thread reports death, main-looper callback has not run yet.
            assertTrue(host.isDead)
            host.onUsbSessionClosed(immediate = false)
            assertFalse("a fast USB reconnect must create a different host", host.onUsbSessionStarted())
            assertEquals(1, starts)
            assertFalse(host.showDesktop())
            assertFalse(host.setPhoneScreenOff(true))
            host.shutdown("failure callback")
            host.shutdown("duplicate callback")
            assertEquals(1, stops)
            assertFalse("a terminal source is not proof of Surface disposal", host.isTornDown)
            checkNotNull(stopAck).invoke()
            assertTrue(host.isTornDown)
        } finally { scheduler.shutdownNow() }
    }

    @Test
    fun previousStopTimeoutNeverAuthorizesAReplacementSource() {
        var oldStopAck: (() -> Unit)? = null
        val oldSource = object : AaLifecycle {
            override fun start() = Unit
            override fun stop(onDone: () -> Unit) { oldStopAck = onDone }
        }
        val old = BridgeHost(core(), oldSource, scheduler, { _, _ -> })
        val nextSource = FakeLifecycle()
        val done = CountDownLatch(1)
        val next = BridgeHost(core(), nextSource, scheduler, { _, _ -> }, previous = old,
            onTornDown = { done.countDown() }, previousStopTimeoutMs = 40)
        try {
            old.onUsbSessionStarted()
            old.shutdown("producer has not confirmed cleanup")
            next.onUsbSessionStarted()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertTrue(next.isDead)
            assertEquals("timeout cannot substitute for producer closure", 0, nextSource.starts.get())
            assertEquals(1, nextSource.stops.get())
            checkNotNull(oldStopAck).invoke()
            assertEquals("late acknowledgement must not revive the retired replacement", 0, nextSource.starts.get())
        } finally { scheduler.shutdownNow() }
    }

    @Test
    fun desktopNavigationNeverReachesAnUnstartedDetachedOrRetiredSource() {
        var calls = 0
        var starts = 0
        var sourceReady = false
        val source = object : AaLifecycle, DesktopNavigation {
            override fun start() { starts++; sourceReady = true }
            override fun stop(onDone: () -> Unit) { sourceReady = false; onDone() }
            override fun showDesktop(): Boolean { calls++; return sourceReady }
        }
        val host = BridgeHost(core(), source, scheduler, { _, _ -> }, graceMs = 10_000)
        try {
            assertFalse("navigation must not create a source", host.showDesktop())
            assertEquals(0, calls)
            host.onUsbSessionStarted()
            assertTrue(host.showDesktop())
            assertEquals(1, calls)
            host.onUsbSessionClosed(immediate = false)
            repeat(3) { assertFalse("no navigation during USB grace", host.showDesktop()) }
            assertEquals(1, calls)
            host.onUsbSessionStarted()
            assertTrue(host.showDesktop())
            assertEquals("USB reconnect reuses the source", 1, starts)
            sourceReady = false
            assertFalse("source readiness refusal must propagate", host.showDesktop())
            assertEquals(3, calls)
            host.shutdown("test")
            assertFalse("navigation must not revive a retired source", host.showDesktop())
            assertEquals(3, calls)
        } finally {
            host.shutdown("test cleanup")
            scheduler.shutdownNow()
        }
    }

    @Test
    fun desktopNavigationDoesNotFallBackToAnOrdinarySource() {
        val source = FakeLifecycle()
        val host = BridgeHost(core(), source, scheduler, { _, _ -> })
        try {
            host.onUsbSessionStarted()
            assertFalse(host.showDesktop())
            assertEquals(1, source.starts.get())
            assertEquals(0, source.stops.get())
        } finally {
            host.shutdown("test cleanup")
            scheduler.shutdownNow()
        }
    }

    @Test
    fun panelCommandsOnlyReachAnActiveCapableSource() {
        val requests = mutableListOf<Boolean>()
        val source = object : AaLifecycle, DesktopPowerControl {
            var ready = false
            override fun start() { ready = true }
            override fun stop(onDone: () -> Unit) { ready = false; onDone() }
            override fun setPhoneScreenOff(off: Boolean): Boolean {
                if (!ready) return false
                requests += off
                return true
            }
        }
        val host = BridgeHost(core(), source, scheduler, { _, _ -> }, graceMs = 10_000)
        try {
            assertFalse("a panel command cannot start a source", host.setPhoneScreenOff(true))
            host.onUsbSessionStarted()
            assertTrue(host.setPhoneScreenOff(true))
            host.onUsbSessionClosed(immediate = false)
            assertFalse("a detached session cannot control the phone", host.setPhoneScreenOff(false))
            host.onUsbSessionStarted()
            assertTrue(host.setPhoneScreenOff(false))
            host.shutdown("test")
            assertFalse("a retired source cannot control the phone", host.setPhoneScreenOff(true))
            assertEquals(listOf(true, false), requests)
        } finally {
            host.shutdown("test cleanup")
            scheduler.shutdownNow()
        }
    }

    @Test
    fun panelCommandsDoNotFallBackToAnOrdinarySource() {
        val source = FakeLifecycle()
        val host = BridgeHost(core(), source, scheduler, { _, _ -> })
        try {
            host.onUsbSessionStarted()
            assertFalse(host.setPhoneScreenOff(true))
            assertEquals(1, source.starts.get())
            assertEquals(0, source.stops.get())
        } finally {
            host.shutdown("test cleanup")
            scheduler.shutdownNow()
        }
    }

    @Test
    fun usbBackInsideGraceKeepsAaAndDoesNotRestartIt() {
        val lc = FakeLifecycle()
        val host = BridgeHost(core(), lc, scheduler, { l, _ -> logs.add(l) }, graceMs = 400)
        assertTrue(host.onUsbSessionStarted())
        host.onUsbSessionClosed(immediate = false)
        assertTrue(host.isInGrace)
        Thread.sleep(150)
        assertTrue(host.onUsbSessionStarted())            // вернулся в окно
        assertFalse(host.isInGrace)
        Thread.sleep(600)                                  // таймер отменён: не сработает
        assertEquals(1, lc.starts.get())
        assertEquals(0, lc.stops.get())
        assertFalse(host.isDead)
    }

    @Test
    fun usbNotBackClosesAaAfterGrace() {
        val lc = FakeLifecycle()
        val down = CountDownLatch(1)
        val host = BridgeHost(core(), lc, scheduler, { l, _ -> logs.add(l) }, graceMs = 300, onTornDown = { down.countDown() })
        host.onUsbSessionStarted()
        host.onUsbSessionClosed(immediate = false)
        Thread.sleep(100)
        assertEquals("до истечения grace AA не закрывается", 0, lc.stops.get())
        assertTrue(down.await(3, TimeUnit.SECONDS))
        assertEquals(1, lc.stops.get())
        assertTrue(host.isDead && host.isTornDown)
        assertFalse("мёртвый хост не принимает новую сессию", host.onUsbSessionStarted())
    }

    @Test
    fun stopButtonClosesAaImmediately() {
        val lc = FakeLifecycle()
        val host = BridgeHost(core(), lc, scheduler, { l, _ -> logs.add(l) }, graceMs = 10_000)
        host.onUsbSessionStarted()
        host.onUsbSessionClosed(immediate = true)
        assertEquals(1, lc.stops.get())
    }

    @Test
    fun manyUsbFlapsNeverTouchAa() {
        val lc = FakeLifecycle()
        val host = BridgeHost(core(), lc, scheduler, { l, _ -> logs.add(l) }, graceMs = 300)
        repeat(62) {
            assertTrue(host.onUsbSessionStarted())
            host.onUsbSessionClosed(immediate = false)
            Thread.sleep(15)
        }
        assertTrue(host.onUsbSessionStarted())
        assertEquals(1, lc.starts.get())
        assertEquals(0, lc.stops.get())
        host.onUsbSessionClosed(immediate = false)
        waitFor { lc.stops.get() == 1 }
        assertEquals(1, lc.stops.get())
    }

    @Test
    fun shutdownBeforeAnyUsbSessionNeverStartsAa() {
        val lc = FakeLifecycle()
        val down = CountDownLatch(1)
        val host = BridgeHost(core(), lc, scheduler, { l, _ -> logs.add(l) }, onTornDown = { down.countDown() })
        host.shutdown("тест")
        assertTrue(down.await(1, TimeUnit.SECONDS))
        assertEquals(0, lc.starts.get())
        assertEquals(0, lc.stops.get())
    }

    @Test
    fun newHostDoesNotConnectUntilPreviousAaIsFullyClosed() {
        val lc1 = FakeLifecycle(stopDelayMs = 500)
        val host1 = BridgeHost(core(), lc1, scheduler, { l, _ -> logs.add(l) })
        host1.onUsbSessionStarted()
        host1.shutdown("тест")                              // закрытие идёт 500 мс
        val lc2 = FakeLifecycle()
        val host2 = BridgeHost(core(), lc2, scheduler, { l, _ -> logs.add(l) }, previous = host1)
        assertTrue(host2.onUsbSessionStarted())
        Thread.sleep(150)
        assertEquals("сервер 5277 одноразовый: пока предыдущая AA не закрыта, нового подключения нет", 0, lc2.starts.get())
        waitFor { lc2.starts.get() == 1 }
        assertEquals(1, lc2.starts.get())
        assertTrue(host1.isTornDown)
    }
}

/** Живая AA-сессия переживает обрыв USB: тот же сеанс, keyframe-цикл, источник вернулся на AA; по истечении grace - ByeBye. */
class BridgeGraceIntegrationTest {
    @Test
    fun usbDropAndReturnInsideGraceReusesSameAaSessionAndRecoversByKeyframe() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val link1 = FakeLink()
        val logs = CopyOnWriteArrayList<String>()
        val core = AaBridgeCore({ l, _ -> logs.add(l) })
        val creds = AapCerts.load(TestAssets.opener, "jvc")
        val created = AtomicInteger()
        val sessionRef = AtomicReference<AapSession>()
        val connector = AaConnector(
            core, creds, { l, _ -> logs.add(l) },
            options = { AapOptions(port = server.localPort) },
            retryMs = 200,
            sessionFactory = { sink, o, lg, end ->
                created.incrementAndGet()
                AapSession(creds, sink, o, lg, onEnd = end).also { sessionRef.compareAndSet(null, it) }
            },
        )
        val down = CountDownLatch(1)
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val host = BridgeHost(core, connector, scheduler, { l, _ -> logs.add(l) }, graceMs = 1500, onTornDown = { down.countDown() })

        // USB-сессия 1: CarLife START, AA подключается и отдаёт config + IDR + P
        core.attach(link1)
        host.onUsbSessionStarted()
        core.onCarlifeStart()
        val sock = server.accept()
        val phone = FakePhone(sock, "jvc", false, "bridge_grace")
        phone.start()
        waitUntil { link1.sent.size >= 2 }
        assertEquals("видео до обрыва не дошло\n" + logs.joinToString("\n"), 2, link1.sent.size)
        assertArrayEquals(FakePhone.SPS_PPS + FakePhone.IDR, link1.sent[0])
        assertTrue(phone.ready.await(10, TimeUnit.SECONDS))
        val aaSession = sessionRef.get()

        // обрыв USB (EIO): CarLife закрыт, AA жива
        core.onCarlifeClosed(link1)
        host.onUsbSessionClosed(immediate = false)
        Thread.sleep(300)
        assertEquals("AA-сессия не пересоздавалась", 1, created.get())
        assertTrue("AA-сессия жива", aaSession.established)
        assertEquals(null, phone.error)

        // USB вернулся внутри grace: новая CarLife-сессия, заглушка "жду кадр", keyframe, IDR от той же AA
        val link2 = FakeLink()
        core.attach(link2)
        assertTrue(host.onUsbSessionStarted())
        assertEquals(listOf(AaBridgeCore.TEXT_WAIT_FRAME), link2.texts.toList())
        core.onCarlifeStart()
        waitUntil { link2.sent.isNotEmpty() }
        assertEquals("после keyframe-цикла IDR не дошёл\n" + logs.joinToString("\n"), 1, link2.sent.size)
        assertArrayEquals(FakePhone.SPS_PPS2 + FakePhone.IDR2, link2.sent[0])      // свежий SPS+PPS+IDR
        assertEquals(AaBridgeCore.Source.AA, core.activeSource)
        assertEquals(1, link2.hides)
        assertEquals("всё ещё та же AA-сессия", 1, created.get())
        assertTrue(aaSession === sessionRef.get() && aaSession.established)

        // USB не вернулся за grace: AA закрывается корректно (ByeBye), телефон это видит
        core.onCarlifeClosed(link2)
        host.onUsbSessionClosed(immediate = false)
        assertTrue("AA не закрылась после grace\n" + logs.joinToString("\n"), down.await(15, TimeUnit.SECONDS))
        phone.join(10_000)
        assertNull("телефон: ${phone.error}", phone.error)
        assertTrue("сокет закрыт штатно", phone.sawEof && !phone.reset)
        assertTrue(aaSession.exitOk)
        assertEquals(1, created.get())
        server.close()
    }

    private fun waitUntil(ms: Long = 20_000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < ms) Thread.sleep(10)
    }
}
