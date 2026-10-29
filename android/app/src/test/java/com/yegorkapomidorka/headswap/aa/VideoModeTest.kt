package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.yegorkapomidorka.headswap.aa.AapMessages as M

class VideoModeTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun table() {
        data class Row(val aa: String, val mw: Int, val mh: Int, val density: Int, val w: Int, val h: Int, val bitrate: Int)
        val expected = mapOf(
            VideoMode.R800 to Row("800x480", 0, 0, 120, 800, 480, 4_000_000),
            VideoMode.R1024 to Row("1280x720", 256, 120, 160, 1024, 600, 2_000_000),
            VideoMode.R720 to Row("1280x720", 0, 0, 160, 1280, 720, 4_000_000),
            VideoMode.R1080 to Row("1920x1080", 0, 0, 240, 1920, 1080, 8_000_000),
            VideoMode.WIDE to Row("1280x720", 0, 240, 160, 1280, 480, 4_000_000),
            VideoMode.WIDE_MID to Row("1920x1080", 192, 432, 160, 1728, 648, 5_000_000),
            VideoMode.WIDE_HD to Row("1920x1080", 0, 360, 160, 1920, 720, 6_000_000),
            VideoMode.WIDE_640 to Row("1920x1080", 0, 440, 160, 1920, 640, 6_000_000),
            VideoMode.WIDE_2_1 to Row("1280x720", 0, 80, 160, 1280, 640, 4_000_000),
            VideoMode.WIDE_976 to Row("1920x1080", 0, 244, 160, 2240, 976, 8_000_000),
            VideoMode.WIDE_836 to Row("1920x1080", 0, 244, 160, 1920, 836, 6_000_000),
        )
        assertEquals(expected.keys, VideoMode.values().toSet())
        for ((m, r) in expected) {
            assertEquals(m.name, r.aa, m.aaResolution)
            assertEquals(m.name, r.mw, m.marginW)
            assertEquals(m.name, r.mh, m.marginH)
            assertEquals(m.name, r.density, m.density)
            assertEquals(m.name, r.w, m.outW)
            assertEquals(m.name, r.h, m.outH)
            assertEquals(m.name, r.bitrate, m.bitrate)
            // выход CarLife = кадр AA без margin-ов (WIDE_976 - исключение: область 1920x836 масштабируется в 2240x976)
            if (m != VideoMode.WIDE_976) {
                assertEquals(m.name, m.aaW - m.marginW, m.outW)
                assertEquals(m.name, m.aaH - m.marginH, m.outH)
            }
        }
        assertEquals(VideoMode.R720, VideoMode.DEFAULT)
    }

    @Test
    fun r1024() {
        val m = VideoMode.R1024
        // из кадра AA 1280x720 вырезается центр 1024x600, масштаба нет: тач 1:1 с клампом в область
        assertEquals(1023 to 599, m.mapTouch(1100, 700))
        assertEquals(57 to 301, m.mapTouch(57, 301))
        assertEquals(128f / 1280f, m.crop.u0, 1e-6f)
        assertEquals(1152f / 1280f, m.crop.u1, 1e-6f)
        assertEquals(60f / 720f, m.crop.v0, 1e-6f)
        assertEquals(660f / 720f, m.crop.v1, 1e-6f)
        assertTrue(m.needsTranscode)
        assertEquals(VideoMode.R720, m.effective(transcode = false))
        assertEquals(m, m.effective(transcode = true))
        assertEquals("1024×600", m.outLabel)
        assertEquals(VideoMode.R1024, VideoMode.parse("R1024"))
    }

    @Test
    fun cropFractions() {
        for (m in listOf(VideoMode.R800, VideoMode.R720, VideoMode.R1080)) assertTrue(m.name, m.crop.isNone)
        val c = VideoMode.WIDE.crop
        assertFalse(c.isNone)
        assertEquals(0f, c.u0, 1e-6f)
        assertEquals(1f, c.u1, 1e-6f)
        assertEquals(120f / 720f, c.v0, 1e-6f)          // 0.1667
        assertEquals(600f / 720f, c.v1, 1e-6f)          // 0.8333
        assertTrue(VideoMode.WIDE.needsTranscode)
        assertFalse(VideoMode.R1080.needsTranscode)
    }

    @Test
    fun effectiveAndParse() {
        assertEquals(VideoMode.R720, VideoMode.WIDE.effective(transcode = false))
        assertEquals(VideoMode.WIDE, VideoMode.WIDE.effective(transcode = true))
        assertEquals(VideoMode.R1080, VideoMode.R1080.effective(transcode = false))
        assertEquals(VideoMode.R800, VideoMode.R800.effective(transcode = false))
        assertEquals(VideoMode.WIDE, VideoMode.parse("WIDE"))
        assertEquals(VideoMode.R720, VideoMode.parse(null))
        assertEquals(VideoMode.R720, VideoMode.parse("мусор"))
    }

    @Test
    fun describeLine() {
        assertEquals(
            "видео-режим WIDE: AA 1280x720 margins 0x240 density 160, CarLife 1280x480, кроп v 0.167..0.833",
            VideoMode.WIDE.describe(),
        )
        assertEquals(
            "видео-режим R720: AA 1280x720 margins 0x0 density 160, CarLife 1280x720, без кропа",
            VideoMode.R720.describe(),
        )
    }

    @Test
    fun touchMappingR720IsIdentity() {
        assertEquals(640 to 360, VideoMode.R720.mapTouch(640, 360))
        assertEquals(0 to 0, VideoMode.R720.mapTouch(0, 0))
        assertEquals(40 to 675, VideoMode.R720.mapTouch(40, 675))
        for (m in listOf(VideoMode.R800, VideoMode.R1080)) assertEquals(m.name, 7 to 9, m.mapTouch(7, 9))
    }

    @Test
    fun touchMappingWideIsContentSpace() {
        val w = VideoMode.WIDE
        assertEquals(640 to 0, w.mapTouch(640, 0))
        assertEquals(640 to 479, w.mapTouch(640, 479))
        assertEquals(38 to 441, w.mapTouch(38, 441))         // кнопка сетки на Desay G6S
        assertEquals(1279 to 240, w.mapTouch(1279, 240))
        // за пределами кадра ГУ - clamp в область интерфейса 1280x480
        assertEquals(0 to 0, w.mapTouch(-5, -300))
        assertEquals(1279 to 479, w.mapTouch(2000, 900))
        assertEquals(1919 to 719, VideoMode.WIDE_HD.mapTouch(2000, 900))
        assertEquals(57 to 661, VideoMode.WIDE_HD.mapTouch(57, 661))
        assertEquals(180f / 1080f, VideoMode.WIDE_HD.crop.v0, 1e-6f)
        assertEquals(900f / 1080f, VideoMode.WIDE_HD.crop.v1, 1e-6f)
        // h521: поля внизу (SPS-кроп), а перекодирование принудительное - транскодер режет верхние 720 строк (v вверх)
        val bottom = VideoMode.WIDE_HD.cropFor(marginsAtBottom = true)
        assertEquals(360f / 1080f, bottom.v0, 1e-6f)
        assertEquals(1f, bottom.v1, 1e-6f)
        assertEquals(VideoMode.WIDE_HD.crop, VideoMode.WIDE_HD.cropFor(marginsAtBottom = false))
        assertTrue(VideoMode.R720.cropFor(marginsAtBottom = true).isNone)
        val mid = VideoMode.WIDE_MID
        assertEquals(1727 to 647, mid.mapTouch(2000, 900))
        assertEquals(864 to 324, mid.mapTouch(864, 324))
        assertEquals(96f / 1920f, mid.crop.u0, 1e-6f)
        assertEquals(1824f / 1920f, mid.crop.u1, 1e-6f)
        assertEquals(216f / 1080f, mid.crop.v0, 1e-6f)
        assertEquals(864f / 1080f, mid.crop.v1, 1e-6f)
        assertTrue(mid.needsTranscode)
        val c640 = VideoMode.WIDE_640
        assertEquals(1919 to 639, c640.mapTouch(2000, 900))
        assertEquals(57 to 601, c640.mapTouch(57, 601))
        assertEquals(220f / 1080f, c640.crop.v0, 1e-6f)
        assertEquals(860f / 1080f, c640.crop.v1, 1e-6f)
        assertEquals(0f, c640.crop.u0, 1e-6f)
        assertEquals(1f, c640.crop.u1, 1e-6f)
        assertTrue(c640.needsTranscode)
        assertEquals("1920×640", c640.outLabel)
        assertEquals(VideoMode.WIDE_640, VideoMode.parse("WIDE_640"))
        assertEquals(listOf(VideoMode.WIDE, VideoMode.WIDE_MID, VideoMode.WIDE_HD, VideoMode.WIDE_640, VideoMode.WIDE_2_1, VideoMode.WIDE_976, VideoMode.WIDE_836), VideoMode.values().takeLast(7))
    }

    @Test
    fun wide21() {
        val k = VideoMode.WIDE_2_1
        assertEquals(1279 to 639, k.mapTouch(2000, 900))
        assertEquals(57 to 601, k.mapTouch(57, 601))
        assertEquals(0f, k.crop.u0, 1e-6f)
        assertEquals(1f, k.crop.u1, 1e-6f)
        assertEquals(40f / 720f, k.crop.v0, 1e-6f)
        assertEquals(680f / 720f, k.crop.v1, 1e-6f)
        assertTrue(k.needsTranscode)
        assertEquals(VideoMode.R720, k.effective(transcode = false))
        assertEquals("1280×640", k.outLabel)
        assertEquals(VideoMode.WIDE_2_1, VideoMode.parse("WIDE_2_1"))
    }

    @Test
    fun wide976() {
        val k = VideoMode.WIDE_976
        assertEquals(122f / 1080f, k.crop.v0, 1e-6f)
        assertEquals(958f / 1080f, k.crop.v1, 1e-6f)
        assertEquals(0f, k.crop.u0, 1e-6f)
        assertEquals(1f, k.crop.u1, 1e-6f)
        assertTrue(k.needsTranscode)
        assertEquals(VideoMode.R720, k.effective(transcode = false))
        // рабочая область AA 1920x836 масштабируется в кадр ГУ 2240x976: тач пересчитывается и клампится в область
        assertEquals(1919 to 835, k.mapTouch(3000, 2000))
        assertEquals(960 to 418, k.mapTouch(1120, 488))
        assertEquals(0 to 0, k.mapTouch(0, 0))
        assertEquals(1919 to 835, k.mapTouch(2239, 975))
        assertEquals("2240×976", k.outLabel)
        assertEquals(VideoMode.WIDE_976, VideoMode.parse("WIDE_976"))
        // убранный режим из старых prefs - режим по умолчанию
        assertEquals(VideoMode.DEFAULT, VideoMode.parse("R1440"))
    }

    @Test
    fun sdrWideHasMarginHeightAndFullSizeTouchscreen() {
        val wide = hex(M.serviceDiscoveryResponse(VideoMode.WIDE.sdOptions(fullAudio = false)).payload)
        // VideoConfiguration: res=2 fps=2 margin_w=0 margin_h=240 (20 f0 01) density 160 par 10000 codec 3
        assertTrue(wide, wide.contains("08021002180020f00128a00140904e5003"))
        // touchscreen остаётся 1280x720 (поле 2: 1280 = 80 0a, 720 = d0 05)
        assertTrue(wide, wide.contains("1206" + "08800a" + "10d005"))
    }

    @Test
    fun sdrDefaultModeEqualsPlainDefault() {
        val viaMode = M.serviceDiscoveryResponse(VideoMode.R720.sdOptions(fullAudio = false)).payload
        assertEquals(hex(M.serviceDiscoveryResponse().payload), hex(viaMode))
    }

    @Test
    fun sdrOtherModes() {
        val r1080 = hex(M.serviceDiscoveryResponse(VideoMode.R1080.sdOptions(false)).payload)
        assertTrue(r1080, r1080.contains("0803100218002000" + "28f001"))      // res=3, margins 0, density 240
        val wide976 = hex(M.serviceDiscoveryResponse(VideoMode.WIDE_976.sdOptions(false)).payload)
        assertTrue(wide976, wide976.contains("08031002180020f401" + "28a001"))   // res=3, margin_h 244 (f4 01), density 160 (a0 01)
        val r800 = hex(M.serviceDiscoveryResponse(VideoMode.R800.sdOptions(false)).payload)
        assertTrue(r800, r800.contains("0801100218002000" + "2878"))          // res=1, margins 0, density 120
    }

    @Test
    fun coreMapsTouchByMode() {
        val core = AaBridgeCore({ _, _ -> })
        val peer = FakePeer()
        core.peer = peer
        core.onCarlifeTouch(0, 640, 0)                       // по умолчанию R720: 1:1
        core.videoMode = VideoMode.WIDE
        core.onCarlifeTouch(0, 640, 0)
        core.onCarlifeTouch(2, 640, 479)
        core.onCarlifeTouch(1, 640, 500)
        assertEquals(
            listOf(Triple(0, 640, 0), Triple(0, 640, 0), Triple(2, 640, 479), Triple(1, 640, 479)),
            peer.touches.toList(),
        )
    }

    @Test
    fun coreLogsRawAndMappedTouch() {
        val logs = ArrayList<String>()
        val core = AaBridgeCore({ l, _ -> logs.add(l) })
        core.peer = FakePeer()
        core.videoMode = VideoMode.WIDE
        core.onCarlifeTouch(0, 640, 500)
        assertTrue(logs.toString(), logs.any { it.contains("640,500") && it.contains("640,479") })
    }

    @Test
    fun densityAutoIsModeDensity() {
        for (m in VideoMode.values()) {
            assertEquals(m.name, m.density, m.densityFor(AaDensity.AUTO))
            assertEquals(m.name, m.density, m.sdOptions(false).density)
            assertEquals(m.name, m.density, m.sdOptions(false, AaDensity.AUTO).density)
        }
        // авто = прежний SDR байт в байт
        assertEquals(hex(M.serviceDiscoveryResponse(VideoMode.R720.sdOptions(false)).payload),
            hex(M.serviceDiscoveryResponse(VideoMode.R720.sdOptions(false, AaDensity.AUTO)).payload))
    }

    @Test
    fun densityOverrideReplacesOnlyDensity() {
        for (m in VideoMode.values()) {
            val auto = m.sdOptions(true)
            val o = m.sdOptions(true, 200)
            assertEquals(m.name, 200, o.density)
            assertEquals(m.name, auto.copy(density = 200), o)      // разрешение, margin-ы, звук - как в авто
        }
        // R720 с 200: VideoConfiguration ... density 200 (c8 01) вместо 160 (a0 01)
        val r720 = hex(M.serviceDiscoveryResponse(VideoMode.R720.sdOptions(false, 200)).payload)
        assertTrue(r720, r720.contains("0802100218002000" + "28c801"))
        val r800 = hex(M.serviceDiscoveryResponse(VideoMode.R800.sdOptions(false, 100)).payload)
        assertTrue(r800, r800.contains("0801100218002000" + "2864"))       // density 100
    }

    @Test
    fun densityOverrideDoesNotTouchCropOrTouch() {
        // кроп и тач - свойства режима, override в них не участвует; проверяем на ядре моста с override
        for (m in VideoMode.values()) {
            val crop = m.crop
            val touch = listOf(0 to 0, m.outW / 2 to m.outH / 2, m.outW - 1 to m.outH - 1).map { (x, y) -> m.mapTouch(x, y) }
            m.sdOptions(false, 280)
            assertEquals(m.name, crop, m.crop)
            assertEquals(m.name, touch, listOf(0 to 0, m.outW / 2 to m.outH / 2, m.outW - 1 to m.outH - 1).map { (x, y) -> m.mapTouch(x, y) })
        }
        val core = AaBridgeCore({ _, _ -> })
        val peer = FakePeer()
        core.peer = peer
        core.videoMode = VideoMode.WIDE
        core.densityOverride = 120
        core.onCarlifeTouch(0, 640, 0)
        core.onCarlifeTouch(1, 640, 500)
        assertEquals(listOf(Triple(0, 640, 0), Triple(1, 640, 479)), peer.touches.toList())
    }

    @Test
    fun densityDescribeMarksOverride() {
        assertEquals(VideoMode.R720.describe(), VideoMode.R720.describe(AaDensity.AUTO))
        assertEquals(
            "видео-режим R720: AA 1280x720 margins 0x0 density 200 (вручную, авто 160), CarLife 1280x720, без кропа",
            VideoMode.R720.describe(200),
        )
    }

    @Test
    fun densityParseAndStep() {
        assertEquals(listOf(100, 120, 140, 160, 180, 200, 220, 240, 260, 280), AaDensity.VALUES)
        assertEquals(AaDensity.AUTO, AaDensity.parse(0))
        assertEquals(200, AaDensity.parse(200))
        assertEquals(AaDensity.AUTO, AaDensity.parse(210))      // вне сетки
        assertEquals(AaDensity.AUTO, AaDensity.parse(320))      // вне диапазона
        assertEquals(AaDensity.AUTO, AaDensity.parse(80))
        // из авто - соседнее значение от density режима
        assertEquals(180, AaDensity.step(AaDensity.AUTO, 160, up = true))
        assertEquals(140, AaDensity.step(AaDensity.AUTO, 160, up = false))
        assertEquals(100, AaDensity.step(AaDensity.AUTO, 120, up = false))
        assertEquals(280, AaDensity.step(AaDensity.AUTO, 320, up = false))      // авто выше максимума сетки
        assertEquals(AaDensity.AUTO, AaDensity.step(AaDensity.AUTO, 320, up = true))
        // на краях стоим
        assertEquals(280, AaDensity.step(280, 160, up = true))
        assertEquals(100, AaDensity.step(100, 160, up = false))
        assertEquals(220, AaDensity.step(200, 160, up = true))
    }
}
