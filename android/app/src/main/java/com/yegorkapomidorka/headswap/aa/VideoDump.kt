package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.AppLog
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug dump of the encoder output for the CR-V 2026 flicker investigation.
 *
 * Enabled only when the app's external files directory contains a marker file [MARKER].
 * Writes raw Annex-B H.264 plus a CSV with the encoder PTS, wall clock, size and key flag.
 * Writing runs on its own thread with a bounded queue: the encoder thread never blocks on disk.
 */
class VideoDump private constructor(
    private val tag: String,
    private val h264: File,
    private val csv: File,
) {
    private sealed class Item {
        class Config(val data: ByteArray) : Item()
        class Frame(val data: ByteArray, val ptsUs: Long, val key: Boolean, val wallMs: Long) : Item()
        class Event(val text: String, val wallMs: Long) : Item()
        object Stop : Item()
    }

    private val queue = ArrayBlockingQueue<Item>(QUEUE_FRAMES)
    private val dropped = AtomicLong()
    private val thread = Thread({ writeLoop() }, "video-dump-$tag")
    private var out: BufferedOutputStream? = null
    private var csvOut: BufferedOutputStream? = null
    private var index = 0
    private var bytes = 0L
    private var lastPtsUs = 0L
    private var ptsCount = 0
    private var ptsMin = Long.MAX_VALUE
    private var ptsMax = Long.MIN_VALUE
    private var ptsSum = 0L
    private var lastFlushMs = 0L
    @Volatile private var closed = false

    private fun start() {
        h264.parentFile?.mkdirs()
        out = BufferedOutputStream(FileOutputStream(h264), 1 shl 20)
        csvOut = BufferedOutputStream(FileOutputStream(csv), 1 shl 16)
        csvOut?.write("type,index,wall_ms,pts_us,size,key,note\n".toByteArray(Charsets.UTF_8))
        thread.start()
        AppLog.i("video_dump[$tag]: пишу ${h264.absolutePath} и ${csv.absolutePath}", false)
    }

    fun config(data: ByteArray) {
        offer(Item.Config(data))
    }

    fun frame(data: ByteArray, ptsUs: Long, key: Boolean) {
        offer(Item.Frame(data, ptsUs, key, System.currentTimeMillis()))
    }

    fun event(text: String) {
        offer(Item.Event(text, System.currentTimeMillis()))
    }

    private fun offer(item: Item) {
        if (closed) return
        if (!queue.offer(item)) dropped.incrementAndGet()
    }

    fun close() {
        if (closed) return
        closed = true
        queue.offer(Item.Stop)
        try {
            thread.join(3000)
        } catch (_: InterruptedException) {
        }
    }

    private fun writeLoop() {
        try {
            while (true) {
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (item is Item.Stop) break
                when (item) {
                    is Item.Config -> {
                        out?.write(item.data)
                        csvOut?.write("config,$index,${System.currentTimeMillis()},0,${item.data.size},1,\n".toByteArray())
                    }
                    is Item.Frame -> {
                        out?.write(item.data)
                        bytes += item.data.size
                        index++
                        csvOut?.write(
                            "frame,$index,${item.wallMs},${item.ptsUs},${item.data.size},${if (item.key) 1 else 0},\n"
                                .toByteArray()
                        )
                        if (item.ptsUs > 0) {
                            if (lastPtsUs > 0) {
                                val d = item.ptsUs - lastPtsUs
                                if (d in 1..1_000_000L) {
                                    ptsCount++
                                    ptsSum += d
                                    if (d < ptsMin) ptsMin = d
                                    if (d > ptsMax) ptsMax = d
                                }
                            }
                            lastPtsUs = item.ptsUs
                        }
                        if (bytes > MAX_BYTES) {
                            AppLog.i("video_dump[$tag]: достигнут предел $MAX_BYTES Б, дальше не пишу", false)
                            break
                        }
                    }
                    is Item.Event -> csvOut?.write(
                        "event,$index,${item.wallMs},0,0,0,${item.text.replace(',', ';')}\n".toByteArray()
                    )
                    Item.Stop -> {}
                }
                val now = System.currentTimeMillis()
                if (now - lastFlushMs >= 1000) {
                    out?.flush()
                    csvOut?.flush()
                    lastFlushMs = now
                }
            }
        } catch (e: Exception) {
            AppLog.e("video_dump[$tag]: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                out?.flush()
                out?.close()
            } catch (_: Exception) {
            }
            try {
                csvOut?.flush()
                csvOut?.close()
            } catch (_: Exception) {
            }
            val ptsAvg = if (ptsCount > 0) ptsSum / ptsCount else 0
            AppLog.i(
                "video_dump[$tag]: закрыт, кадров=$index, байт=$bytes, " +
                    "PTS Δ мкс min=${if (ptsCount > 0) ptsMin else 0} avg=$ptsAvg " +
                    "max=${if (ptsCount > 0) ptsMax else 0}, отброшено в очередь=${dropped.get()}",
                false,
            )
        }
    }

    companion object {
        const val MARKER = "video-dump.on"
        private const val QUEUE_FRAMES = 240

        /** Safety cap: ~14 minutes at the CR-V desktop bitrate. */
        private const val MAX_BYTES = 256L * 1024 * 1024

        /** Opens a dump if the marker file exists. Returns null otherwise. */
        fun open(tag: String): VideoDump? {
            val dir = AppLog.file?.parentFile ?: return null
            if (!File(dir, MARKER).exists()) return null
            return try {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val base = "video-$tag-$stamp"
                VideoDump(tag, File(dir, "$base.h264"), File(dir, "$base.csv")).also { it.start() }
            } catch (e: Exception) {
                AppLog.e("video_dump[$tag]: не открылся", e)
                null
            }
        }
    }
}
