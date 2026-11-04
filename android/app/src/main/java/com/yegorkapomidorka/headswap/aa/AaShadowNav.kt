package com.yegorkapomidorka.headswap.aa

import android.content.Context
import com.yegorkapomidorka.headswap.AppLog
import java.io.File
import java.io.FileOutputStream

/**
 * ПРОБНИК (не для релиза): «теневая» AA-сессия рядом с рабочим столом 4.0 ради одного навигационного канала.
 * Без видео (фокус NATIVE, как у AaSessionProbe) хватает Google Картам. 2ГИС и Яндекс на Car App Library: их машинную
 * часть AA поднимает только на экране машины, поэтому режим video=true берёт проекцию 1280x720 (кадры в декодер окна
 * навигатора, если включена [projection], иначе мимо),
 * первые [DUMP_MS] пишет в files/shadow/shadow-N.h264 (посмотреть на ПК: ffplay). Медиа/речи нет (fullAudio=false), микрофона нет.
 * Всё, что AA шлёт по навигации, пишется в лог «AA META NAVIGATION ...» плюс разобранные onNav* строкой SHADOWNAV.
 * Команды (am start --display 0 -n <pkg>/.MainActivity --es cmd ...): shadow_nav, shadow_nav_video, shadow_nav_off,
 * shadow_dump (ещё [DUMP_MS] видео с ключевого кадра), shadow_tap --ei x X --ei y Y (координаты кадра 800x480).
 */
object AaShadowNav {
    @Volatile private var wanted = false
    @Volatile private var session: AapSession? = null
    @Volatile private var video = false
    private var thread: Thread? = null
    private lateinit var dumpDir: File

    private object Sink : AaVideoSink, AaMetaSink {
        @Volatile var codecConfig: ByteArray? = null
        @Volatile private var dump: FileOutputStream? = null
        @Volatile private var dumpUntilMs = 0L
        @Volatile private var dumpWaitKey = false
        private var frames = 0L

        override fun onCodecConfig(annexB: ByteArray) {
            codecConfig = annexB
            log("видео: codec config ${annexB.size} Б")
            decoder.config(annexB)
            write(annexB)
        }

        override fun onFrame(annexB: ByteArray, ptsUs: Long) {
            frames++
            if (frames == 1L || frames % 600 == 0L) log("видео: кадров $frames")
            decoder.frame(annexB, ptsUs)
            if (dump == null) return
            if (dumpWaitKey) { if (!isKeyFrame(annexB)) return; dumpWaitKey = false; codecConfig?.let { write(it) } }
            write(annexB)
            if (System.currentTimeMillis() > dumpUntilMs) closeDump()
        }

        @Synchronized fun openDump(waitKey: Boolean) {
            closeDump()
            dumpDir.mkdirs()
            val f = File(dumpDir, "shadow-${System.currentTimeMillis()}.h264")
            dump = FileOutputStream(f)
            dumpUntilMs = System.currentTimeMillis() + DUMP_MS
            dumpWaitKey = waitKey
            log("видео: пишу ${DUMP_MS / 1000} с в ${f.absolutePath}")
        }

        @Synchronized fun closeDump() {
            val d = dump ?: return
            dump = null
            runCatching { d.close() }
            log("видео: запись закрыта")
        }

        fun resetCounters() { frames = 0; codecConfig = null }

        @Synchronized private fun write(b: ByteArray) {
            val d = dump ?: return
            try { d.write(b) } catch (e: Throwable) { log("видео: запись упала ${e.message}"); closeDump() }
        }

        override fun onNavCluster(start: Boolean) { log("кластер ${if (start) "START" else "STOP"}"); forward { it.onNavCluster(start) } }
        override fun onNavStatus(status: Int) { log("статус $status (0 нет, 1 ведёт, 2 стоит, 3 перестроение)"); forward { it.onNavStatus(status) } }
        override fun onNavTurn(t: AaNavTurn) { log("манёвр $t legacy=${t.legacy}"); forward { it.onNavTurn(t) } }
        override fun onNavDistance(d: AaNavDistance) { log("дистанция $d legacy=${d.legacy}"); forward { it.onNavDistance(d) } }

        private inline fun forward(block: (AaMetaSink) -> Unit) {
            // навигатор на своём хосте Car App шлёт подсказки сам: статус теневой AA не должен гасить его маршрут
            if (com.yegorkapomidorka.headswap.carapp.CarAppHost.pkg != null) return
            val t = DesktopNavHints.target ?: return
            try { block(t) } catch (e: Throwable) { log("в мост не ушло: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    private fun log(line: String) = AppLog.i("SHADOWNAV $line", false)

    /** video=true: проекция 800x480 ради навигаторов на Car App Library (2ГИС, Яндекс). Другой режим = перезапуск. */
    @Synchronized fun start(ctx: Context, withVideo: Boolean = false) {
        if (wanted && video == withVideo) { log("уже запущен (video=$video)"); return }
        if (wanted) stop()
        wanted = true
        video = withVideo
        val gen = ++generation
        val app = ctx.applicationContext
        dumpDir = File(app.getExternalFilesDir(null), "shadow")
        log("старт video=$withVideo")
        thread = Thread({ loop(app, gen, withVideo) }, "aa-shadow-nav").apply { isDaemon = true; start() }
    }

    /** Не ждёт ByeBye: старая нить дорабатывает сама, новый start() поднимает свою (по [generation]). */
    @Synchronized fun stop() {
        if (!wanted) return
        wanted = false
        generation++
        session?.requestStop("теневая навигация выключена")
        Sink.closeDump()
        log("выключаю")
    }

    /** Ещё [DUMP_MS] видео начиная с ключевого кадра. */
    fun dump() {
        val s = session
        if (s == null || !video) { log("dump: нет сессии с видео"); return }
        Sink.openDump(waitKey = true)
        s.requestKeyFrame()
    }

    /** Кадр AA в режиме видео: 1280x720, его же размер у поверхности окна навигатора в режиме проекции. */
    const val PROJ_W = 1280
    const val PROJ_H = 720

    private val decoder = AaProjectionDecoder(PROJ_W, PROJ_H)

    /** Проекция: окно навигатора рабочего стола показывает экран AA вместо навигатора телефона (EmbedHost). */
    @Volatile var projection = false

    /** Поверхность окна навигатора для декодера; null - снять. */
    fun setSurface(s: android.view.Surface?) {
        decoder.setSurface(s)
        if (s != null) session?.requestKeyFrame()
    }

    /** Касание из окна навигатора, координаты уже в кадре AA. */
    fun touch(action: Int, index: Int, points: List<TouchPoint>) {
        session?.sendTouch(if (action == 3) AapMessages.TOUCH_UP else action, index, points)
    }

    /** Тап в координатах кадра AA 1280x720. */
    fun tap(x: Int, y: Int) {
        val s = session ?: run { log("tap: нет сессии"); return }
        log("tap $x,$y")
        Thread {
            s.sendTouch(AapMessages.TOUCH_DOWN, x, y)
            Thread.sleep(80)
            s.sendTouch(AapMessages.TOUCH_UP, x, y)
        }.start()
    }

    @Volatile private var generation = 0

    private fun loop(app: Context, gen: Int, withVideo: Boolean) {
        var attempt = 0
        while (generation == gen) {
            attempt++
            val creds = try { AaCreds.load(app, AaPrefs(app).certSet).creds } catch (e: Throwable) {
                log("сертификаты не загрузились: ${e.javaClass.simpleName}: ${e.message}"); stop(); return
            }
            val ended = java.util.concurrent.CountDownLatch(1)
            val mode = VideoMode.R720
            Sink.resetCounters()
            if (withVideo) Sink.openDump(waitKey = false)
            val s = AapSession(creds, Sink,
                AapOptions(preVideoTimeoutMs = Long.MAX_VALUE / 4, videoFocus = withVideo,
                    sdProvider = { mode.sdOptions(fullAudio = false, carMake = AaPrefs(app).carMake).copy(mic = false) }),
                log = { line, _ -> AppLog.i("SHADOWNAV | $line", false) },
                onEnd = { reason, _ -> log("сессия #$attempt закончилась: $reason"); ended.countDown() })
            synchronized(this) {
                if (generation != gen) return
                session = s
            }
            log("сессия #$attempt: подключаюсь к 127.0.0.1:5277 (video=$withVideo, ${mode.aaResolution})")
            s.start()
            ended.await()
            if (generation == gen) Sink.closeDump() // иначе это уже запись новой сессии
            synchronized(this) { if (session === s) session = null }
            var slept = 0L
            while (generation == gen && slept < RETRY_MS) { Thread.sleep(500); slept += 500 }
        }
        log("остановлен")
    }

    /** Сервер 5277 после ByeBye поднимается заново за ~6 с (ServerFailures.RELISTEN_MS = 10 с). */
    private const val RETRY_MS = 10_000L
    private const val DUMP_MS = 20_000L
}
