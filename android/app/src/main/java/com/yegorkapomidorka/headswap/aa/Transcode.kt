package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.AnnexB

/** A producer may render directly into this surface; its lifetime belongs to the encoder. */
interface SurfaceVideoInput {
    val surface: android.view.Surface
    val width: Int get() = DesktopVideoGeometry.WIDTH
    val height: Int get() = DesktopVideoGeometry.HEIGHT
    val density: Int get() = DesktopVideoGeometry.density
}

/** All callbacks run in order on a worker, never under the bridge monitor. */
interface DesktopSurfaceListener {
    fun onSurfaceAvailable(generation: Long, input: SurfaceVideoInput)

    /** Release the matching virtual display before returning, before its consumer is destroyed. */
    fun onSurfaceClosing(generation: Long)
    fun onFirstFrame(generation: Long) {}
}

/** Куда транскодер отдаёт результат (реализует ядро моста). Вызовы идут с нитей MediaCodec. */
interface TranscodeOutput {
    /** SPS+PPS энкодера (Annex-B, 4-байтные старт-коды). */
    fun onEncoderConfig(csd: ByteArray)

    /** Кадр энкодера (Annex-B). */
    fun onEncoderFrame(data: ByteArray, key: Boolean)

    fun onTranscodeError(msg: String)

    /** Декодеру нужен новый IDR от AA (кадр потерян). */
    fun onNeedAaKeyFrame()
}

/**
 * AA H.264 -> декодер (Surface) -> Surface входа энкодера -> H.264 для CarLife. Реализация на MediaCodec
 * (AaTranscoder); ядро моста работает с интерфейсом, чтобы логика проверялась на JVM.
 */
interface VideoTranscoder {
    /** Non-null only for an encoder accepting raw surface frames instead of compressed AA video. */
    val surfaceInput: SurfaceVideoInput? get() = null

    /**
     * CODEC_CONFIG AA (SPS+PPS). Новый SPS -> декодер будет пересоздан на следующем IDR.
     * Не блокирует, обрабатывается на нити транскодера; "nofeedthread" в tuning - старый синхронный путь.
     */
    fun feedConfig(annexB: ByteArray)

    /**
     * Кадр AA (access unit). Первый кадр декодеру должен быть IDR.
     * Не блокирует, обрабатывается на нити транскодера (очередь полна -> кадр теряется до IDR, onNeedAaKeyFrame);
     * "nofeedthread" в tuning - старый синхронный путь.
     */
    fun feedFrame(annexB: ByteArray, ptsUs: Long)

    /**
     * Сбросить декодер (новая AA-сессия): энкодер остаётся жить. Не блокирует, выполняется на нити транскодера
     * после уже принятых кадров; "nofeedthread" в tuning - старый синхронный путь.
     */
    fun resetDecoder()

    fun requestSyncFrame()

    /** Начать переход с кадра заглушки в картинку AA (зум-растворение) с первого следующего кадра. Без GL - ничего. */
    fun startIntro(banner: android.graphics.Bitmap) {}

    /** Целевая частота выхода (0 = не ограничивается). */
    val targetFps: Int get() = 0

    /** Сменить целевую частоту на лету, без пересоздания кодека. */
    fun setTargetFps(fps: Int) {}

    /** Текущий битрейт энкодера, бит/с (0 = неизвестен). */
    val currentBitrate: Int get() = 0

    /** Сменить битрейт энкодера на лету (адаптация под скорость слива ГУ), без пересоздания кодека. Ошибки не бросает. */
    fun setBitrate(bps: Int) {}

    /** Строка статистики за окно с прошлого вызова (задержка декодер->энкодер, fps, повторы). */
    fun statsLine(): String

    fun stop()
}

/** Разбор Annex-B потока H.264 (чистая JVM-логика): NAL-ы и access unit-ы. */
object H264Stream {
    class AccessUnit(val data: ByteArray, val idr: Boolean)

    /** NAL-ы потока целиком, каждый с 4-байтным старт-кодом. */
    fun nalUnits(stream: ByteArray): List<ByteArray> {
        val d = AnnexB.normalize(stream)
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) {
                starts.add(i)
                i += 4
            } else {
                i++
            }
        }
        val res = ArrayList<ByteArray>()
        for ((k, s) in starts.withIndex()) {
            val e = if (k + 1 < starts.size) starts[k + 1] else d.size
            res.add(d.copyOfRange(s, e))
        }
        return res
    }

    fun nalType(nal: ByteArray): Int = if (nal.size > 4) nal[4].toInt() and 0x1F else -1

    /** SPS и PPS (первые) из конфига/потока. */
    fun spsPps(stream: ByteArray): Pair<ByteArray?, ByteArray?> {
        val nals = nalUnits(stream)
        return Pair(nals.firstOrNull { nalType(it) == 7 }, nals.firstOrNull { nalType(it) == 8 })
    }

    /**
     * Делит поток на access unit-ы: новая картинка начинается на слайсе с first_mb_in_slice == 0
     * (старший бит первого байта после заголовка NAL = 1) либо на SPS/PPS/SEI/AUD после слайсов.
     * Возвращает (SPS+PPS первого кадра, список AU).
     */
    fun split(stream: ByteArray): Pair<ByteArray?, List<AccessUnit>> {
        val nals = nalUnits(stream)
        val aus = ArrayList<AccessUnit>()
        var cur = ArrayList<ByteArray>()
        var curVcl = false
        var curIdr = false
        fun flush() {
            if (cur.isNotEmpty() && curVcl) {
                val size = cur.sumOf { it.size }
                val out = ByteArray(size)
                var p = 0
                for (n in cur) {
                    System.arraycopy(n, 0, out, p, n.size)
                    p += n.size
                }
                aus.add(AccessUnit(out, curIdr))
            }
            cur = ArrayList()
            curVcl = false
            curIdr = false
        }
        for (n in nals) {
            val t = nalType(n)
            when (t) {
                1, 5 -> {
                    val firstSlice = n.size > 5 && (n[5].toInt() and 0x80) != 0
                    if (curVcl && firstSlice) flush()
                    cur.add(n)
                    curVcl = true
                    if (t == 5) curIdr = true
                }
                6, 7, 8, 9 -> {
                    if (curVcl) flush()
                    cur.add(n)
                }
                else -> cur.add(n)
            }
        }
        flush()
        val cfgNals = nals.filter { nalType(it) == 7 || nalType(it) == 8 }
        val sps = cfgNals.firstOrNull { nalType(it) == 7 }
        val pps = cfgNals.firstOrNull { nalType(it) == 8 }
        val cfg = if (sps != null && pps != null) sps + pps else null
        return Pair(cfg, aus)
    }
}
