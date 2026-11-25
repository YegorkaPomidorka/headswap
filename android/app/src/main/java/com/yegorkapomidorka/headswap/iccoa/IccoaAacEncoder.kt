// Derived from FT (github.com/OneAboveAll1964/ft-app), Apache-2.0: IccoaAacEncoder.kt.
// Изменено для Headswap: интерфейс для подмены в тестах, время захвата параметром; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** PCM 48 кГц стерео 16 бит → кадры AAC для TS; epoch — поколение очереди ([IccoaStreamQueue.audioEpoch]). */
interface IccoaAudioEncoder {
    fun pcm(data: ByteArray, epoch: Long)
    fun close()
}

/** AAC-LC на MediaCodec. [onFrame]: сырой кадр AAC, PTS в мкс, поколение очереди. */
class IccoaAacEncoder(
    private val onFrame: (ByteArray, Long, Long) -> Unit,
    private val onFailure: (String) -> Unit,
    private val nowUs: () -> Long = { System.nanoTime() / 1000 },
) : IccoaAudioEncoder {
    private class Pcm(val data: ByteArray, val atUs: Long, val epoch: Long)
    private val pending = ArrayBlockingQueue<Pcm>(8)
    private val closed = AtomicBoolean(false)
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val worker = Thread(::encode, "ic-aac")

    init {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            worker.start()
        } catch (e: Exception) {
            runCatching { codec.release() }
            throw e
        }
    }

    override fun pcm(data: ByteArray, epoch: Long) {
        if (closed.get() || data.isEmpty() || data.size > 192000 || data.size % 4 != 0) return
        val now = nowUs()
        var offset = 0
        while (offset < data.size && !closed.get()) {
            val length = minOf(8192, data.size - offset)
            val chunk = Pcm(data.copyOfRange(offset, offset + length),
                now - (data.size - offset) / 4 * 1_000_000L / 48000, epoch)
            // Хвост пачки важнее: при переполнении выбрасываем самый старый кусок.
            while (!closed.get() && !pending.offer(chunk)) pending.poll()
            offset += length
        }
    }

    private fun encode() {
        val info = MediaCodec.BufferInfo()
        val clock = IccoaPcmClock()
        val epochs = java.util.TreeMap<Long, Long>()
        var chunk: Pcm? = null
        var offset = 0
        try {
            while (!closed.get()) {
                if (chunk == null) { chunk = pending.poll(10, TimeUnit.MILLISECONDS); offset = 0 }
                val current = chunk
                if (current != null) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val input = requireNotNull(codec.getInputBuffer(index))
                        input.clear()
                        val count = minOf(input.remaining() and -4, current.data.size - offset)
                        check(count > 0) { "AAC input buffer too small" }
                        input.put(current.data, offset, count)
                        val pts = clock.take(count, current.atUs + offset / 4 * 1_000_000L / 48000)
                        epochs[pts] = current.epoch
                        check(epochs.size <= 128) { "AAC output stalled" }
                        codec.queueInputBuffer(index, 0, count, pts, 0)
                        offset += count
                        if (offset == current.data.size) chunk = null
                    }
                }
                while (!closed.get()) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    if (index < 0) break
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val output = requireNotNull(codec.getOutputBuffer(index))
                            output.position(info.offset); output.limit(info.offset + info.size)
                            val source = epochs.floorEntry(info.presentationTimeUs) ?: epochs.firstEntry()
                            if (source != null) {
                                onFrame(ByteArray(info.size).also { output.get(it) }, info.presentationTimeUs, source.value)
                                epochs.headMap(source.key, false).clear()
                            }
                        }
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (!closed.get()) onFailure(e.javaClass.simpleName)
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.clear()
        worker.interrupt()
    }
}
