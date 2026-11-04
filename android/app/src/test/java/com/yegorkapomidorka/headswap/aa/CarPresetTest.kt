package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPresetTest {
    @Test
    fun presetsApplyAsWritten() {
        // режим с кропом без перекодирования молча стал бы 1280×720: такой пресет «изменён вручную» сразу после выбора
        for (p in CarPreset.values()) {
            // VW Tiguan L Pro: WIDE_976 масштабирует область, SPS-кропом не режется; в прямом пробросе это WIDE_836, до 2240×976 растягивает ГУ (h529)
            val expected = if (p == CarPreset.VW_TIGUAN_L_PRO) VideoMode.WIDE_836 else p.settings.videoMode
            assertEquals(p.name, expected, p.settings.videoMode.effective(p.settings.transcode))
            assertEquals(p.name, p.settings.aaDensity, AaDensity.parse(p.settings.aaDensity))
        }
    }

    @Test
    fun verifiedValues() {
        assertEquals(VideoMode.WIDE_MID, CarPreset.MAZDA_CX5_2025.settings.videoMode)
        assertEquals(VideoMode.WIDE, CarPreset.MAZDA_3_CN_2025.settings.videoMode)
        assertEquals(VideoMode.WIDE_976, CarPreset.VW_TIGUAN_L_PRO.settings.videoMode)
        assertEquals(CarSettings(videoMode = VideoMode.WIDE_HD), CarPreset.HONDA_CRV_2026.settings)
        assertEquals(CarSettings(videoMode = VideoMode.R1080), CarPreset.HONDA_VEZEL_2022.settings)
        assertEquals(CarSettings(), CarPreset.VW_THARU_XR.settings)
        assertEquals(CarSettings(musicRate = MusicRate.HZ32), CarPreset.KIA_K3_2023.settings)
        assertEquals(VideoMode.WIDE, CarPreset.HYUNDAI_IX35_2021.settings.videoMode)
        assertEquals(MusicRate.HZ32, CarPreset.HYUNDAI_IX35_2021.settings.musicRate)
    }

    @Test
    fun allPresetsDirectPassthroughAutoFps() {
        for (p in CarPreset.values()) {
            if (p == CarPreset.HYUNDAI_ELANTRA_7) continue      // CN7: проброс моргает (h638), перекодирование 1280×480 / 15
            // Tucson NX4c: вертикальный кадр проверен только с перекодированием (h670)
            assertEquals(p.name, p == CarPreset.HYUNDAI_TUCSON_NX4C, p.settings.transcode)
            assertEquals(p.name, FpsSetting.AUTO, p.settings.fps)
        }
        with(CarPreset.HYUNDAI_ELANTRA_7.settings) {
            assertEquals(true, transcode)
            assertEquals(FpsSetting.FPS15, fps)
            assertEquals(VideoMode.WIDE, videoMode)
        }
    }

    @Test
    fun newResolutionPresets() {
        assertEquals(VideoMode.WIDE_640, CarPreset.CITROEN_C5_X.settings.videoMode)
        assertEquals(VideoMode.WIDE_2_1, CarPreset.SKODA_OCTAVIA_PRO.settings.videoMode)
        assertEquals(VideoMode.WIDE_2_1, CarPreset.VW_DISCOVER_PRO_9_2.settings.videoMode)
        assertEquals(VideoMode.R1024, CarPreset.DONGFENG_DF6_2023.settings.videoMode)
    }

    @Test
    fun parse() {
        for (p in CarPreset.values()) assertEquals(p, CarPreset.parse(p.name))
        assertNull(CarPreset.parse(null))
        assertNull(CarPreset.parse("NO_SUCH_CAR"))
    }
}
