package com.yegorkapomidorka.headswap.aa

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService

/**
 * Свой IDR со сжатием: I16x16 с квантованным остатком (CAVLC), когда I_PCM без потерь не помещается в буфер ГУ.
 * Картинка на ГУ отличается от опорной AA на ошибку квантования; в статичных местах она держится до следующего IDR
 * (своего или от AA). Реконструкция считается ровно как у декодера (8.5.10-8.5.12 H.264, без деблокинга), от неё же
 * идёт intra-предсказание следующих блоков. Кадр режется на слайсы по строкам макроблоков, слайсы кодируются параллельно.
 */
internal object IdrIntra {
    /** Слайсы кадра (NAL без стартового кода, с эмуляцией) и реконструкция до деблокинга, если её просили. */
    class Coded(val slices: List<ByteArray>, val cbpMbs: Int)

    fun encode(
        pic: IdrSplice.Picture, sps: IdrSplice.Sps, pps: IdrSplice.Pps, idrPicId: Int, qp: Int, sliceCount: Int,
        pool: ExecutorService?, recon: IdrSplice.Picture? = null,
    ): Coded {
        val rows = sps.hMbs
        val n = sliceCount.coerceIn(1, rows)
        val rec = recon ?: IdrSplice.Picture(pic.width, pic.height, ByteArray(pic.y.size), ByteArray(pic.u.size), ByteArray(pic.v.size))
        val totals = Totals(sps.wMbs * sps.hMbs)
        val jobs = (0 until n).map { k ->
            val r0 = rows * k / n
            val r1 = rows * (k + 1) / n
            Callable { Slice(pic, rec, totals, sps, pps, qp, r0 * sps.wMbs, r1 * sps.wMbs).encode(idrPicId) }
        }
        val out = if (pool == null || n == 1) jobs.map { it.call() } else pool.invokeAll(jobs).map { it.get() }
        return Coded(out.map { it.first }, out.sumOf { it.second })
    }

    /** TotalCoeff блоков 4x4 (AC) для nC соседей: luma 16 на макроблок, chroma 4 на компоненту. */
    private class Totals(mbs: Int) {
        val luma = IntArray(mbs * 16)
        val cb = IntArray(mbs * 4)
        val cr = IntArray(mbs * 4)
    }

    private val ZIGZAG = intArrayOf(0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 10, 7, 11, 14, 15)
    /** luma4x4BlkIdx -> (x, y) блока 4x4 в макроблоке. */
    private val BLK_X = intArrayOf(0, 1, 0, 1, 2, 3, 2, 3, 0, 1, 0, 1, 2, 3, 2, 3)
    private val BLK_Y = intArrayOf(0, 0, 1, 1, 0, 0, 1, 1, 2, 2, 3, 3, 2, 2, 3, 3)
    private val MF = arrayOf(
        intArrayOf(13107, 5243, 8066), intArrayOf(11916, 4660, 7490), intArrayOf(10082, 4194, 6554),
        intArrayOf(9362, 3647, 5825), intArrayOf(8192, 3355, 5243), intArrayOf(7282, 2893, 4559),
    )
    private val V = arrayOf(
        intArrayOf(10, 16, 13), intArrayOf(11, 18, 14), intArrayOf(13, 20, 16),
        intArrayOf(14, 23, 18), intArrayOf(16, 25, 20), intArrayOf(18, 29, 23),
    )
    private val QPC = intArrayOf(29, 30, 31, 32, 32, 33, 34, 34, 35, 35, 36, 36, 37, 37, 37, 38, 38, 38, 39, 39, 39, 39)
    private const val MAX_LEVEL = 2000           // Baseline: level_prefix не больше 15

    private fun posClass(i: Int) = if (i / 4 % 2 == 0 && i % 4 % 2 == 0) 0 else if (i / 4 % 2 == 1 && i % 4 % 2 == 1) 1 else 2

    fun chromaQp(qp: Int, offset: Int): Int {
        val q = (qp + offset).coerceIn(0, 51)
        return if (q < 30) q else QPC[q - 30]
    }

    private class Slice(
        val pic: IdrSplice.Picture, val rec: IdrSplice.Picture, val totals: Totals,
        val sps: IdrSplice.Sps, val pps: IdrSplice.Pps, val qp: Int, val mbStart: Int, val mbEnd: Int,
    ) {
        val w = sps.wMbs
        val qpc = chromaQp(qp, pps.chromaQpOffset)
        val out = IdrSplice.BitOut((mbEnd - mbStart) * 64 + 64)
        // рабочие массивы макроблока
        val pred = IntArray(256)
        val tmp = IntArray(256)
        val coef = Array(16) { IntArray(16) }      // [blkRaster][i*4+j] уровни AC (позиция 0 не используется)
        val dc = IntArray(16)
        val cpred = Array(2) { IntArray(64) }
        val ccoef = Array(2) { Array(4) { IntArray(16) } }
        val cdc = Array(2) { IntArray(4) }
        val list = IntArray(16)
        val blk = IntArray(16)
        var cbpMbs = 0

        fun encode(idrPicId: Int): Pair<ByteArray, Int> {
            out.u(8, 0x65)
            out.ue(mbStart)
            out.ue(7)                                   // I, все слайсы картинки
            out.ue(pps.id)
            out.u(sps.log2Fn, 0)
            out.ue(idrPicId)
            when (sps.pocType) {
                0 -> {
                    out.u(sps.log2Poc, 0)
                    if (pps.bottomPoc) out.se(0)
                }
                1 -> if (!sps.dpoZero) {
                    out.se(0)
                    if (pps.bottomPoc) out.se(0)
                }
            }
            if (pps.redundant) out.ue(0)
            out.u(2, 0)                                 // no_output_of_prior_pics_flag, long_term_reference_flag
            out.se(qp - pps.initQp)
            if (pps.dbkCtrl) {
                out.ue(0)                               // деблокинг включён: на сжатом кадре он к лучшему
                out.se(0)
                out.se(0)
            }
            for (a in mbStart until mbEnd) mb(a)
            out.u(1, 1)
            out.alignZero()
            return SpsCrop.addEmulationPrevention(out.toByteArray()) to cbpMbs
        }

        fun availLeft(a: Int) = a % w > 0 && a - 1 >= mbStart
        fun availTop(a: Int) = a >= w && a - w >= mbStart

        fun mb(a: Int) {
            val mx = a % w
            val my = a / w
            val left = availLeft(a)
            val top = availTop(a)
            // --- luma: режим по SAD от реконструкции соседей
            var bestMode = 2
            var bestSad = Int.MAX_VALUE
            for (m in 0..3) {
                if (!lumaPred(m, mx, my, left, top, tmp)) continue
                val s = sad(pic.y, pic.width, mx * 16, my * 16, 16, tmp)
                if (s < bestSad) {
                    bestSad = s
                    bestMode = m
                    System.arraycopy(tmp, 0, pred, 0, 256)
                }
            }
            val q6 = qp / 6
            val qbits = 15 + q6
            val f = (1 shl qbits) / 3
            var anyAc = false
            for (b in 0 until 16) {
                val bx = b % 4
                val by = b / 4
                for (i in 0 until 4) for (j in 0 until 4) {
                    val x = mx * 16 + bx * 4 + j
                    val y = my * 16 + by * 4 + i
                    blk[i * 4 + j] = (pic.y[y * pic.width + x].toInt() and 0xFF) - pred[(by * 4 + i) * 16 + bx * 4 + j]
                }
                forward(blk)
                dc[b] = blk[0]
                val c = coef[b]
                c[0] = 0
                for (p in 1 until 16) {
                    c[p] = quant(blk[p], MF[qp % 6][posClass(p)], f, qbits)
                    if (c[p] != 0) anyAc = true
                }
            }
            val cbpL = if (anyAc) 15 else 0
            if (!anyAc) for (b in 0 until 16) coef[b].fill(0)
            // luma DC: Адамар 4x4, квант
            hadamard4(dc)
            val dcLevels = IntArray(16)
            for (p in 0 until 16) dcLevels[p] = quant(dc[p] / 2, MF[qp % 6][0], 2 * f, qbits + 1)
            // реконструкция luma
            val dcY = dcLevels.copyOf()
            hadamard4(dcY)
            val ls = 16 * V[qp % 6][0]
            for (p in 0 until 16) dcY[p] = if (qp >= 36) (dcY[p] * ls) shl (q6 - 6) else (dcY[p] * ls + (1 shl (5 - q6))) shr (6 - q6)
            for (b in 0 until 16) {
                val c = coef[b]
                for (p in 1 until 16) blk[p] = c[p] * V[qp % 6][posClass(p)] shl q6
                blk[0] = dcY[b]
                inverse(blk)
                val bx = b % 4
                val by = b / 4
                for (i in 0 until 4) for (j in 0 until 4) {
                    val px = pred[(by * 4 + i) * 16 + bx * 4 + j] + blk[i * 4 + j]
                    rec.y[(my * 16 + by * 4 + i) * rec.width + mx * 16 + bx * 4 + j] = px.coerceIn(0, 255).toByte()
                }
            }
            // --- chroma: режим по сумме SAD Cb + Cr
            val cw = pic.width / 2
            var cMode = 0
            var cBest = Int.MAX_VALUE
            for (m in 0..3) {
                if (!chromaPred(m, rec.u, cw, mx, my, left, top, tmp)) continue
                var s = sad(pic.u, cw, mx * 8, my * 8, 8, tmp)
                chromaPred(m, rec.v, cw, mx, my, left, top, tmp)
                s += sad(pic.v, cw, mx * 8, my * 8, 8, tmp)
                if (s < cBest) {
                    cBest = s
                    cMode = m
                }
            }
            chromaPred(cMode, rec.u, cw, mx, my, left, top, cpred[0])
            chromaPred(cMode, rec.v, cw, mx, my, left, top, cpred[1])
            val qc6 = qpc / 6
            val qcbits = 15 + qc6
            val fc = (1 shl qcbits) / 3
            var anyCAc = false
            var anyCDc = false
            for (k in 0..1) {
                val src = if (k == 0) pic.u else pic.v
                for (b in 0 until 4) {
                    val bx = b % 2
                    val by = b / 2
                    for (i in 0 until 4) for (j in 0 until 4) {
                        val x = mx * 8 + bx * 4 + j
                        val y = my * 8 + by * 4 + i
                        blk[i * 4 + j] = (src[y * cw + x].toInt() and 0xFF) - cpred[k][(by * 4 + i) * 8 + bx * 4 + j]
                    }
                    forward(blk)
                    cdc[k][b] = blk[0]
                    val c = ccoef[k][b]
                    c[0] = 0
                    for (p in 1 until 16) {
                        c[p] = quant(blk[p], MF[qpc % 6][posClass(p)], fc, qcbits)
                        if (c[p] != 0) anyCAc = true
                    }
                }
                val d = cdc[k]
                val y0 = d[0] + d[1] + d[2] + d[3]
                val y1 = d[0] - d[1] + d[2] - d[3]
                val y2 = d[0] + d[1] - d[2] - d[3]
                val y3 = d[0] - d[1] - d[2] + d[3]
                d[0] = quant(y0, MF[qpc % 6][0], 2 * fc, qcbits + 1)
                d[1] = quant(y1, MF[qpc % 6][0], 2 * fc, qcbits + 1)
                d[2] = quant(y2, MF[qpc % 6][0], 2 * fc, qcbits + 1)
                d[3] = quant(y3, MF[qpc % 6][0], 2 * fc, qcbits + 1)
                if (d.any { it != 0 }) anyCDc = true
            }
            val cbpC = if (anyCAc) 2 else if (anyCDc) 1 else 0
            for (k in 0..1) {
                if (cbpC < 2) for (b in 0 until 4) ccoef[k][b].fill(0)
                if (cbpC == 0) cdc[k].fill(0)
                val d = cdc[k]
                val f0 = d[0] + d[1] + d[2] + d[3]
                val f1 = d[0] - d[1] + d[2] - d[3]
                val f2 = d[0] + d[1] - d[2] - d[3]
                val f3 = d[0] - d[1] - d[2] + d[3]
                val lsc = 16 * V[qpc % 6][0]
                val dcC = intArrayOf(f0, f1, f2, f3).map { ((it * lsc) shl qc6) shr 5 }
                val dst = if (k == 0) rec.u else rec.v
                for (b in 0 until 4) {
                    val c = ccoef[k][b]
                    for (p in 1 until 16) blk[p] = c[p] * V[qpc % 6][posClass(p)] shl qc6
                    blk[0] = dcC[b]
                    inverse(blk)
                    val bx = b % 2
                    val by = b / 2
                    for (i in 0 until 4) for (j in 0 until 4) {
                        val px = cpred[k][(by * 4 + i) * 8 + bx * 4 + j] + blk[i * 4 + j]
                        dst[(my * 8 + by * 4 + i) * cw + mx * 8 + bx * 4 + j] = px.coerceIn(0, 255).toByte()
                    }
                }
            }
            // --- синтаксис макроблока
            if (cbpL != 0 || cbpC != 0) cbpMbs++
            out.ue(1 + bestMode + 4 * cbpC + if (cbpL == 15) 12 else 0)
            out.ue(cMode)
            out.se(0)                                   // mb_qp_delta
            for (p in 0 until 16) list[p] = dcLevels[ZIGZAG[p]]
            residual(list, 16, lumaNc(a, 0, 0, left, top))
            val lt = totals.luma
            for (b in 0 until 16) lt[a * 16 + b] = 0
            if (cbpL == 15) for (idx in 0 until 16) {
                val bx = BLK_X[idx]
                val by = BLK_Y[idx]
                val c = coef[by * 4 + bx]
                for (p in 1 until 16) list[p - 1] = c[ZIGZAG[p]]
                lt[a * 16 + by * 4 + bx] = residual(list, 15, lumaNc(a, bx, by, left, top))
            }
            if (cbpC != 0) for (k in 0..1) {
                for (p in 0 until 4) list[p] = cdc[k][p]
                residual(list, 4, -1)
            }
            for (k in 0..1) {
                val ct = if (k == 0) totals.cb else totals.cr
                for (b in 0 until 4) ct[a * 4 + b] = 0
                if (cbpC == 2) for (b in 0 until 4) {
                    val c = ccoef[k][b]
                    for (p in 1 until 16) list[p - 1] = c[ZIGZAG[p]]
                    ct[a * 4 + b] = residual(list, 15, chromaNc(ct, a, b % 2, b / 2, left, top))
                }
            }
        }

        fun lumaNc(a: Int, bx: Int, by: Int, left: Boolean, top: Boolean): Int {
            val lt = totals.luma
            val na = if (bx > 0) lt[a * 16 + by * 4 + bx - 1] else if (left) lt[(a - 1) * 16 + by * 4 + 3] else -1
            val nb = if (by > 0) lt[a * 16 + (by - 1) * 4 + bx] else if (top) lt[(a - w) * 16 + 12 + bx] else -1
            return nc(na, nb)
        }

        fun chromaNc(ct: IntArray, a: Int, bx: Int, by: Int, left: Boolean, top: Boolean): Int {
            val na = if (bx > 0) ct[a * 4 + by * 2] else if (left) ct[(a - 1) * 4 + by * 2 + 1] else -1
            val nb = if (by > 0) ct[a * 4 + bx] else if (top) ct[(a - w) * 4 + 2 + bx] else -1
            return nc(na, nb)
        }

        fun nc(na: Int, nb: Int) = if (na >= 0 && nb >= 0) (na + nb + 1) shr 1 else if (na >= 0) na else if (nb >= 0) nb else 0

        fun lumaPred(m: Int, mx: Int, my: Int, left: Boolean, top: Boolean, p: IntArray): Boolean {
            val s = rec.width
            val x0 = mx * 16
            val y0 = my * 16
            val r = rec.y
            fun px(x: Int, y: Int) = r[y * s + x].toInt() and 0xFF
            when (m) {
                0 -> {
                    if (!top) return false
                    for (y in 0 until 16) for (x in 0 until 16) p[y * 16 + x] = px(x0 + x, y0 - 1)
                }
                1 -> {
                    if (!left) return false
                    for (y in 0 until 16) for (x in 0 until 16) p[y * 16 + x] = px(x0 - 1, y0 + y)
                }
                2 -> {
                    var t = 0
                    var l = 0
                    if (top) for (x in 0 until 16) t += px(x0 + x, y0 - 1)
                    if (left) for (y in 0 until 16) l += px(x0 - 1, y0 + y)
                    val v = if (top && left) (t + l + 16) shr 5 else if (left) (l + 8) shr 4 else if (top) (t + 8) shr 4 else 128
                    p.fill(v, 0, 256)
                }
                else -> {
                    if (!top || !left) return false
                    var hh = 0
                    var vv = 0
                    for (i in 0 until 8) {
                        hh += (i + 1) * (px(x0 + 8 + i, y0 - 1) - px(x0 + 6 - i, y0 - 1))
                        vv += (i + 1) * (px(x0 - 1, y0 + 8 + i) - px(x0 - 1, y0 + 6 - i))
                    }
                    val aa = 16 * (px(x0 - 1, y0 + 15) + px(x0 + 15, y0 - 1))
                    val b = (5 * hh + 32) shr 6
                    val c = (5 * vv + 32) shr 6
                    for (y in 0 until 16) for (x in 0 until 16) p[y * 16 + x] = ((aa + b * (x - 7) + c * (y - 7) + 16) shr 5).coerceIn(0, 255)
                }
            }
            return true
        }

        /** Предсказание chroma 8x8 (8.3.4) в p[y * 8 + x]; false - режим недоступен. */
        fun chromaPred(m: Int, r: ByteArray, s: Int, mx: Int, my: Int, left: Boolean, top: Boolean, p: IntArray): Boolean {
            val x0 = mx * 8
            val y0 = my * 8
            fun px(x: Int, y: Int) = r[y * s + x].toInt() and 0xFF
            when (m) {
                0 -> for (by in 0..1) for (bx in 0..1) {
                    val xo = bx * 4
                    val yo = by * 4
                    var t = 0
                    var l = 0
                    if (top) for (i in 0 until 4) t += px(x0 + xo + i, y0 - 1)
                    if (left) for (i in 0 until 4) l += px(x0 - 1, y0 + yo + i)
                    val v = if ((xo == 0 && yo == 0) || (xo > 0 && yo > 0)) {
                        if (top && left) (t + l + 4) shr 3 else if (left) (l + 2) shr 2 else if (top) (t + 2) shr 2 else 128
                    } else if (xo > 0) {
                        if (top) (t + 2) shr 2 else if (left) (l + 2) shr 2 else 128
                    } else {
                        if (left) (l + 2) shr 2 else if (top) (t + 2) shr 2 else 128
                    }
                    for (i in 0 until 4) for (j in 0 until 4) p[(yo + i) * 8 + xo + j] = v
                }
                1 -> {
                    if (!left) return false
                    for (y in 0 until 8) for (x in 0 until 8) p[y * 8 + x] = px(x0 - 1, y0 + y)
                }
                2 -> {
                    if (!top) return false
                    for (y in 0 until 8) for (x in 0 until 8) p[y * 8 + x] = px(x0 + x, y0 - 1)
                }
                else -> {
                    if (!top || !left) return false
                    var hh = 0
                    var vv = 0
                    for (i in 0 until 4) {
                        hh += (i + 1) * (px(x0 + 4 + i, y0 - 1) - px(x0 + 2 - i, y0 - 1))
                        vv += (i + 1) * (px(x0 - 1, y0 + 4 + i) - px(x0 - 1, y0 + 2 - i))
                    }
                    val aa = 16 * (px(x0 - 1, y0 + 7) + px(x0 + 7, y0 - 1))
                    val b = (34 * hh + 32) shr 6
                    val c = (34 * vv + 32) shr 6
                    for (y in 0 until 8) for (x in 0 until 8) p[y * 8 + x] = ((aa + b * (x - 3) + c * (y - 3) + 16) shr 5).coerceIn(0, 255)
                }
            }
            return true
        }

        fun sad(src: ByteArray, s: Int, x0: Int, y0: Int, n: Int, p: IntArray): Int {
            var sum = 0
            for (y in 0 until n) {
                val row = (y0 + y) * s + x0
                for (x in 0 until n) sum += Math.abs((src[row + x].toInt() and 0xFF) - p[y * n + x])
            }
            return sum
        }

        /** residual_block_cavlc (7.3.5.3.2, 9.2) для n коэффициентов list; возвращает TotalCoeff. */
        fun residual(c: IntArray, n: Int, nC: Int): Int {
            val idx = IntArray(16)
            var tc = 0
            for (i in 0 until n) if (c[i] != 0) idx[tc++] = i
            var t1 = 0
            for (k in tc - 1 downTo 0) {
                if (Math.abs(c[idx[k]]) == 1 && t1 < 3) t1++ else break
            }
            coeffToken(nC, tc, t1)
            if (tc == 0) return 0
            var sl = if (tc > 10 && t1 < 3) 1 else 0
            for (k in 0 until tc) {
                val lv = c[idx[tc - 1 - k]]
                if (k < t1) {
                    out.u(1, if (lv < 0) 1 else 0)
                    continue
                }
                var code = if (lv > 0) 2 * lv - 2 else -2 * lv - 1
                if (k == t1 && t1 < 3) code -= 2
                level(code, sl)
                if (sl == 0) sl = 1
                if (Math.abs(lv) > (3 shl (sl - 1)) && sl < 6) sl++
            }
            var zl = 0
            if (tc < n) {
                zl = idx[tc - 1] + 1 - tc
                if (n == 4) out.u(CavlcTables.CHROMA_DC_TOTAL_ZEROS_LEN[tc - 1][zl], CavlcTables.CHROMA_DC_TOTAL_ZEROS_BITS[tc - 1][zl])
                else out.u(CavlcTables.TOTAL_ZEROS_LEN[tc - 1][zl], CavlcTables.TOTAL_ZEROS_BITS[tc - 1][zl])
            }
            for (k in tc - 1 downTo 1) {
                if (zl <= 0) break
                val run = idx[k] - idx[k - 1] - 1
                val t = minOf(zl, 7) - 1
                out.u(CavlcTables.RUN_LEN[t][run], CavlcTables.RUN_BITS[t][run])
                zl -= run
            }
            return tc
        }

        fun coeffToken(nC: Int, tc: Int, t1: Int) {
            val i = 4 * tc + t1
            if (nC < 0) {
                out.u(CavlcTables.CHROMA_DC_LEN[i], CavlcTables.CHROMA_DC_BITS[i])
                return
            }
            val t = if (nC < 2) 0 else if (nC < 4) 1 else if (nC < 8) 2 else 3
            out.u(CavlcTables.COEFF_LEN[t][i], CavlcTables.COEFF_BITS[t][i])
        }

        /** level_prefix / level_suffix (9.2.2.1) для levelCode при suffixLength sl. */
        fun level(code: Int, sl: Int) {
            val prefix: Int
            val suffixLen: Int
            val suffix: Int
            if (sl == 0) {
                when {
                    code < 14 -> { prefix = code; suffixLen = 0; suffix = 0 }
                    code < 30 -> { prefix = 14; suffixLen = 4; suffix = code - 14 }
                    else -> { prefix = 15; suffixLen = 12; suffix = code - 30 }
                }
            } else if (code < (15 shl sl)) {
                prefix = code shr sl
                suffixLen = sl
                suffix = code and ((1 shl sl) - 1)
            } else {
                prefix = 15
                suffixLen = 12
                suffix = code - (15 shl sl)
            }
            check(suffix < (1 shl suffixLen) || suffixLen == 0) { "уровень вне Baseline: levelCode $code" }
            out.u(prefix, 0)
            out.u(1, 1)
            if (suffixLen > 0) out.u(suffixLen, suffix)
        }
    }

    private fun quant(v: Int, mf: Int, f: Int, qbits: Int): Int {
        val z = ((Math.abs(v).toLong() * mf + f) shr qbits).toInt().coerceAtMost(MAX_LEVEL)
        return if (v < 0) -z else z
    }

    /** Прямое целочисленное преобразование 4x4 (b[i * 4 + j], i - строка). */
    private fun forward(b: IntArray) {
        for (i in 0 until 4) {
            val o = i * 4
            val s03 = b[o] + b[o + 3]
            val d03 = b[o] - b[o + 3]
            val s12 = b[o + 1] + b[o + 2]
            val d12 = b[o + 1] - b[o + 2]
            b[o] = s03 + s12
            b[o + 1] = 2 * d03 + d12
            b[o + 2] = s03 - s12
            b[o + 3] = d03 - 2 * d12
        }
        for (j in 0 until 4) {
            val s03 = b[j] + b[12 + j]
            val d03 = b[j] - b[12 + j]
            val s12 = b[4 + j] + b[8 + j]
            val d12 = b[4 + j] - b[8 + j]
            b[j] = s03 + s12
            b[4 + j] = 2 * d03 + d12
            b[8 + j] = s03 - s12
            b[12 + j] = d03 - 2 * d12
        }
    }

    /** Обратное преобразование 8.5.12.2 с округлением (x + 32) >> 6. */
    private fun inverse(d: IntArray) {
        for (i in 0 until 4) {
            val o = i * 4
            val e0 = d[o] + d[o + 2]
            val e1 = d[o] - d[o + 2]
            val e2 = (d[o + 1] shr 1) - d[o + 3]
            val e3 = d[o + 1] + (d[o + 3] shr 1)
            d[o] = e0 + e3
            d[o + 1] = e1 + e2
            d[o + 2] = e1 - e2
            d[o + 3] = e0 - e3
        }
        for (j in 0 until 4) {
            val g0 = d[j] + d[8 + j]
            val g1 = d[j] - d[8 + j]
            val g2 = (d[4 + j] shr 1) - d[12 + j]
            val g3 = d[4 + j] + (d[12 + j] shr 1)
            d[j] = (g0 + g3 + 32) shr 6
            d[4 + j] = (g1 + g2 + 32) shr 6
            d[8 + j] = (g1 - g2 + 32) shr 6
            d[12 + j] = (g0 - g3 + 32) shr 6
        }
    }

    /** Адамар 4x4 на месте (матрица симметрична: и прямой, и обратный). */
    private fun hadamard4(m: IntArray) {
        for (i in 0 until 4) {
            val o = i * 4
            val a = m[o] + m[o + 1]
            val b = m[o] - m[o + 1]
            val c = m[o + 2] + m[o + 3]
            val d = m[o + 2] - m[o + 3]
            m[o] = a + c
            m[o + 1] = a - c
            m[o + 2] = b - d
            m[o + 3] = b + d
        }
        for (j in 0 until 4) {
            val a = m[j] + m[4 + j]
            val b = m[j] - m[4 + j]
            val c = m[8 + j] + m[12 + j]
            val d = m[8 + j] - m[12 + j]
            m[j] = a + c
            m[4 + j] = a - c
            m[8 + j] = b - d
            m[12 + j] = b + d
        }
    }
}
