package com.yegorkapomidorka.headswap.aa

/** SPS/PPS у своего IDR: через раз (как в PR #115), всегда или никогда (кроме восстановления, когда на ГУ нет IDR). */
enum class SpliceSps(val label: String) {
    ALTERNATE("через раз"),
    ALWAYS("всегда"),
    NEVER("без SPS/PPS");

    companion object {
        fun parse(s: String?): SpliceSps = values().firstOrNull { it.name == s } ?: ALTERNATE
    }
}

/**
 * Варианты своего IDR для поиска накопления задержки на ГУ (docs/research/idr-latency.md, раздел для разработчиков).
 * По умолчанию всё как в PR #115. Читается на старте CarLife-сессии.
 */
data class SpliceTuning(
    /** IDR уходит вместо кадра AA, с которого собран (кадр придерживается), а не лишним кадром после него. */
    val replace: Boolean = false,
    val sps: SpliceSps = SpliceSps.ALTERNATE,
    /** Лёгкий IDR: цель [LIGHT_TARGET_BYTES], QP не ниже [LIGHT_MIN_QP]. Быстрее сборка и декодирование на ГУ. */
    val light: Boolean = false,
    /** Слайсов в сжатом IDR. */
    val slices: Int = AaBridgeCore.SPLICE_SLICES,
    /** В SPS к ГУ VUI bitstream_restriction: max_num_reorder_frames = 0, max_dec_frame_buffering = max_num_ref_frames. */
    val lowDelayVui: Boolean = false,
    /** Метка времени телефона (секунды.сотые) в правом верхнем углу своего IDR: замер задержки съёмкой экрана. */
    val stamp: Boolean = false,
    /** Метка времени VIDEO_DATA в миллисекундах (как CarProjection), а не в секундах. */
    val tsMs: Boolean = false,
    /**
     * Сколько кадров AA перед картинкой своего IDR не уходит в ГУ (в движении): IDR встаёт вместо drop + 1 кадров AA,
     * очередь ГУ с постоянной частотой показа укорачивается на drop кадров. P-кадр после IDR ссылается только на его картинку.
     */
    val drop: Int = 0,
) {
    val isDefault: Boolean get() = this == SpliceTuning()

    /** IDR встаёт на место кадра AA: сам по себе ([replace]) или с выбросом кадров перед ним ([drop]). */
    val replacing: Boolean get() = replace || drop > 0

    fun describe(): String = if (isDefault) "как в PR #115" else buildList {
        if (drop > 0) add("IDR вместо ${drop + 1} кадров AA (выброс $drop)")
        else if (replace) add("IDR вместо кадра AA")
        if (sps != SpliceSps.ALTERNATE) add("SPS/PPS ${sps.label}")
        if (light) add("лёгкий IDR (${LIGHT_TARGET_BYTES / 1000} КБ, QP от $LIGHT_MIN_QP)")
        if (slices != AaBridgeCore.SPLICE_SLICES) add("слайсов $slices")
        if (lowDelayVui) add("VUI без переупорядочивания")
        if (stamp) add("метка времени в кадре")
        if (tsMs) add("метка VIDEO_DATA в мс")
    }.joinToString(", ")

    companion object {
        const val LIGHT_TARGET_BYTES = 120_000
        const val LIGHT_MIN_QP = 24
        const val LIGHT_START_QP = 26
        val SLICE_CHOICES = listOf(1, 2, 4, 8)
        val DROP_CHOICES = listOf(0, 1, 2)
    }
}

/**
 * Модель ГУ, которая показывает кадры с постоянной частотой [fps] без пропусков (по своим часам или по счётчику кадров).
 * Тогда каждый кадр сверх [fps] в секунду остаётся в очереди навсегда, а уходит очередь только в паузах потока.
 * Счёт задержки этой модели в логе сравнивается с замером на экране: совпадают - ГУ устроена так.
 */
class GuQueueModel(val fps: Int = 30) {
    private var level = 0.0
    private var last = Long.MIN_VALUE
    var maxMs = 0L
        private set

    fun onFrame(nowMs: Long) {
        decay(nowMs)
        level += 1.0
        val ms = delayMs(nowMs)
        if (ms > maxMs) maxMs = ms
    }

    /** Задержка показа последнего кадра в модели: кадры в очереди перед ним, мс. */
    fun delayMs(nowMs: Long): Long {
        decay(nowMs)
        return ((level - 1.0).coerceAtLeast(0.0) * 1000 / fps).toLong()
    }

    fun resetMax() {
        maxMs = 0
    }

    /** ГУ сбросила декодер (новая CarLife-сессия, IDR от AA после потери): очередь пуста. */
    fun reset() {
        level = 0.0
        last = Long.MIN_VALUE
    }

    private fun decay(nowMs: Long) {
        if (last != Long.MIN_VALUE && nowMs > last) level = (level - (nowMs - last) * fps / 1000.0).coerceAtLeast(0.0)
        if (nowMs > last) last = nowMs
    }
}

/**
 * Разбор и правка VUI в SPS. ГУ без bitstream_restriction может держать в DPB до MaxDpbFrames уровня кадров перед
 * выводом (C.4.5.3, «bumping»); max_num_reorder_frames = 0 разрешает выводить кадр сразу после декодирования.
 */
object SpsVui {
    class Info(
        val profile: Int, val level: Int, val pocType: Int, val maxRef: Int, val vui: Boolean,
        val timing: String?, val hrd: Boolean, val reorder: Int?, val maxDecBuf: Int?,
    ) {
        override fun toString(): String = "profile $profile level $level, POC type $pocType, max_num_ref_frames $maxRef, " +
            if (!vui) "VUI нет" else "VUI: " + listOfNotNull(
                timing?.let { "timing $it" },
                if (hrd) "HRD" else null,
                if (reorder != null) "max_num_reorder_frames $reorder, max_dec_frame_buffering $maxDecBuf" else "bitstream_restriction нет",
            ).joinToString(", ")
    }

    private class Parsed(val rbsp: ByteArray, val info: Info, val vuiFlagPos: Int, val restrictionFlagPos: Int, val restriction: IntArray?)

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** nal - SPS без стартового кода. */
    fun parse(nal: ByteArray): Info = parseInternal(nal).info

    /** SPS с bitstream_restriction: max_num_reorder_frames = 0, max_dec_frame_buffering = max(1, max_num_ref_frames). */
    fun lowDelay(nal: ByteArray): ByteArray {
        val p = parseInternal(nal)
        if (p.info.reorder == 0 && p.info.maxDecBuf == maxOf(1, p.info.maxRef)) return nal
        val w = IdrSplice.BitOut(p.rbsp.size + 16)
        if (!p.info.vui) {
            copy(w, p.rbsp, 0, p.vuiFlagPos)
            w.u(1, 1)                                   // vui_parameters_present_flag
            w.u(8, 0)                                   // aspect, overscan, video_signal, chroma_loc, timing, nal/vcl HRD, pic_struct
        } else {
            copy(w, p.rbsp, 0, p.restrictionFlagPos)
        }
        val r = p.restriction ?: intArrayOf(1, 2, 1, 16, 16)
        w.u(1, 1)                                       // bitstream_restriction_flag
        w.u(1, r[0])                                    // motion_vectors_over_pic_boundaries_flag
        w.ue(r[1]); w.ue(r[2]); w.ue(r[3]); w.ue(r[4])  // max_bytes_per_pic_denom, max_bits_per_mb_denom, log2_max_mv_length h/v
        w.ue(0)                                         // max_num_reorder_frames
        w.ue(maxOf(1, p.info.maxRef))                   // max_dec_frame_buffering
        w.u(1, 1)                                       // rbsp_stop_one_bit
        w.alignZero()
        return SpsCrop.addEmulationPrevention(w.toByteArray())
    }

    private fun copy(w: IdrSplice.BitOut, src: ByteArray, from: Int, to: Int) {
        for (p in from until to) w.u(1, (src[p ushr 3].toInt() ushr (7 - (p and 7))) and 1)
    }

    private fun parseInternal(nal: ByteArray): Parsed {
        require(nal.isNotEmpty() && nal[0].toInt() and 0x1F == 7) { "не SPS" }
        val rbsp = SpsCrop.removeEmulationPrevention(nal)
        val r = IdrSplice.Bits(rbsp)
        try {
            r.skip(8)
            val profile = r.u(8)
            r.skip(8)
            val level = r.u(8)
            r.ue()
            if (profile in HIGH_PROFILES) {
                val chroma = r.ue()
                if (chroma == 3) r.skip(1)
                r.ue(); r.ue()
                r.skip(1)
                if (r.u(1) == 1) for (k in 0 until if (chroma != 3) 8 else 12) if (r.u(1) == 1) skipScalingList(r, if (k < 6) 16 else 64)
            }
            r.ue()                                      // log2_max_frame_num_minus4
            val pocType = r.ue()
            when (pocType) {
                0 -> r.ue()
                1 -> {
                    r.skip(1); r.se(); r.se()
                    repeat(r.ue()) { r.se() }
                }
            }
            val maxRef = r.ue()
            r.skip(1)
            r.ue(); r.ue()
            if (r.u(1) == 0) r.skip(1)                  // frame_mbs_only_flag, mb_adaptive_frame_field_flag
            r.skip(1)                                   // direct_8x8_inference_flag
            if (r.u(1) == 1) repeat(4) { r.ue() }       // frame_cropping
            val vuiFlagPos = r.pos
            if (r.u(1) == 0) return Parsed(rbsp, Info(profile, level, pocType, maxRef, false, null, false, null, null), vuiFlagPos, -1, null)
            if (r.u(1) == 1 && r.u(8) == 255) r.skip(32)    // aspect_ratio_info, Extended_SAR
            if (r.u(1) == 1) r.skip(1)                  // overscan
            if (r.u(1) == 1) {                          // video_signal_type
                r.skip(4)
                if (r.u(1) == 1) r.skip(24)
            }
            if (r.u(1) == 1) {                          // chroma_loc_info
                r.ue(); r.ue()
            }
            var timing: String? = null
            if (r.u(1) == 1) {
                val units = r.u32()
                val scale = r.u32()
                val fixed = r.u(1) == 1
                timing = "$units/$scale${if (fixed) " fixed" else ""}"
            }
            val nalHrd = r.u(1) == 1
            if (nalHrd) skipHrd(r)
            val vclHrd = r.u(1) == 1
            if (vclHrd) skipHrd(r)
            if (nalHrd || vclHrd) r.skip(1)             // low_delay_hrd_flag
            r.skip(1)                                   // pic_struct_present_flag
            val restrictionFlagPos = r.pos
            var reorder: Int? = null
            var dec: Int? = null
            var restriction: IntArray? = null
            if (r.u(1) == 1) {
                restriction = intArrayOf(r.u(1), r.ue(), r.ue(), r.ue(), r.ue())
                reorder = r.ue()
                dec = r.ue()
            }
            val info = Info(profile, level, pocType, maxRef, true, timing, nalHrd || vclHrd, reorder, dec)
            return Parsed(rbsp, info, vuiFlagPos, restrictionFlagPos, restriction)
        } catch (e: IndexOutOfBoundsException) {
            throw IllegalArgumentException("SPS обрывается на бите ${r.pos}")
        }
    }

    private fun IdrSplice.Bits.u32(): Long = (u(16).toLong() shl 16) or u(16).toLong()

    private fun skipHrd(r: IdrSplice.Bits) {
        val cpbCnt = r.ue() + 1
        r.skip(8)                                       // bit_rate_scale, cpb_size_scale
        repeat(cpbCnt) {
            r.ue(); r.ue(); r.skip(1)
        }
        r.skip(20)                                      // 4 поля по 5 бит
    }

    private fun skipScalingList(r: IdrSplice.Bits, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }
}

/**
 * Метка времени в своём IDR: цифры шрифтом 5x7 (пиксель шрифта 4x4) на чёрной плашке, выровненной по макроблокам.
 * P-кадры AA ссылаются на картинку IDR, поэтому метка держится, пока AA не перерисует этот угол, и сменяется следующим IDR.
 */
object FrameStamp {
    private const val SCALE = 4
    private const val PAD = 8
    private val GLYPHS = mapOf(
        '0' to arrayOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
        '1' to arrayOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
        '2' to arrayOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        '3' to arrayOf("11111", "00010", "00100", "00010", "00001", "10001", "01110"),
        '4' to arrayOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        '5' to arrayOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
        '6' to arrayOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        '7' to arrayOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        '8' to arrayOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        '9' to arrayOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
        '.' to arrayOf("00000", "00000", "00000", "00000", "00000", "01100", "01100"),
    )

    /** Текст метки: секунды часов телефона по модулю 1000 с сотыми, «123.45». */
    fun text(wallMs: Long): String {
        val cs = Math.floorMod(wallMs / 10, 100_000L)
        return "%03d.%02d".format(cs / 100, cs % 100)
    }

    /** Ширина и высота плашки с текстом, кратные 16. */
    fun size(text: String): Pair<Int, Int> =
        align16(text.length * 6 * SCALE - SCALE + 2 * PAD) to align16(7 * SCALE + 2 * PAD)

    /** Плашка с текстом в pic, левый верхний угол (x0, y0) кратен 16 и плашка целиком в кадре; иначе ничего. */
    fun draw(pic: IdrSplice.Picture, x0: Int, y0: Int, text: String): Boolean {
        val (bw, bh) = size(text)
        if (x0 < 0 || y0 < 0 || x0 % 16 != 0 || y0 % 16 != 0 || x0 + bw > pic.width || y0 + bh > pic.height) return false
        for (y in 0 until bh) java.util.Arrays.fill(pic.y, (y0 + y) * pic.width + x0, (y0 + y) * pic.width + x0 + bw, 16.toByte())
        val cw = pic.width / 2
        for (y in 0 until bh / 2) {
            val off = (y0 / 2 + y) * cw + x0 / 2
            java.util.Arrays.fill(pic.u, off, off + bw / 2, 128.toByte())
            java.util.Arrays.fill(pic.v, off, off + bw / 2, 128.toByte())
        }
        for ((i, ch) in text.withIndex()) {
            val g = GLYPHS[ch] ?: continue
            val gx = x0 + PAD + i * 6 * SCALE
            for (row in 0 until 7) for (col in 0 until 5) {
                if (g[row][col] != '1') continue
                for (dy in 0 until SCALE) {
                    val off = (y0 + PAD + row * SCALE + dy) * pic.width + gx + col * SCALE
                    java.util.Arrays.fill(pic.y, off, off + SCALE, 235.toByte())
                }
            }
        }
        return true
    }

    private fun align16(v: Int) = (v + 15) / 16 * 16
}
