package com.yegorkapomidorka.headswap.link.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pPolicyTest {
    @Test
    fun firstTargetStarts() {
        assertEquals(P2pOnTarget.START, P2pPolicy.onTarget(active = false, joined = false))
    }

    /** h126: попытка не вошла (BUSY), ГУ переподключила BT и прислала TARGET_INFO снова - начинаем заново, а не «уже вхожу». */
    @Test
    fun newTargetRestartsStuckAttempt() {
        assertEquals(P2pOnTarget.RESTART, P2pPolicy.onTarget(active = true, joined = false))
    }

    @Test
    fun newTargetSkippedWhenAlreadyInGroup() {
        assertEquals(P2pOnTarget.SKIP, P2pPolicy.onTarget(active = true, joined = true))
    }

    /** h211: rejoin спрашивает TARGET_INFO раз в 5-10 с; тот же ответ не рвёт свежую попытку, протухшую или с другими реквизитами - рвёт. */
    @Test
    fun sameTargetKeepsFreshAttempt() {
        assertEquals(P2pOnTarget.SKIP, P2pPolicy.onTarget(active = true, joined = false, same = true, ageMs = 9_000L, freshMs = 30_000L))
        assertEquals(P2pOnTarget.RESTART, P2pPolicy.onTarget(active = true, joined = false, same = true, ageMs = 30_000L, freshMs = 30_000L))
        assertEquals(P2pOnTarget.RESTART, P2pPolicy.onTarget(active = true, joined = false, same = false, ageMs = 1_000L, freshMs = 30_000L))
        assertEquals(P2pOnTarget.START, P2pPolicy.onTarget(active = false, joined = false, same = false, ageMs = 0L))
    }

    @Test
    fun targetSameAs() {
        val a = P2pTarget("CarLife-x", null, null, null)
        assertEquals(true, a.sameAs(P2pTarget("CarLife-x", null, null, null)))
        assertEquals(false, a.sameAs(P2pTarget("CarLife-y", null, null, null)))
        assertEquals(false, a.sameAs(P2pTarget("CarLife-x", "DIRECT-ab", "12345678", null)))
    }

    /** h211: после распада группы при живом BT просим TARGET_INFO сами - сначала быстро, потом раз в 10 с, потом хватит. */
    @Test
    fun rejoinBackoff() {
        assertEquals(2_000L, P2pPolicy.rejoinDelayMs(0))
        assertEquals(5_000L, P2pPolicy.rejoinDelayMs(1))
        assertEquals(10_000L, P2pPolicy.rejoinDelayMs(2))
        assertEquals(10_000L, P2pPolicy.rejoinDelayMs(P2pPolicy.REJOIN_ATTEMPTS - 1))
        assertEquals(null, P2pPolicy.rejoinDelayMs(P2pPolicy.REJOIN_ATTEMPTS))
    }

    /** h211/h185: четыре запроса (~27 с) без входа, первая 30-секундная попытка кончилась - на шаге 4 (~37 с) рвём RFCOMM. */
    @Test
    fun rejoinDropsBtAfterFirstFailedJoin() {
        assertEquals(false, P2pPolicy.rejoinDropBt(0))
        assertEquals(false, P2pPolicy.rejoinDropBt(P2pPolicy.REJOIN_DROP_BT_AFTER - 1))
        assertEquals(true, P2pPolicy.rejoinDropBt(P2pPolicy.REJOIN_DROP_BT_AFTER))
        val dropAtMs = (0 until P2pPolicy.REJOIN_DROP_BT_AFTER + 1).sumOf { P2pPolicy.rejoinDelayMs(it)!! }
        assertTrue("сброс BT раньше конца первой попытки входа: $dropAtMs мс", dropAtMs > 32_000L)
    }

    /** #111 (MG): 10 входов в группу, адрес отдан, а TCP нет - через 25 с и двух MD_WIFI_IP бросаем Wi-Fi Direct. */
    @Test
    fun noTcpFallbackMg() {
        val t = NoTcpTracker()
        assertEquals(false, t.shouldFallback(0, false))
        assertEquals(null, t.nextCheckMs(0))
        assertTrue(t.joined(3_000))       // лог #111: вход на 3 с, адрес на 8,3 с, 16,8 с, 25,4 с ...
        assertEquals(false, t.joined(10_000))
        t.ipSent(8_300)
        assertEquals("одного адреса мало", false, t.shouldFallback(40_000, false))
        t.ipSent(16_800)
        assertEquals("25 с с первого входа ещё не прошли", false, t.shouldFallback(27_000, false))
        t.ipSent(25_400)
        assertEquals("только что отдали адрес: даём ГУ время", false, t.shouldFallback(28_000, false))
        assertEquals(3_400L, t.nextCheckMs(28_000))
        assertTrue(t.shouldFallback(31_400, false))
        t.markFired()
        assertEquals(false, t.shouldFallback(60_000, false))
        assertEquals(null, t.nextCheckMs(60_000))
    }

    /** h211: Wi-Fi Direct работал, сессия оборвалась, rejoin и новый адрес - Wi-Fi Direct не рвём, пока не новое ожидание. */
    @Test
    fun noTcpSilentAfterTcpUpUntilNewWait() {
        val t = NoTcpTracker()
        t.joined(0)
        t.ipSent(1_000)
        t.tcpUp()
        t.ipSent(300_000)
        t.ipSent(310_000)
        assertEquals(false, t.shouldFallback(400_000, false))
        assertEquals(null, t.nextCheckMs(400_000))
        t.newWait()
        assertEquals(false, t.shouldFallback(400_000, false))
        t.joined(500_000)
        t.ipSent(501_000)
        t.ipSent(510_000)
        assertTrue(t.shouldFallback(530_000, false))
    }

    @Test
    fun noTcpRespectsLiveTcpAndWaitsForIp() {
        val t = NoTcpTracker()
        t.joined(0)
        assertEquals("адреса ещё не просили: проверка не раньше 25 с", 25_000L, t.nextCheckMs(0))
        assertEquals("после 25 с без адреса - редкая проверка", 5_000L, t.nextCheckMs(30_000))
        t.ipSent(1_000)
        t.ipSent(2_000)
        assertEquals(false, t.shouldFallback(60_000, tcpUpNow = true))
        assertTrue(t.shouldFallback(60_000, tcpUpNow = false))
    }
}
