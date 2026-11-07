// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import java.util.concurrent.atomic.AtomicBoolean

object DesktopAudioAccess {
    fun hasPermission(context: Context): Boolean = context.checkSelfPermission(DesktopAudioPolicy.PERMISSION) ==
        PackageManager.PERMISSION_GRANTED
}

object DesktopAudioState {
    @Volatile var status = "Звук по USB выключен"
    @Volatile var running = false
    @Volatile var error: String? = null
    @Volatile var pcmBytes = 0L
    @Volatile var nonSilentBuffers = 0L
    /** Пик последнего буфера захвата, 0..1: волны плашки «Говорю» по голосу Алисы. */
    @Volatile var level = 0f
}

/** Main-process owner. PCM exists only in memory; the existing CarLife queue enforces backpressure. */
class DesktopAudioCapture(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var core: AaBridgeCore? = null
    private var usb = false
    private var canCaptureOutput: (() -> Boolean)? = null
    private var closed = false
    private var failed = false
    private var worker: Capture? = null
    private val sessions = DesktopAudioSessionGate()
    private var generation = 0L
    private class Capture(val core: AaBridgeCore, val generation: Long) {
        val cancelled = AtomicBoolean()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            reconcile()
            main.postDelayed(this, 250)
        }
    }

    /** Called only for a live transport, never for the desktop's disconnected grace period. */
    fun attach(value: AaBridgeCore, isUsb: Boolean, canCapture: () -> Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        detach()
        generation = sessions.next()
        core = value
        usb = isUsb
        canCaptureOutput = canCapture
        failed = false
        main.removeCallbacks(tick)
        tick.run()
    }

    fun detach() {
        check(Looper.myLooper() == Looper.getMainLooper())
        main.removeCallbacks(tick)
        worker?.cancelled?.set(true)
        sessions.next() // old callbacks must not PAUSE/STOP a newly attached CarLife link
        core?.usbAudioEnabled = false
        core = null
        canCaptureOutput = null
        usb = false
        DesktopAudioState.running = false
        DesktopAudioState.status = if (DexPrefs(context).usbPlaybackAudio) "Звук: ожидаю подключение CarLife по USB" else "Звук по USB выключен"
    }

    fun close() { detach(); closed = true }

    private fun reconcile() {
        val current = core ?: return
        val prefs = DexPrefs(context)
        val enabled = prefs.usbPlaybackAudio
        val delegated = true // PC-activated delegation is the only access backend here
        val ready = DelegatedDesktopAccess.isReady(context)
        val permission = DesktopAudioAccess.hasPermission(context)
        // Enable the CarLife sink only after an explicit setting + scoped activation.
        current.usbAudioEnabled = enabled && delegated && ready && permission && usb
        val outputFresh = runCatching { canCaptureOutput?.invoke() == true }.getOrDefault(false)
        val permitted = outputFresh && DesktopAudioPolicy.mayCapture(enabled, delegated, ready, permission,
            usb, current.isCarlifeStarted && current.hasCarlifeLink, current.audioViaUsb)
        if (!permitted) {
            if (!outputFresh && worker?.cancelled?.compareAndSet(false, true) == true)
                AppLog.i("DESKTOP AUDIO: USB write lease expired; releasing playback capture", false)
            worker?.cancelled?.set(true)
            failed = false // a user setting/access/session transition allows a fresh attempt
            DesktopAudioState.running = false
            DesktopAudioState.status = when {
                !enabled -> "Звук по USB выключен"
                !delegated -> "Звук по USB доступен в режиме «Доступ с компьютера»"
                !ready -> "Для звука активируйте доступ HeadSwap"
                !permission -> "Для звука повторно активируйте доступ с параметром --audio"
                !usb -> "Звук: ожидаю подключение CarLife по USB"
                !current.isCarlifeStarted -> "Звук: ожидаю запуск CarLife"
                !current.audioViaUsb -> "Магнитола запросила звук по Bluetooth"
                !outputFresh -> "Звук приостановлен: магнитола не принимает USB"
                else -> "Звук: соединение закрыто"
            }
        } else if (worker == null && !failed) {
            val capture = Capture(current, generation)
            worker = capture
            DesktopAudioState.error = null
            DesktopAudioState.pcmBytes = 0
            DesktopAudioState.nonSilentBuffers = 0
            DesktopAudioState.status = "Подключаю звук по USB…"
            Thread({ runCapture(capture) }, "headswap-playback-audio").start()
        }
    }

    // This is a privileged OUTPUT source, not RECORD_AUDIO/microphone. The explicit
    // instrumentation option delegates only CAPTURE_AUDIO_OUTPUT in addition to the desktop grants.
    @SuppressLint("MissingPermission", "WrongConstant") // Privileged public REMOTE_SUBMIX is omitted by the SDK IntDef.
    private fun openRecord(): AudioRecord {
        val minimum = AudioRecord.getMinBufferSize(DesktopAudioPolicy.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Android не поддерживает PCM 48 кГц, стерео" }
        val builder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(DesktopAudioPolicy.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, DesktopAudioPolicy.READ_BYTES * 4))
        if (Build.VERSION.SDK_INT >= 31) builder.setContext(context)
        return builder.build().also {
            if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); error("Android не создал источник звука") }
        }
    }

    /** Пик PCM 16 бит little-endian (каждый 4-й сэмпл - хватает для волн), 0..1. */
    private fun peak(pcm: ByteArray): Float {
        var max = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val a = if (s < 0) -s else s
            if (a > max) max = a
            i += 8
        }
        return max / 32768f
    }

    private fun runCapture(capture: Capture) {
        var record: AudioRecord? = null
        var loopback: DesktopAudioLoopback? = null
        var started = false
        var failure: Throwable? = null
        try {
            if (context.checkSelfPermission(DesktopAudioPolicy.ROUTING_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                try {
                    loopback = DesktopAudioLoopback.open(context, DesktopAudioPolicy.SAMPLE_RATE)
                    record = loopback.record
                } catch (error: Throwable) {
                    AppLog.i("DESKTOP AUDIO: AudioMix loopback недоступен (${error.javaClass.simpleName}: ${error.message}), беру REMOTE_SUBMIX", false)
                }
            } else AppLog.i("DESKTOP AUDIO: нет MODIFY_AUDIO_ROUTING (активация без нового --audio), беру REMOTE_SUBMIX", false)
            // AudioService propagates new delegated grants to its native permission cache asynchronously.
            for (attempt in 0..2) {
                if (record != null) break
                if (capture.cancelled.get()) return
                try { record = openRecord(); break }
                catch (error: Exception) { if (attempt == 2) throw error; Thread.sleep(150) }
            }
            val audio = checkNotNull(record)
            if (capture.cancelled.get()) return
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Android не запустил передачу звука" }
            started = true
            sessions.runIfCurrent(capture.generation) {
                if (!capture.cancelled.get()) capture.core.onAudioStart(DesktopAudioPolicy.MEDIA_CHANNEL)
            }
            main.post { if (worker === capture && !capture.cancelled.get()) {
                DesktopAudioState.running = true
                DesktopAudioState.status = "Звук передаётся по USB"
            } }
            AppLog.i("DESKTOP AUDIO: ${if (loopback != null) "AudioMix loopback (media/game/unknown)" else "REMOTE_SUBMIX"} PCM 48000 Hz stereo16 started (memory only)")
            val input = ByteArray(DesktopAudioPolicy.READ_BYTES)
            val frames = DesktopPcmFrames()
            var statsAt = SystemClock.elapsedRealtime()
            while (!capture.cancelled.get()) {
                val now = SystemClock.elapsedRealtime()
                if (now - statsAt >= STATS_MS) {
                    statsAt = now
                    AppLog.i("DESKTOP AUDIO: PCM bytes=${DesktopAudioState.pcmBytes}, non-silent buffers=${DesktopAudioState.nonSilentBuffers}", false)
                }
                val count = audio.read(input, 0, input.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "Ошибка чтения звука Android: $count" }
                if (count == 0) { Thread.sleep(5); continue }
                val pcm = frames.append(input, count)
                if (pcm.isEmpty() || capture.cancelled.get()) continue
                DesktopAudioState.pcmBytes += pcm.size
                if (pcm.any { it != 0.toByte() }) DesktopAudioState.nonSilentBuffers++
                DesktopAudioState.level = peak(pcm)
                sessions.runIfCurrent(capture.generation) {
                    if (!capture.cancelled.get()) capture.core.onAudioData(DesktopAudioPolicy.MEDIA_CHANNEL, pcm,
                        SystemClock.elapsedRealtimeNanos() / 1000)
                }
            }
        } catch (error: Exception) {
            if (!capture.cancelled.get()) {
                failure = error
                AppLog.e("DESKTOP AUDIO: playback unavailable; desktop remains active", error)
            }
        } finally {
            // Stop rerouting output before notifying CarLife; never retain capture during USB grace.
            if (started) runCatching { record?.stop() }
            if (loopback != null) loopback.release() else runCatching { record?.release() }
            if (started) runCatching { sessions.runIfCurrent(capture.generation) {
                capture.core.onAudioStop(DesktopAudioPolicy.MEDIA_CHANNEL)
            } }
            val error = failure
            main.post {
                if (worker === capture) {
                    worker = null
                    DesktopAudioState.running = false
                    if (error != null && core === capture.core && !capture.cancelled.get()) {
                        failed = true
                        DesktopAudioState.error = "${error.javaClass.simpleName}: ${error.message}"
                        DesktopAudioState.status = "Звук по USB недоступен; рабочий стол продолжает работать"
                    }
                }
            }
            AppLog.i("DESKTOP AUDIO: stopped; PCM bytes=${DesktopAudioState.pcmBytes}, non-silent buffers=${DesktopAudioState.nonSilentBuffers}", false)
        }
    }

    private companion object { const val STATS_MS = 5_000L }
}
