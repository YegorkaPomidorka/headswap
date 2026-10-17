package com.yegorkapomidorka.headswap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface

/**
 * H.264-энкодер (Surface input) и рисовалка заглушки моста (текст, пока AA не подключился).
 * Кадры отдаются в Annex-B с 4-байтными стартовыми кодами; codec config приходит отдельно (onConfig).
 */
class VideoSource(
    val width: Int,
    val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onConfig(csd: ByteArray)
        fun onFrame(data: ByteArray, key: Boolean)
        fun onError(msg: String, t: Throwable?)
    }

    @Volatile var framesRendered = 0L
        private set

    /** Текст заглушки: крупная надпись и живой индикатор вместо видео AA. */
    @Volatile var banner: String? = null

    @Volatile private var running = false
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var drainThread: Thread? = null
    private var renderThread: Thread? = null
    var encoderName: String = "?"
        private set

    private val fill = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    @Synchronized
    fun start() {
        if (running) return
        val c = MediaCodec.createEncoderByType(MIME)
        encoderName = c.name
        var s: Surface? = null
        try {
            var fmt = format(withExtras = true)
            try {
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                AppLog.e("configure с profile/CBR не прошёл, пробую минимальный формат", e)
                c.reset()
                fmt = format(withExtras = false)
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            s = c.createInputSurface()
            c.start()
        } catch (e: Exception) {
            // codec ещё null: stop() вышел бы сразу, аппаратный энкодер освобождаем здесь
            try {
                s?.release()
            } catch (_: Exception) {
            }
            try {
                c.release()
            } catch (_: Exception) {
            }
            throw e
        }
        surface = s
        codec = c
        running = true
        drainThread = Thread({ drainLoop(c) }, "video-enc-drain").also { it.start() }
        renderThread = Thread({ renderLoop() }, "video-render").also { it.start() }
    }

    private fun format(withExtras: Boolean): MediaFormat {
        val f = MediaFormat.createVideoFormat(MIME, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        if (withExtras) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        return f
    }

    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            AppLog.e("requestKeyFrame", e)
        }
    }

    /** Битрейт на лету (воздух: политика битрейта ведёт и заглушку). false = кодека нет или параметр не принят. */
    fun setBitrate(bps: Int): Boolean {
        val c = codec ?: return false
        return try {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
            true
        } catch (e: Exception) {
            AppLog.e("битрейт заглушки", e)
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!running && codec == null) return
        running = false
        renderThread?.join(1000)
        drainThread?.join(1000)
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        try {
            surface?.release()
        } catch (_: Exception) {
        }
        codec = null
        surface = null
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = c.dequeueOutputBuffer(info, 50_000)
                when {
                    idx >= 0 -> {
                        val buf = c.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            val arr = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(arr)
                            val data = AnnexB.normalize(arr)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                listener.onConfig(data)
                            } else {
                                listener.onFrame(data, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                            }
                        }
                        c.releaseOutputBuffer(idx, false)
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        AppLog.i("энкодер: формат вывода $encoderName: ${c.outputFormat}", ui = false)
                }
            }
        } catch (e: Exception) {
            if (running) listener.onError("drain энкодера", e)
        }
    }

    private fun renderLoop() {
        val s = surface ?: return
        val frameMs = 1000L / fps
        var next = SystemClock.elapsedRealtime()
        try {
            while (running) {
                // программный Canvas: lockHardwareCanvas на Surface энкодера валит media.codec на Xiaomi с vndk v30
                // (Redmi 9T/9S, POCO X3 Pro: SIGSEGV в FenceTime::Snapshot::getFlattenedSize, за ним abort нашего процесса)
                val canvas = s.lockCanvas(null)
                try {
                    draw(canvas)
                } finally {
                    s.unlockCanvasAndPost(canvas)
                }
                framesRendered++
                next += frameMs
                val d = next - SystemClock.elapsedRealtime()
                if (d > 0) Thread.sleep(d) else next = SystemClock.elapsedRealtime()
            }
        } catch (e: Exception) {
            if (running) listener.onError("рендер на Surface энкодера", e)
        }
    }

    /** Текущий кадр заглушки в Bitmap: с него транскодер начинает зум-растворение в картинку AA. Любая нить. */
    fun snapshot(): Bitmap? {
        val msg = banner ?: return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawBanner(Canvas(bmp), width, height, msg, framesRendered, Paint(text), Paint(fill))
        return bmp
    }

    private fun drawBanner(c: Canvas, msg: String) = drawBanner(c, width, height, msg, framesRendered, text, fill)

    private fun drawBanner(c: Canvas, width: Int, height: Int, msg: String, n: Long, text: Paint, fill: Paint) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(Color.rgb(16, 24, 56))
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER
        text.textSize = h * 0.09f
        // перенос по словам в 90% ширины
        val lines = ArrayList<String>()
        var cur = ""
        for (word in msg.split(' ')) {
            val t = if (cur.isEmpty()) word else "$cur $word"
            if (text.measureText(t) > w * 0.9f && cur.isNotEmpty()) {
                lines.add(cur)
                cur = word
            } else {
                cur = t
            }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        val lh = text.textSize * 1.25f
        var y = h * 0.5f - lh * (lines.size - 1) / 2f
        for (l in lines) {
            c.drawText(l, w / 2, y, text)
            y += lh
        }
        // живой индикатор: видно, что картинка не замерла
        fill.color = Color.rgb(255, 160, 0)
        val x = w * 0.1f + (w * 0.8f) * ((n % 90) / 90f)
        c.drawRect(x - 12f, h * 0.82f, x + 12f, h * 0.82f + 10f, fill)
        text.textSize = h * 0.035f
        text.color = Color.LTGRAY
        c.drawText("Headswap: мост AA", w / 2, h * 0.92f, text)
    }

    private fun draw(c: Canvas) {
        drawBanner(c, banner ?: "")
    }

    companion object {
        const val MIME = "video/avc"
    }
}
