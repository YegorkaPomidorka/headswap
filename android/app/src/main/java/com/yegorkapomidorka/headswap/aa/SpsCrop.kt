package com.yegorkapomidorka.headswap.aa

import java.io.ByteArrayOutputStream

/**
 * Кроп без перекодирования (h529, идея Димы): поля кадра AA отрезает декодер ГУ по frame_crop_* в SPS.
 * SPS разбирается до frame_cropping_flag, новые смещения пишутся заново, хвост (VUI) копируется побитно.
 * Остальные NAL потока не трогаются.
 */
object SpsCrop {
    /** Геометрия SPS в пикселях: кодированный кадр, смещения кропа и единица кропа по осям. */
    data class Info(
        val codedW: Int, val codedH: Int,
        val left: Int, val right: Int, val top: Int, val bottom: Int,
        val unitX: Int, val unitY: Int,
    ) {
        val visibleW: Int get() = codedW - left - right
        val visibleH: Int get() = codedH - top - bottom

        override fun toString() = "${codedW}x$codedH crop($left,$right,$top,$bottom) = ${visibleW}x$visibleH"
    }

    class Result(val sps: ByteArray, val before: Info, val after: Info)

    /** SPS не разобрался или кроп в него не ложится; сообщение идёт в лог. */
    class SpsException(message: String) : Exception(message)

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** Разбор до кропа: позиция бита frame_cropping_flag в RBSP и геометрия. */
    private class Parsed(val rbsp: ByteArray, val cropFlagPos: Int, val afterCropPos: Int, val info: Info)

    /** nal - SPS без стартового кода, с байтом заголовка NAL. */
    fun parse(nal: ByteArray): Info = parseInternal(nal).info

    /** Прибавить к кропу SPS поля в пикселях (каждое кратно единице кропа). */
    fun rewrite(nal: ByteArray, addL: Int, addR: Int, addT: Int, addB: Int): Result {
        val p = parseInternal(nal)
        val i = p.info
        if (addL % i.unitX != 0 || addR % i.unitX != 0 || addT % i.unitY != 0 || addB % i.unitY != 0)
            throw SpsException("поля $addL,$addR,$addT,$addB не кратны единице кропа ${i.unitX}x${i.unitY}")
        val after = i.copy(left = i.left + addL, right = i.right + addR, top = i.top + addT, bottom = i.bottom + addB)
        if (after.visibleW < 16 || after.visibleH < 16) throw SpsException("после кропа ничего не остаётся: $after")

        val r = BitReader(p.rbsp)
        val stop = r.lastOneBit() ?: throw SpsException("нет rbsp_stop_bit")
        if (stop < p.afterCropPos) throw SpsException("rbsp_stop_bit внутри полей кропа")
        val w = BitWriter()
        w.copy(p.rbsp, 0, p.cropFlagPos)
        w.bit(1)
        w.ue(after.left / i.unitX)
        w.ue(after.right / i.unitX)
        w.ue(after.top / i.unitY)
        w.ue(after.bottom / i.unitY)
        w.copy(p.rbsp, p.afterCropPos, stop)
        w.bit(1)
        w.align()
        return Result(addEmulationPrevention(w.toByteArray()), i, after)
    }

    /**
     * SPS без кропа: декодер отдаёт кадр кодированного размера (1920x1088 вместо 1920x1080). Декодирование кроп не меняет,
     * поэтому картинка та же, плюс скрытые строки, а они тоже опорные (свой IDR, IdrSplice). Без кропа - тот же массив.
     */
    fun uncrop(nal: ByteArray): ByteArray {
        val p = parseInternal(nal)
        val r = BitReader(p.rbsp)
        if (r.bitAt(p.cropFlagPos) == 0) return nal
        val stop = r.lastOneBit() ?: throw SpsException("нет rbsp_stop_bit")
        if (stop < p.afterCropPos) throw SpsException("rbsp_stop_bit внутри полей кропа")
        val w = BitWriter()
        w.copy(p.rbsp, 0, p.cropFlagPos)
        w.bit(0)
        w.copy(p.rbsp, p.afterCropPos, stop)
        w.bit(1)
        w.align()
        return addEmulationPrevention(w.toByteArray())
    }

    /** Применить transform к каждому SPS (тип 7) в access unit с 4-байтными стартовыми кодами; без SPS - тот же массив. */
    fun mapSps(au: ByteArray, transform: (ByteArray) -> ByteArray): ByteArray {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < au.size) {
            if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 0 && au[i + 3].toInt() == 1) {
                starts.add(i)
                i += 4
            } else i++
        }
        if (starts.none { it + 4 < au.size && au[it + 4].toInt() and 0x1F == 7 }) return au
        val out = ByteArrayOutputStream(au.size + 16)
        if (starts[0] > 0) out.write(au, 0, starts[0])
        for ((k, s) in starts.withIndex()) {
            val end = if (k + 1 < starts.size) starts[k + 1] else au.size
            val nal = au.copyOfRange(s + 4, end)
            out.write(au, s, 4)
            out.write(if (nal.isNotEmpty() && nal[0].toInt() and 0x1F == 7) transform(nal) else nal)
        }
        return out.toByteArray()
    }

    private fun parseInternal(nal: ByteArray): Parsed {
        if (nal.size < 4) throw SpsException("SPS короче 4 байт")
        if (nal[0].toInt() and 0x1F != 7) throw SpsException("NAL типа ${nal[0].toInt() and 0x1F}, не SPS")
        val rbsp = removeEmulationPrevention(nal)
        val r = BitReader(rbsp)
        try {
            r.skip(8)                                   // заголовок NAL
            val profile = r.u(8)
            r.skip(16)                                  // constraint flags, level_idc
            r.ue()                                      // seq_parameter_set_id
            var chroma = 1
            var separatePlanes = false
            if (profile in HIGH_PROFILES) {
                chroma = r.ue()
                if (chroma == 3) separatePlanes = r.u(1) == 1
                r.ue(); r.ue()                          // bit_depth_luma/chroma_minus8
                r.skip(1)                               // qpprime_y_zero_transform_bypass_flag
                if (r.u(1) == 1) {                      // seq_scaling_matrix_present_flag
                    for (k in 0 until if (chroma != 3) 8 else 12) {
                        if (r.u(1) == 1) skipScalingList(r, if (k < 6) 16 else 64)
                    }
                }
            }
            r.ue()                                      // log2_max_frame_num_minus4
            when (r.ue()) {                             // pic_order_cnt_type
                0 -> r.ue()
                1 -> {
                    r.skip(1); r.se(); r.se()
                    repeat(r.ue()) { r.se() }
                }
            }
            r.ue()                                      // max_num_ref_frames
            r.skip(1)                                   // gaps_in_frame_num_value_allowed_flag
            val wMbs = r.ue() + 1
            val hMapUnits = r.ue() + 1
            val frameMbsOnly = r.u(1)
            if (frameMbsOnly == 0) r.skip(1)            // mb_adaptive_frame_field_flag
            r.skip(1)                                   // direct_8x8_inference_flag
            val cropFlagPos = r.pos
            val cropped = r.u(1) == 1
            val c = if (cropped) IntArray(4) { r.ue() } else IntArray(4)
            val afterCropPos = r.pos

            val arrayType = if (separatePlanes) 0 else chroma
            val subW = if (arrayType == 1 || arrayType == 2) 2 else 1
            val subH = if (arrayType == 1) 2 else 1
            val unitX = if (arrayType == 0) 1 else subW
            val unitY = (if (arrayType == 0) 1 else subH) * (2 - frameMbsOnly)
            val codedW = wMbs * 16
            val codedH = hMapUnits * 16 * (2 - frameMbsOnly)
            val info = Info(codedW, codedH, c[0] * unitX, c[1] * unitX, c[2] * unitY, c[3] * unitY, unitX, unitY)
            return Parsed(rbsp, cropFlagPos, afterCropPos, info)
        } catch (e: IndexOutOfBoundsException) {
            throw SpsException("SPS обрывается на бите ${r.pos} из ${rbsp.size * 8}")
        }
    }

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }

    /** Убрать байты 0x03 из последовательностей 00 00 03. */
    fun removeEmulationPrevention(d: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(d.size)
        var zeros = 0
        for (b in d) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v == 3) {
                zeros = 0
                continue
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** Вставить 0x03 после 00 00, если следующий байт <= 3. */
    fun addEmulationPrevention(d: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(d.size + 4)
        var zeros = 0
        for (b in d) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitReader(private val d: ByteArray) {
        var pos = 0

        fun bitAt(p: Int): Int = (d[p ushr 3].toInt() ushr (7 - (p and 7))) and 1

        fun u(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or bitAt(pos++) }
            return v
        }

        fun skip(n: Int) {
            if (pos + n > d.size * 8) throw IndexOutOfBoundsException()
            pos += n
        }

        fun ue(): Int {
            var zeros = 0
            while (bitAt(pos++) == 0) {
                if (++zeros > 31) throw SpsException("Exp-Golomb длиннее 32 бит")
            }
            return ((1L shl zeros) - 1 + u(zeros)).toInt()
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }

        /** Позиция последней единицы (rbsp_stop_one_bit). */
        fun lastOneBit(): Int? {
            for (p in d.size * 8 - 1 downTo 0) if (bitAt(p) == 1) return p
            return null
        }
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var cur = 0
        private var n = 0

        fun bit(b: Int) {
            cur = (cur shl 1) or (b and 1)
            if (++n == 8) {
                out.write(cur)
                cur = 0
                n = 0
            }
        }

        fun ue(v: Int) {
            val x = v.toLong() + 1
            val len = 64 - java.lang.Long.numberOfLeadingZeros(x)
            repeat(len - 1) { bit(0) }
            for (k in len - 1 downTo 0) bit(((x ushr k) and 1).toInt())
        }

        /** Скопировать биты [from, to) из src. */
        fun copy(src: ByteArray, from: Int, to: Int) {
            for (p in from until to) bit((src[p ushr 3].toInt() ushr (7 - (p and 7))) and 1)
        }

        fun align() {
            while (n != 0) bit(0)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
