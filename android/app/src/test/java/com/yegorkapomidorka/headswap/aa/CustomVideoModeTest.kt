package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomVideoModeTest {
    /** Расчёт геометрии без проверки диапазона ввода: конструктор воспроизводит и размеры вне диапазона Dev-экрана (800x480, 1024x600). */
    private fun layout(w: Int, h: Int) = CustomVideoMode(w, h)

    private fun assertLayout(w: Int, h: Int, aa: String, mw: Int, mh: Int) {
        val m = layout(w, h)
        assertEquals("$w x $h aa", aa, m.aaResolution)
        assertEquals("$w x $h marginW", mw, m.marginW)
        assertEquals("$w x $h marginH", mh, m.marginH)
        assertEquals(w, m.outW)
        assertEquals(h, m.outH)
        assertEquals(160, m.density)
    }

    @Test
    fun reproducesExistingModes() {
        assertLayout(1920, 640, "1920x1080", 0, 440)       // WIDE_640
        assertLayout(1280, 640, "1280x720", 0, 80)         // WIDE_2_1
        assertLayout(2240, 976, "1920x1080", 0, 244)       // WIDE_976
        assertLayout(1024, 600, "1280x720", 256, 120)      // R1024 (вне диапазона Dev: высота < 640)
        assertLayout(800, 480, "800x480", 0, 0)            // R800
        assertLayout(1920, 720, "1920x1080", 0, 360)       // WIDE_HD
        assertLayout(1728, 648, "1920x1080", 192, 432)     // WIDE_MID
        assertLayout(1280, 480, "1280x720", 0, 240)        // WIDE
    }

    @Test
    fun reproducesPresetGeometryAgainstEnum() {
        for (m in listOf(VideoMode.WIDE, VideoMode.WIDE_MID, VideoMode.WIDE_HD, VideoMode.WIDE_640, VideoMode.WIDE_2_1, VideoMode.WIDE_976)) {
            val c = layout(m.outW, m.outH)
            assertEquals(m.name, m.aaResolution, c.aaResolution)
            assertEquals(m.name, m.marginW, c.marginW)
            assertEquals(m.name, m.marginH, c.marginH)
            assertEquals(m.name, m.crop, c.crop)
        }
    }

    @Test
    fun wide976Area() {
        val m = layout(2240, 976)
        assertEquals(1920, m.areaW)
        assertEquals(836, m.areaH)
        assertEquals(1.167, m.scale, 0.001)
    }

    @Test
    fun validationRejectsBadValues() {
        assertNull(CustomVideoMode.from(639, 640))
        assertNull(CustomVideoMode.from(1000, 639))
        assertNull(CustomVideoMode.from(2242, 800))
        assertNull(CustomVideoMode.from(1281, 720))        // нечётная ширина
        assertNull(CustomVideoMode.from(1280, 721))        // нечётная высота
        assertNull(CustomVideoMode.from(1920, 1082))       // высота > 1080
        assertNull(CustomVideoMode.from(700, 1000))        // вертикальный
        assertNull(CustomVideoMode.from(0, 0))
        assertEquals(CustomResError.WIDTH_RANGE, CustomVideoMode.check(2242, 800))
        assertEquals(CustomResError.HEIGHT_RANGE, CustomVideoMode.check(1920, 1082))
        assertEquals(CustomResError.ODD, CustomVideoMode.check(1281, 720))
        assertEquals(CustomResError.PORTRAIT, CustomVideoMode.check(700, 1000))
        assertNull(CustomVideoMode.check(640, 640))
        assertNull(CustomVideoMode.check(2240, 1080))
        assertEquals(CustomVideoMode(2240, 976), CustomVideoMode.from(2240, 976))
    }

    @Test
    fun marginsAndAreaOnGrid() {
        val plates = setOf("800x480", "1280x720", "1920x1080")
        for (w in 640..2240 step 10) {
            for (h in 640..1080 step 10) {
                if (w < h) continue
                val m = CustomVideoMode.from(w, h) ?: error("$w x $h должен быть валиден")
                val tag = "$w x $h"
                assertTrue(tag, m.aaResolution in plates)
                assertTrue(tag, m.marginW >= 0 && m.marginH >= 0)
                assertTrue(tag, m.marginW % 2 == 0 && m.marginH % 2 == 0)
                assertTrue(tag, m.areaW > 0 && m.areaH > 0)
                assertEquals(tag, m.aaW - m.marginW, m.areaW)
                assertEquals(tag, m.aaH - m.marginH, m.areaH)
                assertTrue(tag, m.needsTranscode)
                // без масштаба область = кадр, иначе вписанный аспект
                if (w <= 1920) assertTrue(tag, m.areaW == w && m.areaH == h)
                else assertEquals(tag, w.toDouble() / h, m.areaW.toDouble() / m.areaH, 0.01)
            }
        }
    }

    @Test
    fun touchMatchesWide976() {
        val c = layout(2240, 976)
        val e = VideoMode.WIDE_976
        val pts = listOf(0 to 0, 2239 to 975, 1120 to 488, 3000 to 2000, 57 to 601, 2239 to 0, 0 to 975, -5 to -300)
        for ((x, y) in pts) assertEquals("$x,$y", e.mapTouch(x, y), c.mapTouch(x, y))
        assertEquals(0 to 0, c.mapTouch(0, 0))                       // углы кадра -> углы области
        assertEquals(1919 to 835, c.mapTouch(2239, 975))
        assertEquals(1919 to 0, c.mapTouch(2239, 0))
        assertEquals(0 to 835, c.mapTouch(0, 975))
    }

    @Test
    fun touchWithoutScaleIsContentSpace() {
        val c = layout(1920, 640)
        assertEquals(VideoMode.WIDE_640.mapTouch(2000, 900), c.mapTouch(2000, 900))
        assertEquals(57 to 601, c.mapTouch(57, 601))
        assertEquals(7 to 9, layout(1280, 720).mapTouch(7, 9))        // полей нет - 1:1
    }

    @Test
    fun bitrateClamp() {
        assertEquals(2_000_000, layout(640, 640).bitrate)             // 1.78M -> нижний предел
        assertEquals(4_000_000, layout(1280, 720).bitrate)
        assertEquals(6_000_000, layout(1920, 720).bitrate)
        assertEquals(8_000_000, layout(2240, 1080).bitrate)           // верхний предел
        assertEquals(8_000_000, layout(2240, 976).bitrate)
        assertEquals(8_000_000, layout(1920, 1080).bitrate)           // 9M -> верхний предел
    }

    @Test
    fun transcodeIsMandatory() {
        assertTrue(layout(1280, 720).needsTranscode)                   // даже без полей
        assertSame(VideoMode.R720, layout(2240, 976).effective(transcode = false))
        assertSame(VideoMode.R720, layout(1280, 720).effective(transcode = false))
        assertEquals(layout(2240, 976), layout(2240, 976).effective(transcode = true))
    }

    @Test
    fun equalityByValue() {
        assertEquals(CustomVideoMode(2240, 976), CustomVideoMode(2240, 976))
        assertNotEquals(CustomVideoMode(2240, 976), CustomVideoMode(2240, 978))
        val custom: VideoGeometry = CustomVideoMode(1280, 720)
        val preset: VideoGeometry = VideoMode.R720
        assertNotEquals(custom, preset)
    }

    @Test
    fun resolveFallsBackToPreset() {
        assertSame(VideoMode.WIDE, CustomVideoMode.resolve(false, 2240, 976, VideoMode.WIDE))
        assertEquals(CustomVideoMode(2240, 976), CustomVideoMode.resolve(true, 2240, 976, VideoMode.WIDE))
        // невалидные сохранённые значения = пресет
        assertSame(VideoMode.WIDE, CustomVideoMode.resolve(true, 639, 976, VideoMode.WIDE))
        assertSame(VideoMode.WIDE, CustomVideoMode.resolve(true, 1281, 720, VideoMode.WIDE))
        assertSame(VideoMode.WIDE, CustomVideoMode.resolve(true, 700, 1000, VideoMode.WIDE))
        assertSame(VideoMode.WIDE, CustomVideoMode.resolve(true, 0, 0, VideoMode.WIDE))
    }

    @Test
    fun describeLine() {
        assertEquals(
            "custom 2240x976 ← AA 1920x1080 поля 0x244 ×1.167 dpi 160 8M",
            layout(2240, 976).describe(),
        )
        assertEquals(
            "custom 1280x640 ← AA 1280x720 поля 0x80 ×1.000 dpi 200 3.6M",
            layout(1280, 640).describe(200),
        )
        assertEquals("custom 2240x976", layout(2240, 976).label)
        assertEquals("2240×976", layout(2240, 976).outLabel)
    }

    @Test
    fun sdrUsesPlateAndMargins() {
        val o = layout(2240, 976).sdOptions(fullAudio = false)
        assertEquals("1920x1080", o.resolution)
        assertEquals(244, o.marginH)
        assertEquals(0, o.marginW)
        assertEquals(160, o.density)
        assertEquals(120, layout(2240, 976).sdOptions(false, 120).density)
    }
}
