package com.yegorkapomidorka.headswap.aa

import android.content.Context
import android.hardware.usb.UsbManager
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.CarLifeState
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Отладочный стенд `--es cmd aa_hold`: долгая AA-сессия к 127.0.0.1:5277 без CarLife (фейковая ГУ, как ServerProbe Димы)
 * и замер, сколько сервер приходит в себя после её закрытия. Этап 0 плана docs/plans/aa-server-lease.md.
 *
 * Параметры intent: `--ei hold_s N` (держать, по умолчанию 180), `--es close bye|fin|rst` (ByeBye и штатное закрытие /
 * close() сокета без ByeBye / RST через SO_LINGER 0), `--ez video true` (дать видеофокус; по умолчанию NATIVE, видео не идёт),
 * `--ez until_usb true` (держать до USB-аксессуара или моста, потом закрыть и отдать сервер мосту, восстановление не мерить).
 * `--es cmd aa_hold_stop` закрывает раньше. После закрытия (кроме until_usb) пробует полное рукопожатие раз в секунду
 * до ALIVE (AaSessionProbe: голый connect вешает сервер), не дольше RECOVER_MAX_MS.
 */
object AaHold {
    private const val TAG = "AAHOLD"
    private const val CONNECT_TIMEOUT_MS = 3000
    private const val CLOSE_WAIT_MS = 20_000L
    private const val RECOVER_MAX_MS = 180_000L
    private const val RECOVER_STEP_MS = 1000L
    private const val TICK_MS = 200L
    private const val STATS_EVERY_MS = 30_000L

    @Volatile private var running = false
    @Volatile private var stopWhy: String? = null
    private val idleLock = Object()

    fun run(ctx: Context, holdS: Int, close: String, video: Boolean, untilUsb: Boolean) {
        val app = ctx.applicationContext
        synchronized(idleLock) {
            if (running) {
                AppLog.i("$TAG уже идёт, пропускаю")
                return
            }
            running = true
            stopWhy = null
        }
        Thread({
            try {
                hold(app, holdS * 1000L, close, video, untilUsb)
            } catch (e: Throwable) {
                AppLog.i("$TAG ИТОГ: сбой ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                synchronized(idleLock) {
                    running = false
                    idleLock.notifyAll()
                }
            }
        }, "aa-hold").start()
    }

    /** Закрыть долгую сессию сейчас (команда aa_hold_stop). */
    fun stop(why: String) {
        if (running) stopWhy = why
    }

    /** Мост перед подключением: отпустить сервер и дождаться конца стенда (вместе с восстановлением). */
    fun releaseForBridge(timeoutMs: Long): Boolean {
        stop("мост подключается")
        synchronized(idleLock) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (running) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) return false
                try {
                    idleLock.wait(left)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return true
    }

    private object NoSink : AaVideoSink {
        override fun onCodecConfig(annexB: ByteArray) {}
        override fun onFrame(annexB: ByteArray, ptsUs: Long) {}
    }

    private fun nowMs() = System.nanoTime() / 1_000_000

    private fun hold(ctx: Context, holdMs: Long, close: String, video: Boolean, untilUsb: Boolean) {
        val usb = ctx.getSystemService(UsbManager::class.java)
        val prefs = AaPrefs(ctx)
        val sd = { VideoMode.DEFAULT.sdOptions(fullAudio = false, carMake = prefs.carMake) }
        AppLog.i("$TAG старт: держать ${if (untilUsb) "до USB/моста" else "${holdMs / 1000} с"}, закрытие=$close, видео=$video")
        val creds = try {
            AaCreds.load(ctx, prefs.certSet).creds
        } catch (e: Throwable) {
            AppLog.i("$TAG ИТОГ: сертификаты не загрузились ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        val sockRef = AtomicReference<Socket?>(null)
        val ended = CountDownLatch(1)
        val endReason = AtomicReference("-")
        val session = AapSession(
            creds, NoSink,
            AapOptions(preVideoTimeoutMs = Long.MAX_VALUE / 4, sdProvider = sd, videoFocus = video),
            log = { line, _ ->
                AppLog.i("$TAG | " + (if (line.startsWith("AA ")) "aa " + line.substring(3) else line), false)
            },
            socketFactory = {
                Socket().also { s ->
                    try {
                        s.connect(InetSocketAddress("127.0.0.1", 5277), CONNECT_TIMEOUT_MS)
                    } catch (e: IOException) {
                        try {
                            s.close()
                        } catch (_: IOException) {
                        }
                        throw e
                    }
                    sockRef.set(s)
                }
            },
            onEnd = { reason, _ ->
                endReason.set(reason)
                ended.countDown()
            },
        )
        val startMs = nowMs()
        session.start()

        var establishedMs = 0L
        var nextStatsMs = startMs + STATS_EVERY_MS
        var closedBy: String? = null
        while (true) {
            if (ended.await(TICK_MS, TimeUnit.MILLISECONDS)) break
            val now = nowMs()
            if (session.established && establishedMs == 0L) {
                establishedMs = now
                AppLog.i("$TAG сессия установлена за ${now - startMs} мс")
            }
            if (now >= nextStatsMs) {
                nextStatsMs += STATS_EVERY_MS
                val s = session.stats
                AppLog.i("$TAG держу ${(now - startMs) / 1000} с: rx=${s.rxFrames} tx=${s.txFrames} видео=${s.videoData} аудио=${s.audioData}")
            }
            closedBy = when {
                stopWhy != null -> stopWhy
                untilUsb && (CarLifeState.running || usb.accessoryList?.isNotEmpty() == true) -> "машина: USB/мост"
                !untilUsb && now - startMs >= holdMs -> "прошло ${holdMs / 1000} с"
                else -> null
            }
            if (closedBy != null) break
        }

        val heldS = (nowMs() - startMs) / 1000.0
        val serverClosed = closedBy == null
        if (serverClosed) {
            AppLog.i("$TAG сервер сам закрыл сессию через $heldS с: ${endReason.get()}")
        } else {
            AppLog.i("$TAG закрываю ($closedBy) через $heldS с способом $close")
            val closeStart = nowMs()
            when (close) {
                "fin", "rst" -> sockRef.get()?.let { s ->
                    try {
                        if (close == "rst") s.setSoLinger(true, 0)
                        s.close()
                    } catch (_: IOException) {
                    }
                }
                else -> session.requestStop("aa_hold: $closedBy")
            }
            if (!ended.await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)) AppLog.i("$TAG сессия не закрылась за ${CLOSE_WAIT_MS / 1000} с")
            AppLog.i("$TAG закрыто за ${nowMs() - closeStart} мс, чисто=${session.exitOk}, причина: ${endReason.get()}")
        }
        val s = session.stats
        val held = "держали $heldS с, established=${session.established}, сервер закрыл сам=$serverClosed, " +
            "rx=${s.rxFrames} tx=${s.txFrames} видео=${s.videoData} аудио=${s.audioData}"
        if (untilUsb || !session.established) {
            AppLog.i("$TAG ИТОГ: $held, закрытие=$close")
            return
        }
        AppLog.i("$TAG ИТОГ: $held, закрытие=$close; восстановление: ${recover(ctx, sd)}")
    }

    /**
     * `--es cmd aa_gap`: первая сессия (`--es first probe|hold`: короткая проба SDR+ByeBye или `--ei hold_s` с видео и ByeBye),
     * пауза `--ei gap_s`, потом сессия как у моста: видео, повтор раз в секунду до REJOIN_MAX_MS, держим hold_s, ByeBye.
     * Отвечает на вопрос, можно ли пробовать сервер при открытии экрана и не сломать следующую поездку.
     */
    fun runGap(ctx: Context, first: String, gapS: Int, holdS: Int) {
        val app = ctx.applicationContext
        synchronized(idleLock) {
            if (running) {
                AppLog.i("$TAG уже идёт, пропускаю")
                return
            }
            running = true
            stopWhy = null
        }
        Thread({
            try {
                gap(app, first, gapS * 1000L, holdS * 1000L)
            } catch (e: Throwable) {
                AppLog.i("$TAG GAP ИТОГ: сбой ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                synchronized(idleLock) {
                    running = false
                    idleLock.notifyAll()
                }
            }
        }, "aa-gap").start()
    }

    private const val REJOIN_MAX_MS = 180_000L

    private class Outcome(val established: Boolean, val establishMs: Long, val firstVideoMs: Long, val videoFrames: Long, val reason: String)

    /** Одна сессия: до установки, потом держим holdMs (видео по [video]) и закрываем ByeBye. */
    private fun session(ctx: Context, sd: () -> AapSdOptions, holdMs: Long, video: Boolean, tag: String): Outcome {
        val prefs = AaPrefs(ctx)
        val creds = AaCreds.load(ctx, prefs.certSet).creds
        val ended = CountDownLatch(1)
        val endReason = AtomicReference("-")
        val s = AapSession(
            creds, NoSink,
            AapOptions(preVideoTimeoutMs = 30_000, sdProvider = sd, videoFocus = video),
            log = { line, _ -> AppLog.i("$TAG $tag | " + (if (line.startsWith("AA ")) "aa " + line.substring(3) else line), false) },
            socketFactory = {
                Socket().also { sock ->
                    try {
                        sock.connect(InetSocketAddress("127.0.0.1", 5277), CONNECT_TIMEOUT_MS)
                    } catch (e: IOException) {
                        try {
                            sock.close()
                        } catch (_: IOException) {
                        }
                        throw e
                    }
                }
            },
            onEnd = { reason, _ ->
                endReason.set(reason)
                ended.countDown()
            },
        )
        val t0 = nowMs()
        s.start()
        var estMs = -1L
        var videoMs = -1L
        while (!ended.await(TICK_MS / 4, TimeUnit.MILLISECONDS)) {
            val now = nowMs()
            if (s.established && estMs < 0) estMs = now - t0
            if (s.stats.videoData > 0 && videoMs < 0) videoMs = now - t0
            if (estMs >= 0 && now - t0 - estMs >= holdMs) {
                s.requestStop("aa_gap: $tag отработала")
                ended.await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
                break
            }
        }
        return Outcome(estMs >= 0, estMs, videoMs, s.stats.videoData, endReason.get())
    }

    private fun gap(ctx: Context, first: String, gapMs: Long, holdMs: Long) {
        val prefs = AaPrefs(ctx)
        val sd = { VideoMode.DEFAULT.sdOptions(fullAudio = false, carMake = prefs.carMake) }
        AppLog.i("$TAG GAP старт: первая=$first, пауза=${gapMs / 1000} с, держать=${holdMs / 1000} с")
        val firstRes = if (first == "probe") {
            val probe = AaSessionProbe(
                loadCreds = { AaCreds.load(ctx, prefs.certSet).creds },
                sd = sd,
                log = { line, _ -> AppLog.i("$TAG probe | $line", false) },
            )
            val a0 = nowMs()
            val r = probe.probeOnce()
            "проба $r за ${nowMs() - a0} мс ${probe.lastDetail}"
        } else {
            val o = session(ctx, sd, holdMs, true, "first")
            "сессия established=${o.established} за ${o.establishMs} мс, видео через ${o.firstVideoMs} мс, кадров ${o.videoFrames}, конец: ${o.reason}"
        }
        AppLog.i("$TAG GAP первая: $firstRes")
        val endFirst = nowMs()
        Thread.sleep(gapMs)
        var attempt = 0
        var fails = ""
        while (nowMs() - endFirst - gapMs < REJOIN_MAX_MS) {
            if (stopWhy != null) {
                AppLog.i("$TAG GAP ИТОГ: прервано ($stopWhy); первая: $firstRes")
                return
            }
            attempt++
            val a0 = nowMs()
            val o = session(ctx, sd, holdMs, true, "second#$attempt")
            val since = (a0 - endFirst) / 1000.0
            if (o.established && o.firstVideoMs >= 0) {
                AppLog.i(
                    "$TAG GAP ИТОГ: первая: $firstRes; пауза ${gapMs / 1000} с; вторая OK с попытки $attempt через $since с после первой, " +
                        "установка ${o.establishMs} мс, видео ${o.firstVideoMs} мс, кадров ${o.videoFrames}, конец: ${o.reason}; провалы:$fails",
                )
                return
            }
            AppLog.i("$TAG GAP вторая #$attempt через $since с: established=${o.established} видео=${o.firstVideoMs} (${nowMs() - a0} мс): ${o.reason}")
            fails += " #$attempt@${since}с ${o.reason.take(60)};"
            val wait = RECOVER_STEP_MS - (nowMs() - a0)
            if (wait > 0) Thread.sleep(wait)
        }
        AppLog.i("$TAG GAP ИТОГ: первая: $firstRes; пауза ${gapMs / 1000} с; вторая НЕ ПОДНЯЛАСЬ за ${REJOIN_MAX_MS / 1000} с, попыток $attempt;$fails")
    }

    /** Полное рукопожатие раз в секунду до ALIVE. Отказ (REFUSED) серверу не вредит, TCP без VERSION (HUNG) - уже беда. */
    private fun recover(ctx: Context, sd: () -> AapSdOptions): String {
        val prefs = AaPrefs(ctx)
        val probe = AaSessionProbe(
            loadCreds = { AaCreds.load(ctx, prefs.certSet).creds },
            sd = sd,
            log = { line, _ -> AppLog.i("$TAG rec | $line", false) },
        )
        val t0 = nowMs()
        var refused = 0
        var hung = 0
        var errors = 0
        var attempt = 0
        while (nowMs() - t0 < RECOVER_MAX_MS) {
            if (stopWhy == "мост подключается") return "прервано мостом через ${(nowMs() - t0) / 1000.0} с"
            val a0 = nowMs()
            attempt++
            val r = probe.probeOnce()
            val at = (nowMs() - t0) / 1000.0
            AppLog.i("$TAG восстановление #$attempt через $at с: $r (${nowMs() - a0} мс) ${if (r == ProbeResult.ERROR) probe.lastDetail else ""}")
            when (r) {
                ProbeResult.ALIVE -> return "ALIVE через $at с (начало попытки ${(a0 - t0) / 1000.0} с), попыток $attempt: refused=$refused hung=$hung error=$errors"
                ProbeResult.REFUSED -> refused++
                ProbeResult.HUNG -> hung++
                ProbeResult.ERROR -> errors++
            }
            val wait = RECOVER_STEP_MS - (nowMs() - a0)
            if (wait > 0) Thread.sleep(wait)
        }
        return "НЕ ПОДНЯЛСЯ за ${RECOVER_MAX_MS / 1000} с, попыток $attempt: refused=$refused hung=$hung error=$errors"
    }
}
