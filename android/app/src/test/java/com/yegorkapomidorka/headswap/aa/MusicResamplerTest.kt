package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class MusicResamplerTest {
    /** Выходная частота -> (L, M) отношения L/M к 48000. */
    private val ratios = mapOf(44100 to (147 to 160), 32000 to (2 to 3), 24000 to (1 to 2), 22050 to (147 to 320))

    /** Стерео-синус (R = L) в int16 LE на 48 кГц. */
    private fun sine(freq: Double, amp: Double, frames: Int): ByteArray {
        val b = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val s = (amp * sin(2 * PI * freq * i / 48000.0)).roundToInt()
            for (c in 0..1) {
                b[i * 4 + c * 2] = s.toByte()
                b[i * 4 + c * 2 + 1] = (s shr 8).toByte()
            }
        }
        return b
    }

    /** Левый канал выхода как Int. */
    private fun left(pcm: ByteArray) = IntArray(pcm.size / 4) { i ->
        ((pcm[i * 4].toInt() and 0xFF) or (pcm[i * 4 + 1].toInt() shl 8)).toShort().toInt()
    }

    @Test
    fun ratioMatchesForEveryRate() {
        for ((rate, lm) in ratios) {
            val (l, m) = lm
            val rs = MusicResampler(rate)
            var inFrames = 0L
            var outFrames = 0L
            repeat(1000) {
                outFrames += rs.process(ByteArray(8192)).size / 4
                inFrames += 2048
            }
            val expected = inFrames * l / m
            assertTrue("$rate Гц: кадров на выходе $outFrames, ждали $expected", abs(outFrames - expected) <= 1)
        }
    }

    @Test
    fun emptyAndPartialInput() {
        for (rate in ratios.keys) {
            val rs = MusicResampler(rate)
            assertEquals(0, rs.process(ByteArray(0)).size)
            assertEquals(0, rs.process(ByteArray(3)).size)
        }
    }

    @Test
    fun chunkingDoesNotChangeResult() {
        val src = sine(1000.0, 10000.0, 20000)
        // добавим шум в правый канал, чтобы каналы различались
        val rnd = java.util.Random(1)
        for (i in 0 until 20000) {
            val v = rnd.nextInt(2000) - 1000
            src[i * 4 + 2] = v.toByte()
            src[i * 4 + 3] = (v shr 8).toByte()
        }
        for (rate in ratios.keys) {
            val whole = MusicResampler(rate).process(src)
            val rs = MusicResampler(rate)
            val parts = java.io.ByteArrayOutputStream()
            var off = 0
            val sizes = intArrayOf(4, 8, 12, 4, 400, 36, 8192, 20, 1000, 4)
            var k = 0
            while (off < src.size) {
                val n = minOf(sizes[k++ % sizes.size], src.size - off)
                parts.write(rs.process(src.copyOfRange(off, off + n)))
                off += n
            }
            assertArrayEquals("$rate Гц", whole, parts.toByteArray())
        }
    }

    @Test
    fun resetRestartsFromScratch() {
        val src = sine(3000.0, 8000.0, 4096)
        for (rate in ratios.keys) {
            val rs = MusicResampler(rate)
            val a = rs.process(src)
            rs.process(sine(500.0, 20000.0, 777))
            rs.reset()
            assertArrayEquals("$rate Гц", a, rs.process(src))
        }
    }

    @Test
    fun sine1kHzKeepsFrequencyAndAmplitude() {
        for (rate in ratios.keys) {
            val out = left(MusicResampler(rate).process(sine(1000.0, 10000.0, 48000)))
            val tail = out.copyOfRange(100, out.size)
            var crossings = 0
            for (i in 1 until tail.size) if ((tail[i - 1] < 0) != (tail[i] < 0)) crossings++
            val seconds = tail.size / rate.toDouble()
            val hz = crossings / 2.0 / seconds
            assertEquals("$rate Гц: частота по переходам через ноль", 1000.0, hz, 10.0)
            val peak = tail.maxOf { abs(it) }
            assertTrue("$rate Гц: амплитуда $peak", peak in 9000..11000)
        }
    }

    @Test
    fun toneAboveOutputNyquistIsAttenuated() {
        // частота выше 0,5 x выход: давится минимум в 10 раз (пик < 1000 при амплитуде 10000)
        val tones = mapOf(22050 to 12000.0, 24000 to 15000.0, 32000 to 17000.0, 44100 to 21000.0)
        for ((rate, hz) in tones) {
            val out = left(MusicResampler(rate).process(sine(hz, 10000.0, 48000)))
            val peak = out.copyOfRange(100, out.size).maxOf { abs(it) }
            assertTrue("$rate Гц: ${hz.toInt()} Гц должны гаситься минимум в 10 раз, пик $peak", peak < 1000)
        }
    }

    @Test
    fun dcPassesWithUnityGain() {
        for (rate in ratios.keys) {
            val b = ByteArray(8192)
            for (i in 0 until 2048) for (c in 0..1) {
                b[i * 4 + c * 2] = 0x10
                b[i * 4 + c * 2 + 1] = 0x27         // 0x2710 = 10000
            }
            val out = left(MusicResampler(rate).process(b))
            val tail = out.copyOfRange(100, out.size)
            assertTrue("$rate Гц: постоянная составляющая ${tail.min()}..${tail.max()}", tail.all { abs(it - 10000) <= 2 })
        }
    }

    @Test
    fun saturatesInsteadOfWrapping() {
        // полный размах на низкой частоте: выход не должен «перескакивать» знак
        for (rate in ratios.keys) {
            val out = left(MusicResampler(rate).process(sine(100.0, 32767.0, 48000)))
            var maxJump = 0
            for (i in 1 until out.size) maxJump = maxOf(maxJump, abs(out[i] - out[i - 1]))
            assertTrue("$rate Гц: скачок $maxJump", maxJump < 3000)
        }
    }
}
