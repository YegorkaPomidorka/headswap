package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/** Настройки моста, которые выставляет выбор машины в разделе «Мой автомобиль». */
data class CarSettings(
    val transcode: Boolean = true,
    val videoMode: VideoMode = VideoMode.DEFAULT,
    val aaDensity: Int = AaDensity.AUTO,
    val fps: FpsSetting = FpsSetting.DEFAULT,
    val musicRate: MusicRate = MusicRate.DEFAULT,
)

/** Корейские ГУ слабые: на 30 кадрах и 48 кГц рвут USB, советуем 1280×720, 15 кадров, 32 кГц (h139, h218, h260). */
private val KOREAN = CarSettings(fps = FpsSetting.FPS15, musicRate = MusicRate.HZ32)

/**
 * Подтверждённые машины (README, «Совместимость») и проверенные сообществом настройки для каждой.
 * Выбор машины разом пишет [settings] в AaPrefs, дальше любую настройку можно поменять вручную.
 * По умолчанию - то, с чем машина подтверждена (1280×720, кадры и музыка «Авто»); отдельные значения только проверенные живьём.
 * Особенности ГУ, которые мост определяет сам (Hyundai CN7 - музыка 32 кГц в «Авто», VW ID - потолок битрейта), здесь не дублируются.
 * Имя элемента хранится в prefs: не переименовывать.
 */
enum class CarPreset(val titleRes: Int, val settings: CarSettings = CarSettings()) {
    /** ГУ 1920×640 (h269). */
    CITROEN_C5_X(R.string.car_citroen_c5_x, CarSettings(videoMode = VideoMode.WIDE_640)),
    /** Экран 1024×600, слабый USB ГУ (h277): мост сам форсирует перекодирование и потолок 800 кбит/с. */
    DONGFENG_DF6_2023(R.string.car_dongfeng_df6_2023, CarSettings(videoMode = VideoMode.R1024)),
    PEUGEOT_5008_2024(R.string.car_peugeot_5008_2024),
    HONDA_BREEZE_2(R.string.car_honda_breeze_2),
    /** h243: 1920×1080, «работает достойно». */
    HONDA_CRV_6(R.string.car_honda_crv_6, CarSettings(videoMode = VideoMode.R1080)),
    /** SEMIDRIVE X9, экран 1920×720; на кадрах «Авто» полосы и мерцание (h72). */
    HONDA_CRV_2026(R.string.car_honda_crv_2026, CarSettings(videoMode = VideoMode.WIDE_HD, fps = FpsSetting.FPS30)),
    HONDA_CRV_2026_HYBRID(R.string.car_honda_crv_2026_hybrid, CarSettings(videoMode = VideoMode.WIDE_HD, fps = FpsSetting.FPS30)),
    /** Машина Егора: 1920×1080, 30 кадров. */
    HONDA_VEZEL_2022(R.string.car_honda_vezel_2022, CarSettings(videoMode = VideoMode.R1080, fps = FpsSetting.FPS30)),
    HYUNDAI_ELANTRA_7(R.string.car_hyundai_elantra_7, KOREAN),
    /** HSAE, вертикальный экран: 1280×480 работает шустро (h86). */
    HYUNDAI_IX35_2021(R.string.car_hyundai_ix35_2021, KOREAN.copy(videoMode = VideoMode.WIDE)),
    JETTA_VS(R.string.car_jetta_vs),
    KIA_K3_2023(R.string.car_kia_k3_2023, KOREAN),
    KIA_SPORTAGE_ACE(R.string.car_kia_sportage_ace, KOREAN),

    /** Desay SV G6S: нативный экран 1728×648. */
    MAZDA_CX5_2025(R.string.car_mazda_cx5_2025, CarSettings(videoMode = VideoMode.WIDE_MID)),
    /** alps, CarLife 4.1: 1920×720 (h166). */
    MAZDA_CX50(R.string.car_mazda_cx50, CarSettings(videoMode = VideoMode.WIDE_HD)),
    /** yftech: заработало на 15 кадрах (h10, h41). */
    MITSUBISHI_OUTLANDER_4(R.string.car_mitsubishi_outlander_4, CarSettings(fps = FpsSetting.FPS15)),
    SKODA_KAMIQ_GT_2021(R.string.car_skoda_kamiq_gt_2021),
    /** CNS3 просит 2:1, 1280×640 (h292); на 1080p виснет (h305). */
    SKODA_OCTAVIA_PRO(R.string.car_skoda_octavia_pro, CarSettings(videoMode = VideoMode.WIDE_2_1)),
    TOYOTA_COROLLA_CROSS_2024(R.string.car_toyota_corolla_cross_2024),
    TOYOTA_LEVIN(R.string.car_toyota_levin),
    /** Китай: 1920×1080, 30 кадров (Егор, 18.10). */
    VW_BORA_2021(R.string.car_vw_bora_2021, CarSettings(videoMode = VideoMode.R1080, fps = FpsSetting.FPS30)),
    /** 1920×1080, остальное «Авто» (@Aleksandr_456_55). */
    VW_GOLF_8_PRO(R.string.car_vw_golf_8_pro, CarSettings(videoMode = VideoMode.R1080)),
    VW_ID_UNYX(R.string.car_vw_id_unyx),
    VW_LAMANDO_L(R.string.car_vw_lamando_l),
    VW_SAGITAR_2021(R.string.car_vw_sagitar_2021),
    VW_TROC_2026(R.string.car_vw_troc_2026),
    VW_THARU_XR(R.string.car_vw_tharu_xr),

    /** VW CNS3: окно CarLife 2240×976 на экране 2240×1260. */
    VW_TIGUAN_L_PRO(R.string.car_vw_tiguan_l_pro, CarSettings(videoMode = VideoMode.WIDE_976));

    companion object {
        /** По имени из prefs; неизвестное/пустое = машина не выбрана. */
        fun parse(key: String?): CarPreset? = values().firstOrNull { it.name == key }
    }
}
