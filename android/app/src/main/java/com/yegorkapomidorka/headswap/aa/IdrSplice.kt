package com.yegorkapomidorka.headswap.aa

/**
 * Свой ключевой кадр в passthrough без перекодирования: IDR из декодированной на телефоне картинки AA, без потерь.
 *
 * Декодеры H.264 восстанавливают кадр бит-в-бит одинаково, поэтому картинка из декодера телефона = опорный кадр,
 * на который ссылаются следующие P-кадры AA. IDR из макроблоков I_PCM (сырые пиксели) или I16x16 без остатка
 * (блок целиком совпадает со своим предсказанием) даёт ровно эту картинку, и поток AA идёт дальше без дрейфа.
 * IDR обнуляет frame_num и POC, поэтому в P-кадрах AA до его следующего IDR эти поля сдвигаются ([IdrSplicer.process]).
 * Деблокинг такой IDR не трогает: у I_PCM qP = 0, у I16x16 QP слайса 0, alpha(0) = 0.
 *
 * Проверено на потоках x264 (Baseline, POC 0 и 2, несколько слайсов): ffmpeg после склейки и при входе с нашего IDR
 * даёт те же кадры, что исходный поток. Ограничения: CAVLC, 4:2:0 8 бит, кадры (не поля), max_num_ref_frames = 1.
 */
object IdrSplice {
    class Sps(
        val id: Int, val log2Fn: Int, val pocType: Int, val log2Poc: Int, val dpoZero: Boolean,
        val maxRef: Int, val wMbs: Int, val hMbs: Int, val chroma: Int, val bitDepth: Int, val frameMbsOnly: Boolean,
    ) {
        val codedW: Int get() = wMbs * 16
        val codedH: Int get() = hMbs * 16
    }

    class Pps(
        val id: Int, val spsId: Int, val cabac: Boolean, val bottomPoc: Boolean, val sliceGroups: Int,
        val initQp: Int, val dbkCtrl: Boolean, val redundant: Boolean, val chromaQpOffset: Int = 0,
    )

    /** Картинка I420 кодированного размера (с полями под кроп SPS), строки без выравнивания. */
    class Picture(val width: Int, val height: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray) {
        fun copy() = Picture(width, height, y.copyOf(), u.copyOf(), v.copyOf())
    }

    /** Свой IDR: NAL-ы (Annex-B у [IdrSplicer.build], без старт-кода у buildIdr). qp = -1 - без потерь, иначе QP сжатия. */
    class Result(val nal: ByteArray, val i16: Int, val pcm: Int, val qp: Int = -1)

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** nal - SPS без стартового кода. */
    fun parseSps(nal: ByteArray): Sps {
        val r = Bits(SpsCrop.removeEmulationPrevention(nal))
        r.skip(8)
        val profile = r.u(8)
        r.skip(16)
        val id = r.ue()
        var chroma = 1
        var depth = 8
        if (profile in HIGH_PROFILES) {
            chroma = r.ue()
            if (chroma == 3) r.skip(1)
            depth = 8 + r.ue()
            r.ue()
            r.skip(1)
            if (r.u(1) == 1) {                          // seq_scaling_matrix: остатка у нас нет, матрица не важна
                for (k in 0 until if (chroma != 3) 8 else 12) if (r.u(1) == 1) skipScalingList(r, if (k < 6) 16 else 64)
            }
        }
        val log2Fn = r.ue() + 4
        val pocType = r.ue()
        var log2Poc = 0
        var dpoZero = true
        when (pocType) {
            0 -> log2Poc = r.ue() + 4
            1 -> {
                dpoZero = r.u(1) == 1
                r.se(); r.se()
                repeat(r.ue()) { r.se() }
            }
        }
        val maxRef = r.ue()
        r.skip(1)
        val w = r.ue() + 1
        val h = r.ue() + 1
        val frameMbsOnly = r.u(1) == 1
        return Sps(id, log2Fn, pocType, log2Poc, dpoZero, maxRef, w, if (frameMbsOnly) h else h * 2, chroma, depth, frameMbsOnly)
    }

    /** nal - PPS без стартового кода. */
    fun parsePps(nal: ByteArray): Pps {
        val r = Bits(SpsCrop.removeEmulationPrevention(nal))
        r.skip(8)
        val id = r.ue()
        val spsId = r.ue()
        val cabac = r.u(1) == 1
        val bottomPoc = r.u(1) == 1
        val groups = r.ue() + 1
        if (groups > 1) return Pps(id, spsId, cabac, bottomPoc, groups, 26, false, false)
        r.ue(); r.ue()                                  // num_ref_idx_l0/l1_default_active_minus1
        r.skip(3)                                       // weighted_pred_flag, weighted_bipred_idc
        val initQp = 26 + r.se()
        r.se()                                          // pic_init_qs
        val cqp = r.se()
        val dbk = r.u(1) == 1
        r.skip(1)                                       // constrained_intra_pred_flag: у нас все макроблоки intra
        val redundant = r.u(1) == 1
        return Pps(id, spsId, cabac, bottomPoc, groups, initQp, dbk, redundant, cqp)
    }

    /** Почему по этим SPS/PPS свой IDR не собрать; null - можно. */
    fun unsupported(sps: Sps, pps: Pps): String? = when {
        pps.cabac -> "CABAC"
        sps.chroma != 1 -> "chroma_format_idc=${sps.chroma}"
        sps.bitDepth != 8 -> "${sps.bitDepth} бит"
        !sps.frameMbsOnly -> "поля (interlace)"
        pps.sliceGroups > 1 -> "FMO (${pps.sliceGroups} групп слайсов)"
        sps.maxRef > 1 -> "max_num_ref_frames=${sps.maxRef}: P-кадры AA могут ссылаться на кадры до нашего IDR"
        else -> null
    }

    /** IDR (NAL без стартового кода, с эмуляцией) с картинкой pic; idrPicId меняется от IDR к IDR. */
    /** Сколько макроблоков IDR без потерь уйдёт в I_PCM (остальные - I16x16 без остатка, по 2-3 байта). */
    fun pcmCount(pic: Picture, sps: Sps): Int {
        var n = 0
        for (my in 0 until sps.hMbs) for (mx in 0 until sps.wMbs) {
            if (Intra.luma16(pic, mx, my) < 0 || Intra.chroma(pic, mx, my) < 0) n++
        }
        return n
    }

    fun buildIdr(pic: Picture, sps: Sps, pps: Pps, idrPicId: Int, useI16: Boolean = true): Result {
        require(pic.width == sps.codedW && pic.height == sps.codedH) { "картинка ${pic.width}x${pic.height}, в SPS ${sps.codedW}x${sps.codedH}" }
        val w = BitOut(sps.wMbs * sps.hMbs * 200 + 64)
        w.u(8, 0x65)                                    // nal_ref_idc 3, IDR
        w.ue(0)                                         // first_mb_in_slice
        w.ue(7)                                         // slice_type I, все слайсы картинки
        w.ue(pps.id)
        w.u(sps.log2Fn, 0)                              // frame_num
        w.ue(idrPicId)
        when (sps.pocType) {
            0 -> {
                w.u(sps.log2Poc, 0)
                if (pps.bottomPoc) w.se(0)
            }
            1 -> if (!sps.dpoZero) {
                w.se(0)
                if (pps.bottomPoc) w.se(0)
            }
        }
        if (pps.redundant) w.ue(0)
        w.u(2, 0)                                       // no_output_of_prior_pics_flag, long_term_reference_flag
        w.se(-pps.initQp)                               // QP слайса 0: деблокинг I16x16 не срабатывает
        if (pps.dbkCtrl) w.ue(1)                        // disable_deblocking_filter_idc
        val mw = sps.wMbs
        val kind = ByteArray(mw * sps.hMbs)            // 1 = I_PCM, 2 = I16x16 без остатка
        var i16 = 0
        for (my in 0 until sps.hMbs) for (mx in 0 until mw) {
            val lm = if (useI16) Intra.luma16(pic, mx, my) else -1
            val cm = if (lm >= 0) Intra.chroma(pic, mx, my) else -1
            if (cm >= 0) {
                kind[my * mw + mx] = 2
                i16++
                w.ue(1 + lm)                            // I_16x16_<lm>_0_0
                w.ue(cm)                                // intra_chroma_pred_mode
                w.se(0)                                 // mb_qp_delta
                // coeff_token Intra16x16DCLevel, TotalCoeff = 0; nN соседа I_PCM = 16, I16x16 без AC = 0
                val na = if (mx > 0) (if (kind[my * mw + mx - 1].toInt() == 1) 16 else 0) else -1
                val nb = if (my > 0) (if (kind[(my - 1) * mw + mx].toInt() == 1) 16 else 0) else -1
                val nc = if (na >= 0 && nb >= 0) (na + nb + 1) shr 1 else if (na >= 0) na else if (nb >= 0) nb else 0
                when {
                    nc < 2 -> w.u(1, 1)
                    nc < 4 -> w.u(2, 3)
                    nc < 8 -> w.u(4, 15)
                    else -> w.u(6, 3)
                }
            } else {
                kind[my * mw + mx] = 1
                w.ue(25)                                // I_PCM
                w.alignZero()
                for (y in 0 until 16) w.bytes(pic.y, (my * 16 + y) * pic.width + mx * 16, 16)
                val cw = pic.width / 2
                for (y in 0 until 8) w.bytes(pic.u, (my * 8 + y) * cw + mx * 8, 8)
                for (y in 0 until 8) w.bytes(pic.v, (my * 8 + y) * cw + mx * 8, 8)
            }
        }
        w.u(1, 1)                                       // rbsp_stop_one_bit
        w.alignZero()
        return Result(SpsCrop.addEmulationPrevention(w.toByteArray()), i16, mw * sps.hMbs - i16)
    }

    private fun skipScalingList(r: Bits, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }

    /** Предсказания intra 16x16 и chroma 8x8 (H.264 8.3.3, 8.3.4) по соседям из той же картинки. */
    private object Intra {
        /** Режим I16x16 (0 V, 1 H, 2 DC, 3 Plane), который даёт блок точно, или -1. */
        fun luma16(p: Picture, mx: Int, my: Int): Int {
            val s = p.width
            val x0 = mx * 16
            val y0 = my * 16
            val pl = p.y
            if (my > 0 && block(pl, s, x0, y0, 16) { x, _ -> px(pl, s, x0 + x, y0 - 1) }) return 0
            if (mx > 0 && block(pl, s, x0, y0, 16) { _, y -> px(pl, s, x0 - 1, y0 + y) }) return 1
            val dc = dc(pl, s, x0, y0, 16, mx > 0, my > 0)
            if (block(pl, s, x0, y0, 16) { _, _ -> dc }) return 2
            if (mx > 0 && my > 0 && plane(pl, s, x0, y0, 16)) return 3
            return -1
        }

        /** Режим chroma (0 DC, 1 H, 2 V, 3 Plane), который даёт оба блока Cb и Cr точно, или -1. */
        fun chroma(p: Picture, mx: Int, my: Int): Int {
            val s = p.width / 2
            val x0 = mx * 8
            val y0 = my * 8
            for (m in 0..3) if (chromaFits(p.u, s, x0, y0, m, mx > 0, my > 0) && chromaFits(p.v, s, x0, y0, m, mx > 0, my > 0)) return m
            return -1
        }

        private fun chromaFits(pl: ByteArray, s: Int, x0: Int, y0: Int, m: Int, left: Boolean, top: Boolean): Boolean = when (m) {
            0 -> (0..1).all { by ->
                (0..1).all { bx ->
                    val xo = bx * 4
                    val yo = by * 4
                    val t = if (top) (0 until 4).sumOf { px(pl, s, x0 + xo + it, y0 - 1) } else -1
                    val l = if (left) (0 until 4).sumOf { px(pl, s, x0 - 1, y0 + yo + it) } else -1
                    val v = when {
                        (xo == 0 && yo == 0) || (xo > 0 && yo > 0) -> when {
                            t >= 0 && l >= 0 -> (t + l + 4) shr 3
                            l >= 0 -> (l + 2) shr 2
                            t >= 0 -> (t + 2) shr 2
                            else -> 128
                        }
                        xo > 0 -> if (t >= 0) (t + 2) shr 2 else if (l >= 0) (l + 2) shr 2 else 128
                        else -> if (l >= 0) (l + 2) shr 2 else if (t >= 0) (t + 2) shr 2 else 128
                    }
                    block(pl, s, x0 + xo, y0 + yo, 4) { _, _ -> v }
                }
            }
            1 -> left && block(pl, s, x0, y0, 8) { _, y -> px(pl, s, x0 - 1, y0 + y) }
            2 -> top && block(pl, s, x0, y0, 8) { x, _ -> px(pl, s, x0 + x, y0 - 1) }
            else -> left && top && plane(pl, s, x0, y0, 8)
        }

        private fun dc(pl: ByteArray, s: Int, x0: Int, y0: Int, n: Int, left: Boolean, top: Boolean): Int {
            val sh = if (n == 16) 4 else 3
            val t = if (top) (0 until n).sumOf { px(pl, s, x0 + it, y0 - 1) } else 0
            val l = if (left) (0 until n).sumOf { px(pl, s, x0 - 1, y0 + it) } else 0
            return when {
                top && left -> (t + l + n) shr (sh + 1)
                left -> (l + n / 2) shr sh
                top -> (t + n / 2) shr sh
                else -> 128
            }
        }

        /** Plane: n = 16 (luma) или 8 (chroma 4:2:0). При i = half - 1 разность берёт угловой сосед (x0 - 1, y0 - 1). */
        private fun plane(pl: ByteArray, s: Int, x0: Int, y0: Int, n: Int): Boolean {
            val half = n / 2
            var hh = 0
            var vv = 0
            for (i in 0 until half) {
                hh += (i + 1) * (px(pl, s, x0 + half + i, y0 - 1) - px(pl, s, x0 + half - 2 - i, y0 - 1))
                vv += (i + 1) * (px(pl, s, x0 - 1, y0 + half + i) - px(pl, s, x0 - 1, y0 + half - 2 - i))
            }
            val a = 16 * (px(pl, s, x0 - 1, y0 + n - 1) + px(pl, s, x0 + n - 1, y0 - 1))
            val k = if (n == 16) 5 else 34
            val b = (k * hh + 32) shr 6
            val c = (k * vv + 32) shr 6
            val o = half - 1
            return block(pl, s, x0, y0, n) { x, y -> ((a + b * (x - o) + c * (y - o) + 16) shr 5).coerceIn(0, 255) }
        }

        private fun px(pl: ByteArray, s: Int, x: Int, y: Int): Int = pl[y * s + x].toInt() and 0xFF

        private inline fun block(pl: ByteArray, s: Int, x0: Int, y0: Int, n: Int, pred: (Int, Int) -> Int): Boolean {
            for (y in 0 until n) for (x in 0 until n) if (px(pl, s, x0 + x, y0 + y) != pred(x, y)) return false
            return true
        }
    }

    internal class Bits(private val d: ByteArray) {
        var pos = 0

        fun skip(n: Int) {
            pos += n
        }

        fun u(n: Int): Int {
            var v = 0
            repeat(n) {
                v = (v shl 1) or ((d[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }

        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) if (++zeros > 31) throw IllegalArgumentException("Exp-Golomb длиннее 32 бит")
            return ((1L shl zeros) - 1 + u(zeros)).toInt()
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }

    /** Запись битов в растущий буфер; PCM-байты на выровненной позиции копируются целиком. */
    internal class BitOut(cap: Int) {
        private var buf = ByteArray(cap)
        private var len = 0
        private var cur = 0
        private var n = 0

        fun u(bits: Int, v: Int) {
            for (k in bits - 1 downTo 0) {
                cur = (cur shl 1) or ((v ushr k) and 1)
                if (++n == 8) {
                    put(cur)
                    cur = 0
                    n = 0
                }
            }
        }

        fun ue(v: Int) {
            val x = v + 1
            val bits = 32 - Integer.numberOfLeadingZeros(x)
            u(bits - 1, 0)
            u(bits, x)
        }

        fun se(v: Int) = ue(if (v > 0) 2 * v - 1 else -2 * v)

        fun alignZero() {
            while (n != 0) u(1, 0)
        }

        fun bytes(src: ByteArray, off: Int, count: Int) {
            check(n == 0)
            ensure(count)
            System.arraycopy(src, off, buf, len, count)
            len += count
        }

        private fun put(b: Int) {
            ensure(1)
            buf[len++] = b.toByte()
        }

        private fun ensure(more: Int) {
            if (len + more > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + more))
        }

        fun toByteArray(): ByteArray = buf.copyOf(len)
    }
}

/**
 * Склейка потока AA со своими IDR (см. [IdrSplice]). Каждый кадр AA проходит через [process]: SPS/PPS запоминаются,
 * у P-кадров после нашего IDR сдвигаются frame_num и POC, IDR от AA сдвиг снимает. Свой IDR заменяет в потоке
 * ГУ последнюю картинку AA ([build] -> отправка -> [commit]); после него может идти только следующий кадр AA.
 */
class IdrSplicer {
    private val spsMap = HashMap<Int, IdrSplice.Sps>()
    private val ppsMap = HashMap<Int, IdrSplice.Pps>()

    /** Номер последней картинки AA (по первому слайсу); 0 - картинок ещё не было. */
    var seq = 0L
        private set
    private var lastPps: IdrSplice.Pps? = null
    private var lastRef = false
    private var lastFn = 0
    private var lastPoc = 0
    private var fnOff = -1                         // -1: сдвига нет (после IDR от AA)
    private var pocOff = 0
    private var idrPicId = 0
    var spliced = 0L
        private set

    /** SPS/PPS из CODEC_CONFIG или из кадра. */
    fun onConfig(au: ByteArray) {
        for (nal in nals(au)) remember(nal)
    }

    /** Кадр AA (Annex-B, 4-байтные старт-коды): учесть его и вернуть с переписанными под наш IDR полями. */
    fun process(au: ByteArray): ByteArray {
        var changed = false
        val out = ArrayList<ByteArray>()
        for (nal in nals(au)) {
            val t = nal[0].toInt() and 0x1F
            if (t == 7 || t == 8) remember(nal)
            if (t != 1 && t != 5) {
                out.add(nal)
                continue
            }
            val rbsp = SpsCrop.removeEmulationPrevention(nal)
            val h = header(rbsp, t)
            if (h == null) {
                out.add(nal)
                continue
            }
            if (h.firstMb == 0) {
                seq++
                if (t == 5) fnOff = -1
                lastPps = h.pps
                lastRef = (nal[0].toInt() ushr 5) and 3 != 0
                lastFn = h.fn
                lastPoc = h.poc
            }
            if (fnOff < 0 || t == 5) {
                out.add(nal)
                continue
            }
            val sps = spsMap[h.pps.spsId]!!
            writeBits(rbsp, h.fnPos, sps.log2Fn, (h.fn - fnOff) and ((1 shl sps.log2Fn) - 1))
            if (h.pocPos >= 0) writeBits(rbsp, h.pocPos, sps.log2Poc, (h.poc - pocOff) and ((1 shl sps.log2Poc) - 1))
            out.add(SpsCrop.addEmulationPrevention(rbsp))
            changed = true
        }
        if (!changed) return au
        return join(out)
    }

    /** Почему последнюю картинку AA сейчас нельзя заменить своим IDR; null - можно. */
    fun blocker(): String? {
        val pps = lastPps ?: return "кадров AA ещё не было"
        val sps = spsMap[pps.spsId] ?: return "нет SPS"
        if (!lastRef) return "последний кадр AA не опорный"
        return IdrSplice.unsupported(sps, pps)
    }

    /** Кодированный размер последней картинки (по её SPS) или null. */
    fun codedSize(): Pair<Int, Int>? {
        val sps = lastPps?.let { spsMap[it.spsId] } ?: return null
        return sps.codedW to sps.codedH
    }

    /** Снимок для сборки своего IDR на другой нити: параметры потока у картинки seq. */
    class Job internal constructor(
        val seq: Long, internal val sps: IdrSplice.Sps, internal val pps: IdrSplice.Pps,
        internal val idrPicId: Int, internal val fn: Int, internal val poc: Int,
    )

    /** Снимок для последней картинки AA; null - вставка сейчас невозможна ([blocker]). */
    fun prepare(): Job? {
        if (blocker() != null) return null
        val pps = lastPps!!
        return Job(seq, spsMap[pps.spsId]!!, pps, idrPicId and 0xFFFF, lastFn, lastPoc)
    }

    /** IDR без потерь с картинкой pic (декодированная последняя картинка AA), Annex-B. Только при blocker() == null. */
    fun build(pic: IdrSplice.Picture): IdrSplice.Result {
        val job = prepare()!!
        val r = IdrSplice.buildIdr(pic, job.sps, job.pps, job.idrPicId)
        return IdrSplice.Result(START + r.nal, r.i16, r.pcm)
    }

    /** Наш IDR ушёл на ГУ вместо последней картинки: следующие кадры AA считаются от него. */
    fun commit() {
        commit(prepare()!!)
    }

    /**
     * Наш IDR по снимку job ушёл на ГУ. Картинка job должна быть последней от AA (между ней и IDR кадров не было).
     * job.fn/poc - исходные поля AA, поэтому повторный IDR той же картинки (статичный экран) даёт тот же сдвиг.
     */
    fun commit(job: Job) {
        check(job.seq == seq) { "свой IDR #${job.seq} устарел: последняя картинка #$seq" }
        idrPicId = job.idrPicId + 1
        spliced++
        fnOff = job.fn
        pocOff = job.poc
    }

    private class Header(val firstMb: Int, val pps: IdrSplice.Pps, val fnPos: Int, val fn: Int, val pocPos: Int, val poc: Int)

    private fun header(rbsp: ByteArray, type: Int): Header? = try {
        val r = IdrSplice.Bits(rbsp)
        r.skip(8)
        val firstMb = r.ue()
        r.ue()                                          // slice_type
        val pps = ppsMap[r.ue()]
        val sps = pps?.let { spsMap[it.spsId] }
        if (pps == null || sps == null) null else {
            val fnPos = r.pos
            val fn = r.u(sps.log2Fn)
            if (!sps.frameMbsOnly && r.u(1) == 1) r.skip(1)
            if (type == 5) r.ue()                       // idr_pic_id
            var pocPos = -1
            var poc = 0
            if (sps.pocType == 0) {
                pocPos = r.pos
                poc = r.u(sps.log2Poc)
            }
            Header(firstMb, pps, fnPos, fn, pocPos, poc)
        }
    } catch (e: RuntimeException) {
        null
    }

    private fun remember(nal: ByteArray) {
        try {
            when (nal[0].toInt() and 0x1F) {
                7 -> IdrSplice.parseSps(nal).let { spsMap[it.id] = it }
                8 -> IdrSplice.parsePps(nal).let { ppsMap[it.id] = it }
            }
        } catch (e: RuntimeException) {
            // битый SPS/PPS: слайсы с ним не разберутся, кадры уйдут как есть
        }
    }

    private fun writeBits(buf: ByteArray, pos: Int, n: Int, v: Int) {
        for (k in 0 until n) {
            val p = pos + k
            val m = 0x80 ushr (p and 7)
            val i = p ushr 3
            buf[i] = (if ((v ushr (n - 1 - k)) and 1 == 1) buf[i].toInt() or m else buf[i].toInt() and m.inv()).toByte()
        }
    }

    companion object {
        private val START = byteArrayOf(0, 0, 0, 1)

        /** NAL-ы access unit без старт-кодов (старт-коды 3 или 4 байта). */
        fun nals(au: ByteArray): List<ByteArray> {
            val starts = ArrayList<Int>()
            val bodies = ArrayList<Int>()
            var i = 0
            while (i + 2 < au.size) {
                if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) {
                    starts.add(if (i > 0 && au[i - 1].toInt() == 0) i - 1 else i)
                    bodies.add(i + 3)
                    i += 3
                } else i++
            }
            return bodies.indices.map { k ->
                var end = if (k + 1 < starts.size) starts[k + 1] else au.size
                while (end > bodies[k] && au[end - 1].toInt() == 0) end--
                au.copyOfRange(bodies[k], end)
            }.filter { it.isNotEmpty() }
        }

        fun join(nals: List<ByteArray>): ByteArray {
            val out = ByteArray(nals.sumOf { it.size + 4 })
            var p = 0
            for (n in nals) {
                System.arraycopy(START, 0, out, p, 4)
                System.arraycopy(n, 0, out, p + 4, n.size)
                p += n.size + 4
            }
            return out
        }
    }
}

/** Куда декодер опорных картинок отдаёт результат (ядро моста). Вызовы идут с нити декодера. */
interface RefPictureSink {
    /** Нужна ли картинка seq: копия кадра (1,4 МБ на 720p) делается, только если она ещё последняя. */
    fun wantsRefPicture(seq: Long): Boolean

    /** Картинка seq кодированного размера; объект переиспользуется, после возврата его содержимое меняется. */
    fun onRefPicture(seq: Long, pic: IdrSplice.Picture)

    /** Картинки не будет до конца AA-сессии (вывод декодера не покрывает кодированный кадр); зовётся один раз. */
    fun onRefUnavailable(why: String)
}

/** Декодер потока AA для своего IDR в passthrough ([IdrSplice]); MediaCodec-реализация - RefDecoder. */
interface RefPictureDecoder {
    /** CODEC_CONFIG AA (SPS+PPS). */
    fun feedConfig(annexB: ByteArray)

    /** Кадр AA с номером картинки seq ([IdrSplicer.seq]); не блокирует. */
    fun feed(annexB: ByteArray, seq: Long)

    fun stop()
}

/**
 * Сборка своего IDR по снимку [IdrSplicer.Job] на нити сборки. Если IDR без потерь (I_PCM / I16x16 без остатка) не больше
 * [target] - он: картинка на ГУ бит-в-бит. Иначе сжатый ([IdrIntra]) на [slices] слайсах: QP от прошлого кадра,
 * не влез - QP выше (до 3 попыток), сильно меньше цели - в следующий раз QP ниже. В статичных местах ГУ показывает
 * картинку этого IDR до следующего IDR, поэтому QP держится низким, насколько позволяет размер.
 */
class IdrBuilder(
    private val pool: java.util.concurrent.ExecutorService?,
    private val slices: Int,
    val target: Int = TARGET_BYTES,
    private val minQp: Int = MIN_QP,
    startQp: Int = START_QP,
) {
    @Volatile var qp = maxOf(startQp, minQp)
        private set

    fun build(job: IdrSplicer.Job, pic: IdrSplice.Picture): IdrSplice.Result {
        val sps = job.sps
        val mbs = sps.wMbs * sps.hMbs
        val pcm = IdrSplice.pcmCount(pic, sps)
        if (pcm * PCM_BYTES + (mbs - pcm) * 3 + 64 <= target) {
            val r = IdrSplice.buildIdr(pic, sps, job.pps, job.idrPicId)
            return IdrSplice.Result(START + r.nal, r.i16, r.pcm)
        }
        var q = qp
        var nal: ByteArray
        var attempt = 0
        while (true) {
            val c = IdrIntra.encode(pic, sps, job.pps, job.idrPicId, q, slices, pool)
            nal = IdrSplicer.join(c.slices)
            if (nal.size <= target || ++attempt >= 3 || q >= MAX_QP) break
            // размер падает примерно вдвое на +6 QP
            val step = Math.ceil(6 * Math.log(nal.size.toDouble() / target) / Math.log(2.0)).toInt().coerceIn(2, 12)
            q = (q + step).coerceAtMost(MAX_QP)
        }
        qp = if (nal.size < target / 2) (q - 2).coerceAtLeast(minQp) else q
        return IdrSplice.Result(nal, mbs, 0, q)
    }

    companion object {
        const val TARGET_BYTES = 300_000        // с запасом до буфера ГУ (SPLICE_MAX_BYTES в мосте)
        const val START_QP = 22
        const val MIN_QP = 18
        const val MAX_QP = 40
        private const val PCM_BYTES = 388
        private val START = byteArrayOf(0, 0, 0, 1)
    }
}
