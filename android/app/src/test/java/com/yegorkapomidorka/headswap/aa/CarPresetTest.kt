package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPresetTest {
    @Test
    fun presetsApplyAsWritten() {
        // режим с кропом без перекодирования молча стал бы 1280×720: такой пресет «изменён вручную» сразу после выбора
        for (p in CarPreset.values()) {
            assertEquals(p.name, p.settings.videoMode, p.settings.videoMode.effective(p.settings.transcode))
            assertEquals(p.name, p.settings.aaDensity, AaDensity.parse(p.settings.aaDensity))
        }
    }

    @Test
    fun verifiedValues() {
        assertEquals(VideoMode.WIDE_MID, CarPreset.MAZDA_CX5_2025.settings.videoMode)
        assertEquals(VideoMode.WIDE_976, CarPreset.VW_TIGUAN_L_PRO.settings.videoMode)
        assertEquals(CarSettings(videoMode = VideoMode.WIDE_HD, fps = FpsSetting.FPS30), CarPreset.HONDA_CRV_2026.settings)
        assertEquals(CarSettings(videoMode = VideoMode.R1080, fps = FpsSetting.FPS30), CarPreset.HONDA_VEZEL_2022.settings)
        assertEquals(CarSettings(), CarPreset.VW_THARU_XR.settings)
        assertEquals(CarSettings(fps = FpsSetting.FPS15, musicRate = MusicRate.HZ32), CarPreset.KIA_K3_2023.settings)
        assertEquals(VideoMode.WIDE, CarPreset.HYUNDAI_IX35_2021.settings.videoMode)
        assertEquals(MusicRate.HZ32, CarPreset.HYUNDAI_IX35_2021.settings.musicRate)
    }

    @Test
    fun newResolutionPresets() {
        assertEquals(VideoMode.WIDE_640, CarPreset.CITROEN_C5_X.settings.videoMode)
        assertEquals(VideoMode.WIDE_2_1, CarPreset.SKODA_OCTAVIA_PRO.settings.videoMode)
        assertEquals(VideoMode.R1024, CarPreset.DONGFENG_DF6_2023.settings.videoMode)
    }

    @Test
    fun parse() {
        for (p in CarPreset.values()) assertEquals(p, CarPreset.parse(p.name))
        assertNull(CarPreset.parse(null))
        assertNull(CarPreset.parse("NO_SUCH_CAR"))
    }
}
