package com.yegorkapomidorka.headswap

import android.content.Context
import android.content.Intent
import com.yegorkapomidorka.headswap.link.MultiSocketTransport
import com.yegorkapomidorka.headswap.link.TransportCaps
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * ADB-режим CarLife 3.0 (старые Android-ГУ, 2017): ГУ делает `adb forward tcp:7200 tcp:7240` (и так для остальных
 * портов) и подключается клиентом к портам телефона, по одному сокету на канал. Как в настоящем CarLife 8.6.7
 * (core/connect/ConnectManager.java, AcceptThread): шесть ServerSocket на всех интерфейсах (`new ServerSocket(port)`)
 * живут с запуска процесса, «подключено» =
 * шесть принятых сокетов, повторный accept на уже занятый порт сбрасывает ВСЕ сокеты. Первым пишет ГУ, MD при accept молчит.
 * Собранный набор отдаётся BridgeService (ACTION_ADB) через take().
 */
object AdbLink {
    /** Канал -> порт телефона (CommonParams.SERVER_*_SOCKET_PORT). */
    private val PORTS = linkedMapOf(
        Ch.CMD to 7240, Ch.VIDEO to 8240, Ch.MEDIA to 9240, Ch.TTS to 9241, Ch.VR to 9242, Ch.TOUCH to 9340,
    )
    private const val ACCEPT_RETRY_MS = 500L

    /** Все изменения слотов (pending, ready, active, статистика) только под этим замком. */
    private val lock = Any()
    private var appCtx: Context? = null
    private var started = false
    private val listening = HashSet<Int>()
    private val bindErrors = HashMap<Int, String>()
    /** Сокеты набора, который собирается: канал -> сокет. */
    private val pending = LinkedHashMap<Int, Socket>()
    /** Полный набор, ещё не забранный сервисом. */
    @Volatile private var ready: MultiSocketTransport? = null
    private var connections = 0

    /** Набор текущей сессии: его закрываем при повторном accept (сессия сама получит EOF и закроется). */
    @Volatile var active: MultiSocketTransport? = null
        private set

    /** Идемпотентно: порты поднимаются один раз на процесс. bind и accept на своих нитях, не на главной. */
    fun start(ctx: Context) {
        synchronized(lock) {
            if (started) return
            started = true
            appCtx = ctx.applicationContext
        }
        if (!CarLifeState.running) CarLifeState.status = "ADB: жду ГУ на :${PORTS.getValue(Ch.CMD)}…"
        AppLog.i("ADB: старт, порты ${PORTS.entries.joinToString(" ") { "${Ch.name(it.key)}:${it.value}" }}")
        for ((chan, port) in PORTS) {
            Thread({ acceptLoop(chan, port) }, "adb-accept-${Ch.name(chan)}").also { it.isDaemon = true }.start()
        }
    }

    /** Забрать собранный набор (атомарно) и сделать его активным: с этого момента повторный accept закроет и его. */
    fun take(): MultiSocketTransport? = synchronized(lock) {
        val io = ready
        ready = null
        if (io != null) active = io
        io
    }

    /** Строка для device.txt: порты, сколько сокетов подключено, сколько было полных подключений. */
    fun describe(): String = synchronized(lock) {
        val ports = PORTS.entries.joinToString(", ") { (chan, port) ->
            val state = when {
                chan in listening -> "слушается"
                else -> bindErrors[chan]?.let { "ОШИБКА bind: $it" } ?: "не поднят"
            }
            "${Ch.name(chan)}:$port $state"
        }
        val a = active
        "порты (все интерфейсы): $ports; сокетов в наборе: ${pending.size}/${PORTS.size}" +
            (if (pending.isNotEmpty()) " (${names(pending.keys)})" else "") +
            "; готов к сессии: ${if (ready != null) "да" else "нет"}; сессия: ${if (a != null && !a.isClosed) "идёт" else "нет"}" +
            "; полных подключений за процесс: $connections"
    }

    private fun names(chans: Collection<Int>) = chans.joinToString(" ") { Ch.name(it) }

    private fun acceptLoop(chan: Int, port: Int) {
        val name = Ch.name(chan)
        val server = try {
            ServerSocket().also {
                try {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress(port))        // все интерфейсы, как `new ServerSocket(port)` в оригинале
                } catch (e: Exception) {
                    try {
                        it.close()
                    } catch (_: Exception) {
                    }
                    throw e
                }
            }
        } catch (e: Exception) {
            AppLog.e("ADB: bind $name :$port", e)
            synchronized(lock) { bindErrors[chan] = "${e.javaClass.simpleName}: ${e.message}" }
            return
        }
        synchronized(lock) { listening.add(chan) }
        AppLog.i("ADB: слушаю $name :$port (все интерфейсы)")
        while (true) {
            val sock = try {
                server.accept()
            } catch (e: Exception) {
                if (server.isClosed) return
                AppLog.e("ADB: accept $name :$port", e)
                try {
                    Thread.sleep(ACCEPT_RETRY_MS)       // не крутить горячий цикл на повторяющейся ошибке
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            try {
                onAccept(chan, port, sock)
            } catch (e: Exception) {
                AppLog.e("ADB: обработка accept $name", e)
                closeQuietly(sock)
            }
        }
    }

    private fun onAccept(chan: Int, port: Int, sock: Socket) {
        val name = Ch.name(chan)
        try {
            sock.tcpNoDelay = true
            sock.keepAlive = true
        } catch (e: Exception) {
            AppLog.e("ADB: опции сокета $name", e)
        }
        AppLog.i("ADB: accept $name :$port от ${sock.remoteSocketAddress}")
        var complete: MultiSocketTransport? = null
        synchronized(lock) {
            // слот занят: сокет этого канала уже в наборе, либо набор собран (ready) или в работе (active) - в нём есть все каналы
            val a = active
            val busy = pending.containsKey(chan) || ready != null || (a != null && !a.isClosed)
            if (busy) {
                AppLog.i("ADB: повторное подключение $name, сбрасываю все сокеты")
                resetAll()
            }
            pending[chan] = sock
            AppLog.i("ADB: собрано ${pending.size}/${PORTS.size}: ${names(pending.keys)}")
            if (pending.size < PORTS.size) {
                if (!CarLifeState.running) CarLifeState.status = "ADB: подключено ${pending.size}/${PORTS.size} сокетов"
                return@synchronized
            }
            val io = try {
                MultiSocketTransport(HashMap(pending), PORTS, "ADB (TCP)", {}, caps = TransportCaps.TCP_ADB)
            } catch (e: Exception) {
                AppLog.e("ADB: потоки сокетов", e)
                resetAll()
                return@synchronized
            }
            pending.clear()
            ready = io
            connections++
            complete = io
        }
        complete?.let { startService(it) }
    }

    /** Закрыть всё: собираемый набор, не забранный сервисом набор и активный (под lock). */
    private fun resetAll() {
        for (s in pending.values) closeQuietly(s)
        pending.clear()
        ready?.close()
        ready = null
        active?.severSockets()      // сессия получит ошибку чтения и закроется сама
        active = null
    }

    private fun startService(io: MultiSocketTransport) {
        val ctx = synchronized(lock) { appCtx } ?: return
        AppLog.i("ADB: все ${PORTS.size} сокетов собраны, запускаю сервис")
        try {
            ctx.startForegroundService(Intent(ctx, BridgeService::class.java).setAction(BridgeService.ACTION_ADB))
        } catch (e: Exception) {      // в том числе ForegroundServiceStartNotAllowedException: процесс в фоне
            AppLog.e("ADB: сервис не запустился", e)
            CarLifeState.status = "ADB: сервис не запустился: ${e.javaClass.simpleName}"
            synchronized(lock) { if (ready === io) ready = null }
            io.close()
        }
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (_: Exception) {
        }
    }
}
