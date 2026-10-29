package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Ручная проба сервера ГУ на фейковом транспорте, пассивные счётчики отказов, классификация и настоящая проба по loopback. */
class AaServerCheckTest {
    private class FakeProbe(private val result: ProbeResult = ProbeResult.ALIVE) : ServerProbe {
        val calls = AtomicInteger()

        @Volatile var gate: CountDownLatch? = null
        @Volatile var entered = CountDownLatch(1)

        override fun probeOnce(): ProbeResult {
            calls.incrementAndGet()
            entered.countDown()
            gate?.await(10, TimeUnit.SECONDS)
            return result
        }
    }

    private val logs = CopyOnWriteArrayList<String>()
    @Volatile private var busy: ManualServerCheck.Refusal? = null

    private fun checker(probe: ServerProbe) = ManualServerCheck(probe, { busy }, { logs.add(it) })

    private class Done {
        val latch = CountDownLatch(1)
        val result = AtomicReference<ProbeResult?>()
        val detail = AtomicReference<String?>()
        val callback: (ProbeResult, String?) -> Unit = { r, d ->
            result.set(r)
            detail.set(d)
            latch.countDown()
        }
    }

    // --- ручная проба ---

    @Test
    fun manualProbeRunsOnceAndReportsResult() {
        val p = FakeProbe(ProbeResult.REFUSED)
        val done = Done()
        assertNull(checker(p).start(done.callback))
        assertTrue(done.latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, p.calls.get())
        assertEquals(ProbeResult.REFUSED, done.result.get())
        assertNull(done.detail.get())
        assertTrue(logs.toString(), logs.any { it.contains("ИТОГ: REFUSED") })
    }

    @Test
    fun manualProbeRefusedWhileBridgeOrUsbBusy() {
        val p = FakeProbe()
        val c = checker(p)
        busy = ManualServerCheck.Refusal.BRIDGE
        assertEquals(ManualServerCheck.Refusal.BRIDGE, c.start { _, _ -> })
        busy = ManualServerCheck.Refusal.USB
        assertEquals(ManualServerCheck.Refusal.USB, c.start { _, _ -> })
        assertEquals("проба не запускалась", 0, p.calls.get())
        busy = null
        val done = Done()
        assertNull(c.start(done.callback))
        assertTrue(done.latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, p.calls.get())
    }

    @Test
    fun secondManualProbeWhileRunningIsRefused() {
        val p = FakeProbe()
        p.gate = CountDownLatch(1)
        val c = checker(p)
        val done = Done()
        assertNull(c.start(done.callback))
        assertTrue(p.entered.await(5, TimeUnit.SECONDS))
        assertEquals(ManualServerCheck.Refusal.RUNNING, c.start { _, _ -> })
        p.gate!!.countDown()
        assertTrue(done.latch.await(5, TimeUnit.SECONDS))
        assertEquals("одна проба за раз", 1, p.calls.get())
    }

    @Test
    fun probeExceptionIsErrorWithDetail() {
        val done = Done()
        assertNull(checker({ throw IllegalStateException("boom") }).start(done.callback))
        assertTrue(done.latch.await(5, TimeUnit.SECONDS))
        assertEquals(ProbeResult.ERROR, done.result.get())
        assertEquals("IllegalStateException: boom", done.detail.get())
    }

    @Test
    fun bridgeConnectWaitsForProbeInFlight() {
        val p = FakeProbe()
        p.gate = CountDownLatch(1)
        val c = checker(p)
        assertNull(c.start { _, _ -> })
        assertTrue(p.entered.await(5, TimeUnit.SECONDS))
        val returned = AtomicLong(0)
        val t = Thread {
            assertTrue(c.awaitIdle(5000))
            returned.set(System.nanoTime())
        }
        t.start()
        Thread.sleep(150)
        assertEquals("мост не должен подключаться, пока попытка держит сервер", 0L, returned.get())
        val released = System.nanoTime()
        p.gate!!.countDown()
        t.join(5000)
        assertTrue(returned.get() >= released)
    }

    @Test
    fun awaitIdleReturnsAtOnceWhenNothingRunsAndTimesOutOtherwise() {
        val p = FakeProbe()
        val c = checker(p)
        assertTrue(c.awaitIdle(50))
        p.gate = CountDownLatch(1)
        assertNull(c.start { _, _ -> })
        assertTrue(p.entered.await(5, TimeUnit.SECONDS))
        assertFalse(c.awaitIdle(100))
        p.gate!!.countDown()
        assertTrue(c.awaitIdle(5000))
    }

    // --- пассивные счётчики отказов ---

    @Test
    fun refusedStreakCountsFromFirstRefusal() {
        val clock = AtomicLong(1_000L)
        val f = ServerFailures { clock.get() }
        assertEquals(0L, f.refusedForMs)
        f.onRefused()
        clock.addAndGet(6_000)
        f.onRefused()
        assertEquals("отсчёт от первого отказа, а не от последнего", 6_000L, f.refusedForMs)
        clock.addAndGet(10_000)
        f.onRefused()
        assertEquals(16_000L, f.refusedForMs)
    }

    @Test
    fun acceptedTcpBreaksRefusedStreak() {
        val clock = AtomicLong(1_000L)
        val f = ServerFailures { clock.get() }
        f.onRefused()
        clock.addAndGet(20_000)
        f.onTcpAccepted()
        assertEquals(0L, f.refusedForMs)
        f.onRefused()
        clock.addAndGet(1_000)
        assertEquals("новая серия начинается заново", 1_000L, f.refusedForMs)
    }

    @Test
    fun versionHangsAreConsecutive() {
        val f = ServerFailures { 0L }
        f.onNoVersionResponse()
        f.onNoVersionResponse()
        assertEquals(2, f.versionHangs)
        f.onRefused()
        assertEquals("отказ между зависаниями рвёт серию", 0, f.versionHangs)
        f.onNoVersionResponse()
        f.reset()
        assertEquals(0, f.versionHangs)
    }

    @Test
    fun establishedSessionClearsStreaksAndRemembersThatServerWorked() {
        val clock = AtomicLong(0L)
        val f = ServerFailures { clock.get() }
        assertFalse(f.worked)
        f.onRefused()
        f.onNoVersionResponse()
        f.onEstablished()
        assertEquals(0L, f.refusedForMs)
        assertEquals(0, f.versionHangs)
        assertTrue(f.worked)
        f.reset()
        assertTrue("reset не забывает, что сервер работал", f.worked)
    }

    @Test
    fun refusalRightAfterOwnSessionEndIsNormalForWorkedServer() {
        val clock = AtomicLong(0L)
        val f = ServerFailures { clock.get() }
        f.onEstablished()
        f.onSessionEnded()
        clock.set(3_000)
        f.onRefused()
        assertFalse("отказ через 3 с после своего ByeBye - AA ещё поднимает порт", f.refusedFresh)
        assertFalse(f.needsStart())
        clock.set(18_000)
        assertTrue("дольше 15 с - всё равно просим запустить", f.needsStart())
    }

    @Test
    fun refusalWithoutPrecedingOwnSessionEndIsFresh() {
        val clock = AtomicLong(0L)
        val f = ServerFailures { clock.get() }
        assertFalse(f.refusedFresh)
        f.onEstablished()
        f.onRefused()
        assertTrue(f.refusedFresh)
        assertTrue("свежий старт моста: работавший сервер не ждём", f.needsStart())
    }

    @Test
    fun refusalAfterRelistenWindowIsFresh() {
        val clock = AtomicLong(0L)
        val f = ServerFailures { clock.get() }
        f.onEstablished()
        f.onSessionEnded()
        clock.set(12_000)
        f.onRefused()
        assertTrue(f.refusedFresh)
        assertTrue(f.needsStart())
        // окно переживает reset() цикла коннектора
        val g = ServerFailures { clock.get() }
        g.onEstablished()
        g.onSessionEnded()
        g.reset()
        clock.set(14_000)
        g.onRefused()
        assertFalse(g.refusedFresh)
    }

    // --- классификация итога настоящей пробы ---

    @Test
    fun classifyConnectFailures() {
        val refusedAndroid = ConnectException("failed to connect to /127.0.0.1 (port 5277) from /:: (port 4242) after 3000ms: isConnectionRefused failed: ECONNREFUSED (Connection refused)")
        val c = AaSessionProbe.Companion
        assertEquals(ProbeResult.REFUSED, c.classify(refusedAndroid, false, false, "сервер недоступен"))
        assertEquals(ProbeResult.REFUSED, c.classify(ConnectException("Connection refused: connect"), false, false, "-"))
        assertEquals(ProbeResult.HUNG, c.classify(SocketTimeoutException("failed to connect after 3000ms"), false, false, "-"))
        assertEquals(ProbeResult.ERROR, c.classify(SocketException("socket failed: EPERM (Operation not permitted)"), false, false, "-"))
        assertEquals(ProbeResult.ERROR, c.classify(null, false, false, "не подключено"))
    }

    @Test
    fun classifyHandshakeOutcomes() {
        val c = AaSessionProbe.Companion
        assertEquals(ProbeResult.ALIVE, c.classify(null, true, true, "проба: SDR получен"))
        assertEquals(ProbeResult.HUNG, c.classify(null, true, false, "нет VERSION_RESPONSE. Если соединение открыто, но тишина: сервер ГУ завис"))
        assertEquals(ProbeResult.HUNG, c.classify(null, true, false, "TLS handshake: таймаут"))
        assertEquals("сервер сразу закрыл соединение (h224): не утверждаем, что он умер", ProbeResult.ERROR, c.classify(null, true, false, "peer closed: телефон закрыл соединение (EOF)"))
        assertEquals(ProbeResult.ERROR, c.classify(null, true, false, "TLS handshake не прошёл: SSLHandshakeException"))
    }

    // --- настоящая AapSession против фейкового "телефона" по loopback (как AapSessionTest) ---

    private val creds by lazy { AapCerts.load(TestAssets.opener, "jvc") }

    private fun probe(port: Int) = AaSessionProbe(
        loadCreds = { creds }, sd = { AapSdOptions() }, log = { l, _ -> logs.add(l) },
        port = port, sdrWaitMs = 1500,
    )

    @Test
    fun realProbeAgainstFakePhoneClosesWithByeBye() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.use {
            lateinit var phone: AapSessionTest.Phone
            val accepted = CountDownLatch(1)
            Thread {
                phone = AapSessionTest.Phone(server.accept(), "jvc", false, "no_video")
                accepted.countDown()
                phone.start()
            }.start()
            val r = probe(server.localPort).probeOnce()
            assertEquals(logs.joinToString("\n"), ProbeResult.ALIVE, r)
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            phone.join(10_000)
            assertFalse("скрипт телефона завис", phone.isAlive)
            // скрипт no_video проверяет: SDR получил ответ, затем пришёл именно ByeBye, и только потом EOF
            assertNull("ошибка телефона: ${phone.error}\n" + logs.joinToString("\n"), phone.error)
            assertTrue("HU не закрыл сокет штатно (EOF)", phone.sawEof && !phone.reset)
            assertTrue(logs.any { it.contains("SRVCHECK сессия: connected=true established=true закрытие чистое=true") })
            // строки сессии пробы не должны совпасть с маркерами моста в LogDiag ("AA TCP подключено" и т.п.)
            assertTrue(logs.any { it.contains("SRVCHECK | aa TCP подключено") })
            assertFalse(logs.any { it.contains("AA TCP подключено") || it.contains("AA сессия установлена") })
        }
    }

    @Test
    fun realProbeOnClosedPortIsRefusedWithDetail() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val p = probe(port)
        assertEquals(logs.joinToString("\n"), ProbeResult.REFUSED, p.probeOnce())
        assertTrue(p.lastDetail, p.lastDetail.startsWith("ConnectException"))
    }

    @Test
    fun realProbeOnServerThatClosesAtOnceIsErrorNotDown() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.use {
            Thread {
                try {
                    server.accept().close()
                } catch (_: IOException) {
                }
            }.start()
            assertEquals(logs.joinToString("\n"), ProbeResult.ERROR, probe(server.localPort).probeOnce())
        }
    }
}
