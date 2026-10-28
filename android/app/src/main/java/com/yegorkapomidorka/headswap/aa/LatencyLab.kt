package com.yegorkapomidorka.headswap.aa

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
 * Разбор SPS для лога. ГУ без bitstream_restriction может держать в DPB до MaxDpbFrames уровня кадров перед
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

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** nal - SPS без стартового кода. */
    fun parse(nal: ByteArray): Info {
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
            if (r.u(1) == 0) return Info(profile, level, pocType, maxRef, false, null, false, null, null)
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
            var reorder: Int? = null
            var dec: Int? = null
            if (r.u(1) == 1) {                          // bitstream_restriction
                r.skip(1)                               // motion_vectors_over_pic_boundaries_flag
                repeat(4) { r.ue() }                    // max_bytes_per_pic_denom, max_bits_per_mb_denom, log2_max_mv_length h/v
                reorder = r.ue()
                dec = r.ue()
            }
            return Info(profile, level, pocType, maxRef, true, timing, nalHrd || vclHrd, reorder, dec)
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
