package com.yegorkapomidorka.headswap.aa

import android.content.Context
import android.hardware.usb.UsbManager
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.CarLifeState
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Итог одной пробы сервера ГУ (127.0.0.1:5277). */
enum class ProbeResult {
    /** Полное рукопожатие прошло, сессия закрыта штатно. */
    ALIVE,

    /** ECONNREFUSED: порт никто не слушает. */
    REFUSED,

    /** TCP принят, но VERSION_RESPONSE или TLS не пришли (или порт не принимает подключения). */
    HUNG,

    /** Прочее: нет сертификатов, сервер сразу закрыл соединение, TLS отвергнут, сбой сокета. */
    ERROR,
}

/** Одна попытка пробы. Настоящая - AaSessionProbe, в тестах подменяется. */
fun interface ServerProbe {
    fun probeOnce(): ProbeResult
}

/**
 * Проба живости сервера ГУ: настоящая AA-сессия (AapSession, как в мосте и AaProbe), а не голый connect.
 * Голое connect/close без рукопожатия вешает сервер 5277, поэтому после TCP попытка идёт до конца:
 * VERSION, TLS, AuthComplete, ответ на SERVICE_DISCOVERY_REQUEST и сразу штатное закрытие (ByeBye, EOF, close).
 * Видео и каналы не открываем: после SDR телефон шлёт запросы каналов, но сессия уже останавливается.
 * Возвращается только когда сессия закрылась полностью (onEnd): иначе мост подключился бы поверх неё.
 * Автоматически не запускается: даже короткая проба сбивает Android Auto, следующее подключение машины получало отказ или зависание.
 */
class AaSessionProbe(
    private val loadCreds: () -> AapCredentials,
    private val sd: () -> AapSdOptions,
    private val log: (String, Boolean) -> Unit,
    private val host: String = "127.0.0.1",
    private val port: Int = 5277,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val sdrWaitMs: Long = SDR_WAIT_MS,
) : ServerProbe {
    private object NoSink : AaVideoSink {
        override fun onCodecConfig(annexB: ByteArray) {}
        override fun onFrame(annexB: ByteArray, ptsUs: Long) {}
    }

    /** Чем кончилась последняя попытка (ошибка подключения или причина конца сессии): для тоста при ERROR. */
    @Volatile var lastDetail: String = ""
        private set

    override fun probeOnce(): ProbeResult {
        lastDetail = ""
        val creds = try {
            loadCreds()
        } catch (e: Throwable) {
            lastDetail = "${e.javaClass.simpleName}: ${e.message}"
            log("SRVCHECK сертификаты не загрузились: $lastDetail", true)
            return ProbeResult.ERROR
        }
        val ended = CountDownLatch(1)
        val endReason = AtomicReference("-")
        val connectError = AtomicReference<IOException?>(null)
        val ref = AtomicReference<AapSession?>(null)
        val session = AapSession(
            creds, NoSink, AapOptions(host = host, port = port, sdProvider = sd),
            log = { line, _ ->
                // "AA " -> "aa ": LogDiag/logdiag.py ищут маркеры моста ("AA TCP подключено", "AA сервер ... недоступен") подстрокой по всей строке
                log("SRVCHECK | " + (if (line.startsWith("AA ")) "aa " + line.substring(3) else line), false)
                // телефон спросил возможности ГУ: это последний шаг, дальше штатное закрытие. Ответ на SDR
                // лог-хук опережает (dispatch логирует до onControl), но в очереди он встанет раньше ByeBye
                if ("RX CONTROL(0) SERVICE_DISCOVERY_REQUEST" in line) ref.get()?.requestStop("проба: SDR получен")
            },
            socketFactory = {
                Socket().also { s ->
                    try {
                        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
                    } catch (e: IOException) {
                        connectError.set(e)
                        try {
                            s.close()
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
        ref.set(session)
        session.start()
        // Пока TCP принят, прерывать нельзя: stop до TLS закрыл бы сокет без ByeBye. Сессия сама ограничена
        // таймаутами (version 10 с, TLS 15 с, bye и EOF ещё несколько секунд), поэтому ждём её конца.
        var establishedAtMs = 0L
        var interrupted = false
        var polls = 0
        while (true) {
            try {
                if (ended.await(50, TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                interrupted = true      // нить не бросаем: сессия ещё держит сокет
            }
            if (session.established) {
                val nowMs = System.nanoTime() / 1_000_000
                if (establishedAtMs == 0L) establishedAtMs = nowMs
                else if (nowMs - establishedAtMs > sdrWaitMs) session.requestStop("проба: SDR не пришёл за ${sdrWaitMs / 1000} с")
            }
            if (++polls > MAX_POLLS) {
                log("SRVCHECK сессия не закрылась за ${MAX_POLLS * 50 / 1000} с, выхожу не дожидаясь", true)
                break
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        val result = classify(connectError.get(), session.connected, session.established, endReason.get())
        lastDetail = connectError.get()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: endReason.get()
        log(
            "SRVCHECK сессия: connected=${session.connected} established=${session.established} закрытие чистое=${session.exitOk} " +
                "rx=${session.stats.rxFrames} tx=${session.stats.txFrames}, причина: ${endReason.get()}",
            true,
        )
        return result
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 3000
        const val SDR_WAIT_MS = 3000L
        private const val MAX_POLLS = 120 * 20      // 120 с при опросе раз в 50 мс

        /** Итог по тому, докуда дошла сессия. Таймауты рукопожатия = HUNG, всё остальное необычное = ERROR. */
        fun classify(connectError: Throwable?, connected: Boolean, established: Boolean, endReason: String): ProbeResult = when {
            !connected -> when {
                connectError is ConnectException && connectError.message?.contains("refused", ignoreCase = true) == true -> ProbeResult.REFUSED
                connectError is SocketTimeoutException -> ProbeResult.HUNG       // backlog полон: порт не принимает
                else -> ProbeResult.ERROR
            }
            established -> ProbeResult.ALIVE
            endReason.startsWith(AapSession.NO_VERSION_RESPONSE_REASON) || endReason.startsWith("TLS handshake: таймаут") -> ProbeResult.HUNG
            else -> ProbeResult.ERROR
        }
    }
}

/**
 * Ручная проба из «Для разработчиков»: одна попытка в фоновой нити, без серий и повторов.
 * Не стартует при живом мосте и подключённом USB (одноразовый сервер 5277 тогда нужен им) и не параллельно с другой.
 * Попытку, дошедшую до TCP, прервать нельзя, поэтому мост перед подключением ждёт её конца (awaitIdle).
 * Результат на состояние главного экрана не влияет.
 */
class ManualServerCheck(
    private val probe: ServerProbe,
    private val busy: () -> Refusal?,
    private val log: (String) -> Unit,
) {
    /** Почему пробу не запустили. */
    enum class Refusal { BRIDGE, USB, RUNNING }

    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private var running = false

    /** null = проба запущена, onDone позовётся из рабочей нити (detail - текст сбоя, если проба упала исключением); иначе причина отказа. */
    fun start(onDone: (ProbeResult, String?) -> Unit): Refusal? {
        lock.withLock {
            if (running) return Refusal.RUNNING
            busy()?.let { return it }
            running = true
        }
        val t = Thread({
            var result = ProbeResult.ERROR
            var detail: String? = null
            try {
                log("SRVCHECK ручная проба: старт")
                result = probe.probeOnce()
            } catch (e: Throwable) {
                detail = "${e.javaClass.simpleName}: ${e.message}"
                log("SRVCHECK сбой: $detail")
            } finally {
                lock.withLock {
                    running = false
                    idle.signalAll()
                }
            }
            log("SRVCHECK ИТОГ: $result")
            onDone(result, detail)
        }, "aa-srvcheck")
        t.isDaemon = true
        t.start()
        return null
    }

    /** Дождаться конца идущей пробы; false = не закончилась за timeoutMs. */
    fun awaitIdle(timeoutMs: Long): Boolean = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var interrupted = false
        while (running && left > 0) {
            try {
                left = idle.awaitNanos(left)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        !running
    }
}

/**
 * Пассивная диагностика сервера ГУ по настоящим подключениям моста (AaConnector), сама к серверу не ходит:
 * как долго он подряд отказывает (ECONNREFUSED) и сколько раз подряд принял TCP, но не прислал VERSION_RESPONSE.
 * Короткие отказы (~6 с после ByeBye, пока AA снова слушает порт) норма: решает главный экран (HomeLogic) по порогам.
 * Серия принадлежит одному циклу коннектора: reset() в начале и в конце, старые отказы в новую поездку не попадают.
 */
class ServerFailures(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    @Volatile private var refusedSince = NONE

    /** Когда закончилась последняя установленная сессия (наш ByeBye или обрыв); NONE - в этом цикле такой не было. */
    @Volatile private var lastEndAt = NONE

    /** Подряд принятых по TCP подключений без VERSION_RESPONSE. */
    @Volatile var versionHangs = 0
        private set

    /** Хоть раз за процесс установилась настоящая AA-сессия: сервер работал. Не сбрасывается. */
    @Volatile var worked = false
        private set

    /** Сколько миллисекунд сервер отказывает без перерыва (0 = не отказывает). */
    val refusedForMs: Long get() = refusedSince.let { if (it == NONE) 0L else now() - it }

    fun onRefused() {
        if (refusedSince == NONE) refusedSince = now()
        versionHangs = 0
    }

    /** TCP принят: порт уже не отказывает (дальше решит VERSION_RESPONSE). */
    fun onTcpAccepted() {
        refusedSince = NONE
    }

    fun onNoVersionResponse() {
        refusedSince = NONE
        versionHangs++
    }

    /** Сессия установилась: сервер работает, серии обнуляются. */
    fun onEstablished() {
        reset()
        worked = true
    }

    /** Установленная сессия закончилась (наш ByeBye или обрыв): ближайшие [RELISTEN_MS] отказ - норма, AA снова поднимает порт. */
    fun onSessionEnded() {
        lastEndAt = now()
    }

    /**
     * Сервер отказывает, и это не окно [RELISTEN_MS] после закрытия нашей же сессии: на свежем старте моста отказ
     * значит, что сервер не запущен. Запас на перезапуск порта нужен только сразу после своего ByeBye.
     */
    val refusedFresh: Boolean
        get() = refusedSince != NONE && (lastEndAt == NONE || refusedSince - lastEndAt >= RELISTEN_MS)

    /**
     * Пора просить пользователя включить сервер (текст заглушки на ГУ): зависания подряд, отказ у сервера, который
     * в этом процессе ещё не работал, отказ вне окна после закрытия своей сессии ([refusedFresh]) или отказ дольше
     * [START_HINT_REFUSED_MS] у работавшего (как HomeLogic.REFUSED_DOWN_MS).
     */
    fun needsStart(): Boolean =
        versionHangs >= START_HINT_HANGS ||
            (refusedSince != NONE && (!worked || refusedFresh || refusedForMs >= START_HINT_REFUSED_MS))

    /** Любой другой исход (голый EOF, TLS и т.п.), начало и конец цикла коннектора. */
    fun reset() {
        refusedSince = NONE
        versionHangs = 0
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val START_HINT_REFUSED_MS = 15_000L
        const val RELISTEN_MS = 10_000L
        const val START_HINT_HANGS = 2
    }
}

/** Ручная проба для кнопки в «Для разработчиков» (Actions.checkServer) и ожидание её конца перед подключением моста. */
object AaServerCheck {
    @Volatile private var manual: ManualServerCheck? = null
    @Volatile private var probe: AaSessionProbe? = null

    /** Сколько мост ждёт конец ручной пробы перед подключением. Попытка сама укладывается в ~35 с. */
    private const val AWAIT_MS = 45_000L

    private fun checker(ctx: Context): ManualServerCheck {
        manual?.let { return it }
        synchronized(this) {
            manual?.let { return it }
            val app = ctx.applicationContext
            val usb = app.getSystemService(UsbManager::class.java)
            val p = AaSessionProbe(
                loadCreds = { AaCreds.load(app, AaPrefs(app).certSet).creds },
                sd = { VideoMode.DEFAULT.sdOptions(fullAudio = false, carMake = AaPrefs(app).carMake) },
                log = { line, ui -> AppLog.i(line, ui) },
            )
            probe = p
            val c = ManualServerCheck(
                probe = p,
                busy = {
                    when {
                        CarLifeState.running -> ManualServerCheck.Refusal.BRIDGE
                        usb.accessoryList?.isNotEmpty() == true -> ManualServerCheck.Refusal.USB
                        else -> null
                    }
                },
                log = { AppLog.i(it) },
            )
            manual = c
            return c
        }
    }

    /** Кнопка в «Для разработчиков». null = проба пошла, onDone позовётся из рабочей нити (detail - причина при ERROR). */
    fun start(ctx: Context, onDone: (ProbeResult, String?) -> Unit): ManualServerCheck.Refusal? =
        checker(ctx).start { result, err -> onDone(result, err ?: if (result == ProbeResult.ERROR) probe?.lastDetail else null) }

    /** AaConnector перед каждым подключением к серверу ГУ (нить коннектора, не main): ручная проба не должна держать сервер. */
    fun beforeBridgeConnect() {
        val c = manual ?: return
        if (!c.awaitIdle(AWAIT_MS)) AppLog.i("SRVCHECK проба не закончилась за $AWAIT_MS мс, подключаюсь всё равно")
    }
}
