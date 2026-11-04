package com.yegorkapomidorka.headswap.aa

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.yegorkapomidorka.headswap.AppLog

/**
 * ПРОБНИК: аппаратный декодер видео теневой AA-сессии прямо в Surface окна навигатора рабочего стола.
 * Синхронный режим на нити AA-сессии: кадр в декодер, готовые кадры сразу на экран. Пока поверхности нет - кадры мимо.
 * Новая поверхность или новый codec config = декодер заново с ключевого кадра.
 */
internal class AaProjectionDecoder(private val w: Int, private val h: Int) {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var csd: ByteArray? = null
    private var needKey = true
    private var frames = 0L
    private val info = MediaCodec.BufferInfo()

    @Synchronized fun setSurface(s: Surface?) {
        if (s === surface) return
        stopCodec()
        surface = s
        needKey = true
        log(if (s == null) "поверхность снята" else "поверхность есть, жду ключевой кадр")
    }

    @Synchronized fun config(annexB: ByteArray) {
        if (csd?.contentEquals(annexB) == true) return
        csd = annexB
        stopCodec()
        needKey = true
    }

    @Synchronized fun frame(annexB: ByteArray, ptsUs: Long) {
        val s = surface ?: return
        if (needKey) {
            if (!isKeyFrame(annexB)) return
            if (!startCodec(s)) return
            needKey = false
        }
        val c = codec ?: return
        try {
            val i = c.dequeueInputBuffer(10_000)
            if (i < 0) { drain(c); return }
            val buf = c.getInputBuffer(i) ?: return
            buf.clear()
            buf.put(annexB)
            c.queueInputBuffer(i, 0, annexB.size, ptsUs, 0)
            drain(c)
            if (++frames == 1L || frames % 900 == 0L) log("кадров на экран $frames")
        } catch (e: Throwable) {
            log("декодер упал: ${e.javaClass.simpleName}: ${e.message}")
            stopCodec()
            needKey = true
        }
    }

    private fun drain(c: MediaCodec) {
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            if (o >= 0) c.releaseOutputBuffer(o, true)
            else if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return
        }
    }

    private fun startCodec(s: Surface): Boolean {
        val conf = csd ?: return false
        return try {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(f, s, null, 0)
            c.start()
            val i = c.dequeueInputBuffer(100_000)
            if (i >= 0) {
                c.getInputBuffer(i)!!.apply { clear(); put(conf) }
                c.queueInputBuffer(i, 0, conf.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            }
            codec = c
            frames = 0
            log("декодер ${c.name} ${w}x$h запущен")
            true
        } catch (e: Throwable) {
            log("декодер не запустился: ${e.javaClass.simpleName}: ${e.message}")
            stopCodec()
            false
        }
    }

    private fun stopCodec() {
        val c = codec ?: return
        codec = null
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    private fun log(line: String) = AppLog.i("SHADOWNAV проекция: $line", false)
}

/** IDR (NAL 5) или SPS (7) в любом из NAL кадра Annex B. */
internal fun isKeyFrame(b: ByteArray): Boolean {
    var i = 0
    while (i + 3 < b.size) {
        if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 1.toByte()) {
            val t = b[i + 3].toInt() and 0x1F
            if (t == 5 || t == 7) return true
            i += 3
        } else i++
    }
    return false
}
