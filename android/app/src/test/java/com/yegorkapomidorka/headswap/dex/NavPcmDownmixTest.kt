package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class NavPcmDownmixTest {
    private fun same(frames: Int, f: (Int) -> Int) = stereo(frames, f, f)

    private fun stereo(frames: Int, left: (Int) -> Int, right: (Int) -> Int): ByteArray {
        val b = ByteArray(frames * 4)
        for (f in 0 until frames) {
            put(b, f * 4, left(f))
            put(b, f * 4 + 2, right(f))
        }
        return b
    }

    private fun put(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte()
        b[i + 1] = (v shr 8).toByte()
    }

    private fun samples(b: ByteArray) = IntArray(b.size / 2) { (b[it * 2].toInt() and 0xFF) or (b[it * 2 + 1].toInt() shl 8) }

    private fun rms(s: IntArray, skip: Int = 0) = sqrt(s.drop(skip).sumOf { it.toDouble() * it } / (s.size - skip))

    @org.junit.Test fun lengthIsThirdOfFramesAndMono() {
        val out = NavPcmDownmix().process(same(960) { 0 })              // 20 мс 48 кГц стерео -> 320 сэмплов 16 кГц
        assertEquals(640, out.size)
    }

    @org.junit.Test fun silenceStaysSilence() {
        val out = NavPcmDownmix().process(same(960) { 0 })
        assertTrue(samples(out).all { it == 0 })
    }

    @org.junit.Test fun sineOneKhzKeepsLevel() {
        val amp = 10_000.0
        val input = same(4800) { (amp * sin(2 * PI * 1000 * it / 48000)).roundToInt() }
        val out = samples(NavPcmDownmix().process(input))
        assertEquals(1600, out.size)
        val expected = amp / sqrt(2.0)
        val level = rms(out, skip = 8)                                     // без разгона фильтра
        assertTrue("rms $level, ждали около $expected", abs(level - expected) < expected * 0.1)
        // частота та же: период 16 сэмплов на выходе (16 кГц / 1 кГц)
        val crossings = (9 until out.size).count { out[it - 1] < 0 && out[it] >= 0 }
        assertEquals(100.0, crossings.toDouble(), 3.0)
    }

    @org.junit.Test fun stereoIsAveraged() {
        val out = samples(NavPcmDownmix().process(stereo(60, { 8000 }, { -2000 })))
        assertEquals(3000, out.last())                                     // (8000 - 2000) / 2, окно уже заполнено
    }

    @org.junit.Test fun bufferSeamsDoNotChangeResult() {
        val whole = same(960) { (9000 * sin(2 * PI * 440 * it / 48000)).roundToInt() }
        val ref = NavPcmDownmix().process(whole)
        val split = NavPcmDownmix()
        val cuts = listOf(0, 100, 501, 960)                                // границы не кратны 3 кадрам
        val parts = (0 until cuts.size - 1).fold(ByteArray(0)) { acc, i -> acc + split.process(whole.copyOfRange(cuts[i] * 4, cuts[i + 1] * 4)) }
        assertEquals(ref.toList(), parts.toList())
    }

    @org.junit.Test fun clipsInsteadOfWrapping() {
        val out = samples(NavPcmDownmix().process(same(60) { 32767 }))
        assertTrue(out.all { it in -32768..32767 })
        assertEquals(32767, out.last())
    }
}
