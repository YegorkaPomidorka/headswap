package com.yegorkapomidorka.headswap.link

import com.yegorkapomidorka.headswap.link.diag.WLog
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Серверная сторона «свистка»: Android-стик в USB ГУ принимает AOA и гонит сырой поток аксессуара
 * ([chan u32 BE][len u32 BE] + inner) по одному TCP-сокету. Стик - клиент, подключается к шлюзу Wi-Fi телефона (точка доступа).
 * Одновременно живёт один сокет: пока предыдущий не закрыт, новые отбиваются (закрываются с записью в лог).
 * ServerSocket живёт до [close], чтобы после обрыва стик мог подключиться снова.
 */
class DongleListener(
    private val port: Int = PORT,
    private val bind: InetAddress = InetAddress.getByName("0.0.0.0"),
    /** Принятый сокет уже настроен. Зовётся с нити accept; закрывать сокет при отказе должен получатель. */
    private val onSocket: (Socket) -> Unit,
) {
    private val lock = Object()
    private var server: ServerSocket? = null
    private var current: Socket? = null
    private var closed = false

    /** Открыть порт и запустить нить accept. Порт занят: IOException, всё закрыто. */
    fun open(): DongleListener {
        val s = ServerSocket()
        try {
            s.reuseAddress = true
            s.receiveBufferSize = BUF      // до bind: принятые сокеты наследуют окно приёма
            s.bind(InetSocketAddress(bind, port), 4)
        } catch (e: IOException) {
            s.close()
            WLog.ev(WLog.TCP, "dongle-bind-fail", "port" to port, "err" to e.message)
            throw e
        }
        synchronized(lock) { server = s }
        WLog.ev(WLog.TCP, "dongle-bind", "port" to s.localPort, "addr" to bind.hostAddress)
        Thread({ acceptLoop(s) }, "dongle-accept").apply { isDaemon = true }.start()
        return this
    }

    /** Фактический порт (в тестах 0); до open() и после close() -1. */
    val boundPort: Int get() = synchronized(lock) { server?.takeIf { !it.isClosed }?.localPort ?: -1 }

    /** Остановить приём. Уже принятый сокет не трогаем: им владеет сессия. */
    fun close() {
        val s = synchronized(lock) {
            closed = true
            server.also { server = null }
        }
        try {
            s?.close()
        } catch (_: IOException) {
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (true) {
            val sock = try {
                s.accept()
            } catch (e: IOException) {
                if (!synchronized(lock) { closed }) WLog.ev(WLog.TCP, "dongle-accept-fail", "err" to e.message)
                return
            }
            val peer = sock.inetAddress?.hostAddress
            if (synchronized(lock) { closed }) {
                try {
                    sock.close()
                } catch (_: IOException) {
                }
                continue
            }
            // Свисток один. Новое подключение при живом старом = стик перезагрузился (ГУ передёрнуло питание USB),
            // а старый сокет полуоткрыт и FIN не придёт. Побеждает новый: старый закрываем, сессия на нём кончится.
            val old = synchronized(lock) { current?.takeIf { !it.isClosed } }
            if (old != null) {
                WLog.ev(WLog.TCP, "dongle-replace", "old" to old.inetAddress?.hostAddress, "new" to peer)
                try {
                    old.close()
                } catch (_: IOException) {
                }
            }
            try {
                // буфер ядра маленький: противодавление видно в очереди Headswap, а не прячется в ядре
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.sendBufferSize = BUF
                sock.receiveBufferSize = BUF
            } catch (e: IOException) {
                WLog.ev(WLog.TCP, "dongle-opt-fail", "peer" to peer, "err" to e.message)
            }
            synchronized(lock) { current = sock }
            WLog.ev(WLog.TCP, "dongle-accept", "peer" to peer, "snd" to sock.sendBufferSize, "rcv" to sock.receiveBufferSize)
            try {
                onSocket(sock)
            } catch (e: Exception) {
                WLog.ev(WLog.TCP, "dongle-callback-fail", "err" to e.message)
                try {
                    sock.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    companion object {
        const val PORT = 7250
        private const val BUF = 128 * 1024
    }
}
