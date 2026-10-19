package com.yegorkapomidorka.headswap.aa

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import com.yegorkapomidorka.headswap.AnnexB
import com.yegorkapomidorka.headswap.AppLog
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * AA H.264 -> MediaCodec-декодер (вывод на Surface) -> Surface входа энкодера AVC Baseline -> CarLife.
 * Нулевое копирование через Surface. Энкодер живёт от start() до stop() (вся CarLife-сессия) и сам
 * повторяет последний кадр раз в 100 мс на статичной картинке (KEY_REPEAT_PREVIOUS_FRAME_AFTER, как
 * MD Baidu: core/screen/video/Recorder.java:1364), поэтому ГУ не остаётся без кадров. Декодер создаётся
 * лениво на первом IDR AA и пересоздаётся при новом SPS или resetDecoder().
 */
class AaTranscoder(
    private val width: Int,
    private val height: Int,
    /** Начальная целевая частота: KEY_FRAME_RATE энкодера и гейт GL-реле; дальше меняется через [setTargetFps]. */
    private val fps: Int,
    private val bitrate: Int,
    private val out: TranscodeOutput,
    private val log: (String) -> Unit = { AppLog.i(it, false) },
    /** Эксперименты: "vbr", "repeat=NNN" (мкс), "norealtime", "nolowlat", "nobase". */
    private val tuning: String = "",
    /** Размер кадра AA на входе (подсказка декодеру) и вырезаемая из него часть; по умолчанию вход = выход, без кропа. */
    private val srcWidth: Int = width,
    private val srcHeight: Int = height,
    private val crop: VideoCrop = VideoCrop.NONE,
) : VideoTranscoder {
    private var encoder: MediaCodec? = null
    private var surface: Surface? = null
    private var relay: GlRelay? = null
    private var lastRedraws = 0L
    private var lastGated = 0L
    @Volatile private var target = fps
    @Volatile private var curBitrate = bitrate
    private var bitrateMode = -1                       // режим, с которым сконфигурирован энкодер (-1 = не задан/неизвестен)
    @Volatile private var bitrateFailed = false
    private var encThread: Thread? = null
    @Volatile private var running = false
    private var encoderNote = ""
    var encoderName = "?"
        private set

    private val lock = Any()
    private var decoder: Dec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var decoderCfg: ByteArray? = null          // SPS+PPS, с которыми создан текущий декодер
    private var cfgStale = false
    private var needIdr = true

    // статистика окна
    private val inTimes = ConcurrentHashMap<Long, Long>()
    private val winIn = AtomicLong()
    private val winDecoded = AtomicLong()
    private val winOut = AtomicLong()
    private val winDrop = AtomicLong()
    private val winDecLatUs = AtomicLong()
    private val winDecLatN = AtomicLong()               // кадров с известным временем подачи (знаменатель winDecLatUs)
    private val winEncLatUs = AtomicLong()
    private val winEncLatN = AtomicLong()
    private var winStartMs = SystemClock.elapsedRealtime()
    val totalIn = AtomicLong()
    val totalDecoded = AtomicLong()
    val totalOut = AtomicLong()

    @Synchronized
    fun start() {
        if (running) return
        val c = MediaCodec.createEncoderByType(MIME)
        encoderName = c.name
        var configured = false
        var lastErr: Exception? = null
        for (level in 0..2) {
            try {
                if (level > 0) c.reset()
                c.configure(format(level), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoderNote = when (level) {
                    0 -> "baseline+CBR+noB+repeat100ms+realtime"
                    1 -> "baseline+CBR+noB+repeat100ms"
                    else -> "минимальный формат+repeat100ms"
                }
                configured = true
                break
            } catch (e: Exception) {
                lastErr = e
                log("транскодер: configure уровня $level не прошёл: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (!configured) {
            try {
                c.release()
            } catch (_: Exception) {
            }
            throw lastErr ?: IllegalStateException("encoder configure")
        }
        var inSurface: Surface? = null
        try {
            inSurface = c.createInputSurface()
            c.start()
        } catch (e: Exception) {
            // кодек и Surface ещё не в полях: stop() их не увидит, аппаратный энкодер освобождаем здесь
            try {
                inSurface?.release()
            } catch (_: Exception) {
            }
            try {
                c.release()
            } catch (_: Exception) {
            }
            throw e
        }
        surface = inSurface
        encoder = c
        // режим битрейта: CBR/VBR принимают PARAMETER_KEY_VIDEO_BITRATE на лету, CQ - нет; на уровне 2 режим не задавался
        bitrateMode = try {
            c.inputFormat.getInteger(MediaFormat.KEY_BITRATE_MODE)
        } catch (_: Exception) {
            -1
        }
        if (!tuning.contains("nogl")) {
            // декодер -> GL-реле -> Surface энкодера: реле перерисовывает последний кадр на статике (>= 10 fps)
            try {
                relay = GlRelay(surface!!, width, height, REDRAW_IDLE_MS, log, srcWidth, srcHeight, crop, target).also { it.start() }
            } catch (e: Exception) {
                log("транскодер: GL-реле не поднялось (${e.message}), декодер пишет прямо в энкодер")
                relay = null
            }
        }
        if (relay == null && !crop.isNone) log("транскодер: !!!!!! без GL-реле кроп невозможен, кадр AA будет сжат целиком")
        running = true
        encThread = Thread({ encodeLoop(c) }, "aa-trans-enc").also { it.start() }
        log("транскодер: энкодер $encoderName ${width}x$height (вход AA ${srcWidth}x$srcHeight${if (crop.isNone) "" else ", кроп $crop"}) ${fps}fps ${bitrate / 1000}kbps ($encoderNote)")
    }

    override val targetFps: Int get() = target

    /** Целевая частота на лету: только гейт GL-реле, кодек не пересоздаётся (KEY_FRAME_RATE остаётся начальным). */
    override fun setTargetFps(fps: Int) {
        val old = target
        target = fps
        val r = relay
        if (r != null) r.setTargetFps(fps) else log("транскодер: без GL-реле частоту не ограничить, целевой fps $fps только в статистике")
        log("транскодер: целевой fps $old -> $fps")
    }

    override val currentBitrate: Int get() = curBitrate

    /** Битрейт на лету через setParameters (без пересоздания). CQ или исключение кодека: один раз в лог, дальше не пробуем. */
    override fun setBitrate(bps: Int) {
        if (bitrateFailed || bps == curBitrate) return
        val c = encoder ?: return
        if (bitrateMode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) {
            bitrateFailed = true
            log("транскодер: энкодер в BITRATE_MODE_CQ, битрейт на лету не меняется")
            return
        }
        try {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
            curBitrate = bps
        } catch (e: Exception) {
            bitrateFailed = true
            log("транскодер: setParameters(VIDEO_BITRATE=$bps) не прошёл, битрейт больше не меняю: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun format(level: Int): MediaFormat {
        val f = MediaFormat.createVideoFormat(MIME, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        // как у Baidu (Recorder.java:1364, MediaFormatUtils.java:48): на статичном экране кадр повторяется раз в 100 мс
        val repeat = Regex("""repeat=(\d+)""").find(tuning)?.groupValues?.get(1)?.toLong() ?: REPEAT_AFTER_US
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeat)
        if (level <= 1 && !tuning.contains("nobase")) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            // VBR: на растворении логотипа AA в карты CBR c2.qti выдавал P-кадры до 129 КБ (~4x к исходнику AA, ~850 КБ за 0,3 с)
            // и ГУ рисовало полосы; VBR на том же потоке держит пики до ~40 КБ при той же картинке (transcode_test, 12.10.2026)
            f.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                if (tuning.contains("cbr")) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        if (level == 0) {
            if (!tuning.contains("norealtime")) f.setInteger(MediaFormat.KEY_PRIORITY, 0)   // realtime
            if (Build.VERSION.SDK_INT >= 30 && !tuning.contains("nolowlat")) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        return f
    }

    // ---------------- вход AA ----------------

    override fun feedConfig(annexB: ByteArray) {
        val (s, p) = H264Stream.spsPps(annexB)
        if (s == null || p == null) return
        synchronized(lock) {
            val changed = sps?.contentEquals(s) != true || pps?.contentEquals(p) != true
            sps = s
            pps = p
            if (changed && decoder != null) cfgStale = true     // декодер пересоздастся на следующем IDR
        }
    }

    override fun feedFrame(annexB: ByteArray, ptsUs: Long) {
        if (!running) return
        winIn.incrementAndGet()
        totalIn.incrementAndGet()
        val idr = 5 in AnnexB.nalTypes(annexB)
        synchronized(lock) {
            // мост подаёт кадры вне своей блокировки: stop() мог пройти между проверкой выше и lock, декодер не пересоздаём
            if (!running) return
            if (decoder == null || (cfgStale && idr)) {
                if (!idr) {
                    winDrop.incrementAndGet()
                    return                           // декодеру нужен IDR для старта
                }
                if (!createDecoder(annexB)) return
            }
            if (needIdr && !idr) {
                winDrop.incrementAndGet()
                return
            }
            val d = decoder ?: return
            val idx = try {
                d.codec.dequeueInputBuffer(15_000)
            } catch (e: Exception) {
                out.onTranscodeError("декодер: dequeueInputBuffer: ${e.message}")
                return
            }
            if (idx < 0) {
                // декодер не успевает: кадр потерян, дальше P нельзя - ждём IDR от AA
                winDrop.incrementAndGet()
                needIdr = true
                out.onNeedAaKeyFrame()
                return
            }
            val buf = d.codec.getInputBuffer(idx)
            if (buf == null || buf.capacity() < annexB.size) {
                try {
                    d.codec.queueInputBuffer(idx, 0, 0, ptsUs, 0)
                } catch (e: Exception) {
                    out.onTranscodeError("декодер: queueInputBuffer: ${e.message}")
                    return
                }
                // кадр потерян, как и без входного буфера: P после него декодер испортит - ждём IDR от AA
                winDrop.incrementAndGet()
                needIdr = true
                out.onNeedAaKeyFrame()
                return
            }
            buf.clear()
            buf.put(annexB)
            inTimes[ptsUs] = SystemClock.elapsedRealtimeNanos()
            // ConcurrentHashMap без порядка: выбрасываем самые старые pts (AA шлёт их по возрастанию), а не случайные,
            // иначе пропадали метки кадров, которые декодер ещё выдаст
            if (inTimes.size > 256) inTimes.keys.sorted().take(128).forEach { inTimes.remove(it) }
            try {
                d.codec.queueInputBuffer(idx, 0, annexB.size, ptsUs, 0)
                if (idr) needIdr = false
            } catch (e: Exception) {
                out.onTranscodeError("декодер: queueInputBuffer: ${e.message}")
            }
        }
    }

    /** Создаёт декодер по SPS/PPS (из CODEC_CONFIG или из самого IDR). Под lock. */
    private fun createDecoder(idrFrame: ByteArray): Boolean {
        releaseDecoderLocked()
        var s = sps
        var p = pps
        if (s == null || p == null) {
            val (s2, p2) = H264Stream.spsPps(idrFrame)
            s = s2
            p = p2
        }
        if (s == null || p == null) {
            log("транскодер: нет SPS/PPS для декодера, жду")
            return false
        }
        val surf = relay?.inputSurface ?: surface ?: return false
        return try {
            val f = MediaFormat.createVideoFormat(MIME, srcWidth, srcHeight)
            f.setByteBuffer("csd-0", ByteBuffer.wrap(s))
            f.setByteBuffer("csd-1", ByteBuffer.wrap(p))
            f.setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
            val c = MediaCodec.createDecoderByType(MIME)
            try {
                try {
                    c.configure(f, surf, null, 0)
                } catch (e: Exception) {
                    // без low-latency/priority (не все декодеры принимают)
                    c.reset()
                    val f2 = MediaFormat.createVideoFormat(MIME, srcWidth, srcHeight)
                    f2.setByteBuffer("csd-0", ByteBuffer.wrap(s))
                    f2.setByteBuffer("csd-1", ByteBuffer.wrap(p))
                    c.configure(f2, surf, null, 0)
                }
                c.start()
            } catch (e: Exception) {
                // декодер ещё не в поле decoder: releaseDecoderLocked() его не освободит
                try {
                    c.release()
                } catch (_: Exception) {
                }
                throw e
            }
            val d = Dec(c)
            d.thread = Thread({ decodeLoop(d) }, "aa-trans-dec").also { it.start() }
            decoder = d
            decoderCfg = s + p
            cfgStale = false
            needIdr = true
            log("транскодер: декодер ${c.name} создан (SPS ${s.size} Б, PPS ${p.size} Б)")
            true
        } catch (e: Exception) {
            out.onTranscodeError("декодер не создан: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private class Dec(val codec: MediaCodec) {
        @Volatile var alive = true
        var thread: Thread? = null
    }

    private fun decodeLoop(d: Dec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (d.alive && running) {
                val idx = d.codec.dequeueOutputBuffer(info, 20_000)
                if (idx >= 0) {
                    val render = info.size > 0
                    val inAt = inTimes.remove(info.presentationTimeUs)
                    d.codec.releaseOutputBuffer(idx, render)
                    if (render) {
                        winDecoded.incrementAndGet()
                        totalDecoded.incrementAndGet()
                        if (inAt != null) {
                            winDecLatUs.addAndGet((SystemClock.elapsedRealtimeNanos() - inAt) / 1000)
                            winDecLatN.incrementAndGet()
                        }
                    }
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    log("транскодер: формат вывода декодера ${d.codec.outputFormat}")
                }
            }
        } catch (e: Exception) {
            if (d.alive && running) out.onTranscodeError("цикл декодера: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun resetDecoder() {
        synchronized(lock) {
            releaseDecoderLocked()
            relay?.clear()
            sps = null
            pps = null
            needIdr = true
        }
    }

    private fun releaseDecoderLocked() {
        val d = decoder ?: return
        decoder = null
        d.alive = false
        try {
            d.thread?.join(500)
        } catch (_: InterruptedException) {
        }
        try {
            d.codec.stop()
        } catch (_: Exception) {
        }
        try {
            d.codec.release()
        } catch (_: Exception) {
        }
        inTimes.clear()
    }

    // ---------------- выход энкодера ----------------

    private fun encodeLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = c.dequeueOutputBuffer(info, 50_000)
                if (idx >= 0) {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        val arr = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(arr)
                        val data = AnnexB.normalize(arr)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            out.onEncoderConfig(data)
                        } else {
                            val lat = System.nanoTime() - info.presentationTimeUs * 1000
                            if (lat in 0..2_000_000_000L) {
                                winEncLatUs.addAndGet(lat / 1000)
                                winEncLatN.incrementAndGet()
                            }
                            winOut.incrementAndGet()
                            totalOut.incrementAndGet()
                            out.onEncoderFrame(data, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        }
                    }
                    c.releaseOutputBuffer(idx, false)
                }
            }
        } catch (e: Exception) {
            if (running) out.onTranscodeError("цикл энкодера: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun startIntro(banner: android.graphics.Bitmap) {
        relay?.startIntro(banner)
    }

    override fun requestSyncFrame() {
        try {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            log("транскодер: requestSyncFrame: ${e.message}")
        }
    }

    override fun statsLine(): String {
        val now = SystemClock.elapsedRealtime()
        val sec = maxOf((now - winStartMs) / 1000.0, 0.001)
        winStartMs = now
        val inN = winIn.getAndSet(0)
        val dec = winDecoded.getAndSet(0)
        val outN = winOut.getAndSet(0)
        val drop = winDrop.getAndSet(0)
        val decLat = winDecLatUs.getAndSet(0)
        val decLatN = winDecLatN.getAndSet(0)
        val encLat = winEncLatUs.getAndSet(0)
        val encN = winEncLatN.getAndSet(0)
        val decMs = if (decLatN > 0) decLat / decLatN / 1000.0 else 0.0
        val encMs = if (encN > 0) encLat / encN / 1000.0 else 0.0
        val r = relay
        val redrawsNow = r?.redraws?.get() ?: 0L
        val repeats = if (r != null) redrawsNow - lastRedraws else maxOf(outN - dec, 0)
        lastRedraws = redrawsNow
        val gatedNow = r?.gated?.get() ?: 0L
        val gatedWin = gatedNow - lastGated
        lastGated = gatedNow
        return "транскод за %.1f с: вход AA %d, декодировано %d, выход энкодера %d (%.1f fps), повторов (перерисовок GL) %d, задержано гейтом fps %d, потеряно %d; задержка декодер %.1f мс + энкодер %.1f мс = %.1f мс"
            .format(sec, inN, dec, outN, outN / sec, repeats, gatedWin, drop, decMs, encMs, decMs + encMs)
    }

    @Synchronized
    override fun stop() {
        if (!running && encoder == null) return
        running = false
        synchronized(lock) { releaseDecoderLocked() }
        try {
            relay?.stop()
        } catch (_: Exception) {
        }
        relay = null
        try {
            encThread?.join(1000)
        } catch (_: InterruptedException) {
        }
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        try {
            surface?.release()
        } catch (_: Exception) {
        }
        encoder = null
        surface = null
    }

    companion object {
        const val MIME = "video/avc"
        const val REPEAT_AFTER_US = 100_000L

        /** Реле перерисовывает последний кадр, если новых нет столько мс (как repeat-previous-frame-after у Baidu). */
        const val REDRAW_IDLE_MS = 100L
    }
}
