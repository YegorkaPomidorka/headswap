package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitratePolicyTest {
    private val ceiling = 6_000_000

    /**
     * Модель очереди: queue += bitrate/8 * dt - drain * dt (не ниже 0). Возвращает историю (время мс -> битрейт)
     * и максимум сырого бэклога. spikeAtMs/spikeBytes - разовый кусок в очередь (IDR).
     */
    private class Run(
        val policy: BitratePolicy,
        val drainBps: Double,
        val stepMs: Long = 100,
        val spikeAtMs: Long = -1,
        val spikeBytes: Double = 0.0,
    ) {
        var queue = 0.0
        var now = 0L
        var maxRawMs = 0.0
        val history = ArrayList<Pair<Long, Int>>()
        val changes = ArrayList<Pair<Long, Int>>()

        fun run(untilMs: Long) {
            while (now < untilMs) {
                now += stepMs
                val dt = stepMs / 1000.0
                queue = maxOf(queue + policy.current / 8.0 * dt - drainBps * dt, 0.0)
                if (spikeAtMs in (now - stepMs + 1)..now) queue += spikeBytes
                val raw = queue / drainBps * 1000
                maxRawMs = maxOf(maxRawMs, raw)
                policy.update(now, raw, drainBps)?.let { changes.add(now to it) }
                history.add(now to policy.current)
            }
        }
    }

    @Test
    fun slowDrainConvergesAndDoesNotOscillateWildly() {
        for (step in listOf(100L, 500L)) {
            val r = Run(BitratePolicy(ceiling), 210.0 * 1024, step)
            r.run(30_000)
            val at30 = r.policy.current
            assertTrue("за 30 с битрейт $at30 (шаг $step мс)", at30 in 1_200_000..1_700_000)
            r.run(150_000)
            val tail = r.history.filter { it.first > 30_000 }.map { it.second }
            val mean = tail.average()
            assertTrue("минимум ${tail.min()} при среднем $mean (шаг $step мс)", tail.min() >= mean * 0.8)
            assertTrue("максимум ${tail.max()} при среднем $mean (шаг $step мс)", tail.max() <= mean * 1.2)
            assertTrue(tail.min() >= BitratePolicy.FLOOR_BPS)
        }
    }

    @Test
    fun fastDrainStaysAtCeiling() {
        val r = Run(BitratePolicy(ceiling), 3.0 * 1024 * 1024)
        r.run(120_000)
        assertEquals(ceiling, r.policy.current)
        assertTrue(r.changes.isEmpty())
    }

    @Test
    fun singleIdrSpikeDoesNotCutBitrate() {
        // слив 500 КБ/с, энкодер на потолке 3.5 Мбит/с: откат возможен (0.8 * 4 Мбит/с = 3.2 Мбит/с), IDR 160 КБ даёт сырой бэклог 320 мс
        val p = BitratePolicy(3_500_000)
        val r = Run(p, 500.0 * 1000, spikeAtMs = 5_000, spikeBytes = 160.0 * 1000)
        r.run(30_000)
        assertTrue("сырой бэклог ${r.maxRawMs} мс должен был перешагнуть 300", r.maxRawMs > 300)
        assertTrue(r.changes.isEmpty())
        assertEquals(3_500_000, p.current)
    }

    @Test
    fun idrSpikeOnKiaDrainDoesNotCutBitrate() {
        // слив 210 КиБ/с, энкодер на 1.2 Мбит/с, IDR 100 КБ = 465 мс бэклога
        val p = BitratePolicy(1_200_000)
        val r = Run(p, 210.0 * 1024, spikeAtMs = 5_000, spikeBytes = 100.0 * 1000)
        r.run(20_000)
        assertTrue(r.maxRawMs > 400)
        assertTrue(r.changes.isEmpty())
    }

    @Test
    fun floorIsNotBroken() {
        val r = Run(BitratePolicy(ceiling), 30.0 * 1024)          // слив 30 КиБ/с = 245 кбит/с, 0.8 от него ниже пола
        r.run(120_000)
        assertTrue(r.history.all { it.second >= BitratePolicy.FLOOR_BPS })
        assertEquals(BitratePolicy.FLOOR_BPS, r.history.last().second)
    }

    @Test
    fun downStepGoesToEightyPercentOfDrainAndIsRateLimited() {
        val p = BitratePolicy(ceiling)
        val drain = 210.0 * 1024
        assertNull(p.update(0, 0.0, drain))
        // устойчивый бэклог 1 с: сглаженный перешагнёт 300 мс, откат одним шагом до 0.8 слива
        var first: Pair<Long, Int>? = null
        var t = 0L
        while (t < 3000 && first == null) {
            t += 100
            p.update(t, 1000.0, drain)?.let { first = t to it }
        }
        assertNotNull(first)
        assertEquals((drain * 8 * 0.8).toInt(), first!!.second)
        // слив упал вдвое: следующее снижение не раньше чем через 1 с
        val newDrain = drain * 0.75
        var second: Pair<Long, Int>? = null
        t = first!!.first
        while (t < first!!.first + 5000 && second == null) {
            t += 100
            p.update(t, 1000.0, newDrain)?.let { second = t to it }
        }
        assertNotNull(second)
        assertTrue(second!!.first - first!!.first >= 1000)
        assertEquals((newDrain * 8 * 0.8).toInt(), second!!.second)
    }

    @Test
    fun upStepsOnlyAfterTenCalmSecondsEachAndStopAtCeiling() {
        val p = BitratePolicy(ceiling)
        val drain = 3.0 * 1024 * 1024
        var t = 0L
        // принудительный откат: большой бэклог при медленном сливе
        while (p.current == ceiling) {
            t += 100
            p.update(t, 2000.0, 100.0 * 1024)
        }
        val cut = t
        val low = p.current
        // затишье: вверх не раньше 10 с после отката, потом +10% и снова 10 с
        val ups = ArrayList<Pair<Long, Int>>()
        while (t < cut + 60_000) {
            t += 100
            p.update(t, 0.0, drain)?.let { ups.add(t to it) }
        }
        assertTrue(ups.size >= 3)
        // сглаженный бэклог после отката ещё спадает, поэтому первая ступень не раньше 10 с
        assertTrue(ups[0].first - cut >= 10_000)
        assertEquals((low * 1.1).toInt(), ups[0].second)
        for (i in 1 until ups.size) {
            assertTrue(ups[i].first - ups[i - 1].first >= 10_000)
            assertTrue(ups[i].second <= ceiling)
        }
        t += 200_000
        var last = p.current
        var u = t - 200_000
        while (u < t) {
            u += 100
            p.update(u, 0.0, drain)
            last = p.current
        }
        assertEquals(ceiling, last)
    }

    @Test
    fun unknownDrainNeverCuts() {
        val p = BitratePolicy(ceiling)
        for (i in 1..100) assertNull(p.update(i * 100L, 5000.0, 0.0))
        assertEquals(ceiling, p.current)
    }

    @Test
    fun ceilingBelowFloorIsRespected() {
        val p = BitratePolicy(500_000)
        for (i in 1..100) p.update(i * 100L, 5000.0, 10_000.0)
        assertEquals(500_000, p.current)
    }

    @Test
    fun lowerCeilingCutsCurrentAndKeepsUpBelowCeiling() {
        val p = BitratePolicy(8_000_000)
        assertEquals(8_000_000, p.current)
        assertTrue(p.lowerCeiling(4_000_000))
        assertEquals(4_000_000, p.ceilingBps)
        assertEquals(4_000_000, p.current)
        assertEquals(4_000_000, p.minBps)
        // быстрый слив, долгое затишье: выше нового потолка не поднимается
        for (i in 1..1000) p.update(i * 100L, 0.0, 3.0 * 1024 * 1024)
        assertEquals(4_000_000, p.current)
    }

    @Test
    fun lowerCeilingOnlyLowersAndRespectsFloor() {
        val p = BitratePolicy(3_000_000)
        assertTrue(!p.lowerCeiling(3_000_000))
        assertTrue(!p.lowerCeiling(5_000_000))
        assertEquals(3_000_000, p.ceilingBps)
        assertTrue(p.lowerCeiling(100_000))
        assertEquals(BitratePolicy.FLOOR_BPS, p.ceilingBps)
        assertEquals(BitratePolicy.FLOOR_BPS, p.current)
    }

    @Test
    fun lowerCeilingBelowCurrentLeavesLowerCurrent() {
        val p = BitratePolicy(6_000_000, profile = BitrateProfile.WIFI)   // старт 4.5 Мбит/с
        assertEquals(4_500_000, p.current)
        assertTrue(p.lowerCeiling(5_000_000))
        assertEquals(4_500_000, p.current)
        assertTrue(p.lowerCeiling(4_000_000))
        assertEquals(4_000_000, p.current)
    }
}
