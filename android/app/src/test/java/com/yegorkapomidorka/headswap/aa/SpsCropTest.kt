package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpsCropTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** SPS AA из записи pc-headunit: Baseline 1280x720, без кропа. */
    private val aa720 = hex("6742801fda014016e9a8283028368509a8")

    /** x264 1920x1080: кроп снизу 8 px, в SPS есть эмуляционные 00 00 03. */
    private val x264Baseline = hex("6742c028d900780227e5c044000003000400000300f03c60c920")
    private val x264Main = hex("674d4028eca03c0113f2e022000003000200000300781e30632c")
    private val x264High = hex("67640028acd940780227e5c044000003000400000300f03c60c658")

    @Test
    fun parsesGeometry() {
        assertEquals(SpsCrop.Info(1280, 720, 0, 0, 0, 0, 2, 2), SpsCrop.parse(aa720))
        for (s in listOf(x264Baseline, x264Main, x264High)) {
            assertEquals(SpsCrop.Info(1920, 1088, 0, 0, 0, 8, 2, 2), SpsCrop.parse(s))
        }
    }

    @Test
    fun zeroAdditionKeepsBytesWhenCropAlreadySet() {
        for (s in listOf(x264Baseline, x264Main, x264High)) {
            assertArrayEquals(s, SpsCrop.rewrite(s, 0, 0, 0, 0).sps)
        }
    }

    @Test
    fun wide640From1080() {
        for (s in listOf(x264Baseline, x264Main, x264High)) {
            val r = SpsCrop.rewrite(s, 0, 0, 220, 220)
            assertEquals(1920, r.after.visibleW)
            assertEquals(640, r.after.visibleH)
            val back = SpsCrop.parse(r.sps)
            assertEquals(r.after, back)
            assertEquals(220, back.top)
            assertEquals(228, back.bottom)
        }
    }

    /** Эталон: ffmpeg -bsf:v h264_metadata=crop_top=..:crop_bottom=.. на тех же потоках, байт в байт. */
    @Test
    fun matchesFfmpegH264Metadata() {
        assertArrayEquals(
            hex("67640028acd940780227c0de073c0440000003004000000f03c60c6580"),
            SpsCrop.rewrite(x264High, 0, 0, 220, 220).sps,
        )
        assertArrayEquals(
            hex("6742801fda014016fc2a159a8283028368509a80"),
            SpsCrop.rewrite(aa720, 0, 0, 40, 40).sps,
        )
        // поток @diman_tm (h529): AA 1280x720 с полями 512x256, ffmpeg crop 256/256/128/128 -> 768x464
        assertArrayEquals(
            hex("6742801fda014016f010202040820419a8080808368509a8"),
            SpsCrop.rewrite(hex("6742801fda014016e9a8080808368509a8"), 256, 256, 128, 128).sps,
        )
    }

    @Test
    fun wide21FromAa720AddsCropFlag() {
        val r = SpsCrop.rewrite(aa720, 0, 0, 40, 40)
        assertEquals(SpsCrop.Info(1280, 720, 0, 0, 40, 40, 2, 2), SpsCrop.parse(r.sps))
        // первые байты (профиль, уровень) не тронуты
        assertArrayEquals(aa720.copyOfRange(0, 4), r.sps.copyOfRange(0, 4))
    }

    @Test
    fun r1024BothAxes() {
        val r = SpsCrop.rewrite(aa720, 128, 128, 60, 60)
        assertEquals(1024, r.after.visibleW)
        assertEquals(600, r.after.visibleH)
        assertEquals(r.after, SpsCrop.parse(r.sps))
    }

    @Test
    fun rewrittenHasNoStartCodeInside() {
        for (s in listOf(aa720, x264Baseline, x264Main, x264High)) {
            val out = SpsCrop.rewrite(s, 0, 0, 220, 220).sps
            for (i in 0 until out.size - 2) {
                assertFalse("00 00 0x на $i", out[i].toInt() == 0 && out[i + 1].toInt() == 0 && (out[i + 2].toInt() and 0xFF) <= 3 && out[i + 2].toInt() != 3)
            }
        }
    }

    @Test(expected = SpsCrop.SpsException::class)
    fun oddMarginRejected() {
        SpsCrop.rewrite(aa720, 0, 0, 41, 41)
    }

    @Test(expected = SpsCrop.SpsException::class)
    fun notSpsRejected() {
        SpsCrop.parse(hex("68ce3c80"))
    }

    @Test
    fun mapSpsReplacesOnlySps() {
        val sc = hex("00000001")
        val pps = hex("68ce3c80")
        val idr = hex("6588840021ff")
        val au = sc + aa720 + sc + pps + sc + idr
        val out = SpsCrop.mapSps(au) { SpsCrop.rewrite(it, 0, 0, 40, 40).sps }
        val expected = sc + SpsCrop.rewrite(aa720, 0, 0, 40, 40).sps + sc + pps + sc + idr
        assertArrayEquals(expected, out)
        val pOnly = sc + hex("419a0102")
        assertSame(pOnly, SpsCrop.mapSps(pOnly) { error("не должен вызываться") })
    }

    @Test
    fun sdrMarginsAtBottomUsesUiConfig() {
        fun h(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        val sdr = h(AapMessages.serviceDiscoveryResponse(VideoMode.WIDE_HD.sdOptions(false, marginsAtBottom = true)).payload)
        // margin_w 0, margin_h 0, density 160 ... ui_config(11){margins(1){top 0, bottom 360}}
        assertTrue(sdr, sdr.contains("0803100218002000" + "28a001"))
        assertTrue(sdr, sdr.contains("5a07" + "0a05" + "0800" + "10e802"))
        val plain = h(AapMessages.serviceDiscoveryResponse(VideoMode.WIDE_HD.sdOptions(false)).payload)
        assertFalse(plain, plain.contains("5a070a05"))
        // WIDE_MID (CX-5 G6S) из 1920x1080: поля 192 справа и 432 снизу, legacy-поля нулевые
        val mid = h(AapMessages.serviceDiscoveryResponse(VideoMode.WIDE_MID.sdOptions(false, marginsAtBottom = true)).payload)
        assertTrue(mid, mid.contains("0803100218002000"))
        assertTrue(mid, mid.contains("5a0c" + "0a0a" + "0800" + "10b003" + "1800" + "20c001"))
    }

    @Test
    fun rightAndBottomCropWideMid() {
        val r = SpsCrop.rewrite(x264High, 0, 192, 0, 432)
        assertEquals(SpsCrop.Info(1920, 1088, 0, 192, 0, 440, 2, 2), r.after)
        assertEquals(1728 to 648, r.after.visibleW to r.after.visibleH)
        assertEquals(r.after, SpsCrop.parse(r.sps))
    }

    @Test
    fun geometrySpsCropable() {
        for (m in listOf(VideoMode.WIDE, VideoMode.WIDE_MID, VideoMode.WIDE_HD, VideoMode.WIDE_640, VideoMode.WIDE_2_1, VideoMode.R1024)) {
            assertTrue(m.name, m.spsCropable)
            assertSame(m, m.effective(transcode = false, spsCrop = true))
            assertSame(VideoMode.R720, m.effective(transcode = false, spsCrop = false))
        }
        assertTrue(VideoMode.WIDE_976_QHD.spsCropable)
        assertSame(VideoMode.WIDE_976_QHD, VideoMode.WIDE_976_QHD.effective(transcode = false, spsCrop = true))
        assertFalse(VideoMode.WIDE_976.spsCropable)
        assertSame(VideoMode.R720, VideoMode.WIDE_976.effective(transcode = false, spsCrop = true))
        assertFalse(VideoMode.R1080.spsCropable)
        assertSame(VideoMode.R1080, VideoMode.R1080.effective(transcode = false, spsCrop = true))
        // своё 1:1 с полями - можно, со масштабом - нет
        assertTrue(CustomVideoMode(1920, 640).spsCropable)
        assertFalse(CustomVideoMode(2240, 976).spsCropable)
    }
}
