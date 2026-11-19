package com.yegorkapomidorka.headswap.link.wifi

import com.yegorkapomidorka.headswap.link.diag.WLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP в Wi-Fi Direct-группе ГУ, пока она не подключила TCP (#111, по образцу FT CarBeacon): раз в [intervalMs] маяк
 * «CarlifeHost» на 7999 в broadcast группы и unicast владельцу (ГУ) и JSON-привет [BeaconCodec.hello] ГУ на 8999.
 * HotspotBeacon сюда не годится: p2p-интерфейсы он исключает, а свип по /24 группе ни к чему. Сокет привязан к адресу
 * телефона в группе, чтобы пакеты не ушли в основную сеть. Ответы ГУ на этот сокет пишем в WL-лог (адрес и длина,
 * без содержимого): по ним видно, слушает ли ГУ 8999.
 */
class P2pHello(
    private val local: LanIface,
    /** Адрес ГУ в группе; null - берём .1 подсети (DHCP владельца группы у Android и у ГУ Baidu). */
    owner: Inet4Address?,
    private val hello: () -> ByteArray,
    private val intervalMs: Long = 1_500,
    private val beaconPort: Int = BeaconCodec.BEACON_PORT,
    private val helloPort: Int = BeaconCodec.PROBE_PORT,
) {
    val owner: Inet4Address = owner ?: guessOwner(local)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var socket: DatagramSocket? = null

    @Volatile var rounds = 0L
        private set
    @Volatile var replies = 0L
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "wl-p2p-hello").apply { isDaemon = true; start() }
    }

    fun stop(why: String) {
        if (!running.compareAndSet(true, false)) return
        socket?.close()
        thread?.interrupt()
        WLog.ev("udp", "hello-stop", "why" to why, "rounds" to rounds, "replies" to replies)
    }

    private fun loop() {
        var firstOk = false
        var errLogged = false
        try {
            DatagramSocket(null).use { s ->
                socket = s
                s.reuseAddress = true
                s.broadcast = true
                s.bind(InetSocketAddress(local.ip, 0))
                s.soTimeout = intervalMs.toInt()
                WLog.ev("udp", "hello-start", "iface" to local.toString(), "owner" to owner.hostAddress)
                val buf = ByteArray(1500)
                while (running.get()) {
                    val h = hello()
                    val packets = listOf<Triple<ByteArray, InetAddress, Int>>(
                        Triple(BeaconCodec.BEACON, local.broadcast, beaconPort),
                        Triple(BeaconCodec.BEACON, owner, beaconPort),
                        Triple(h, owner, helloPort),
                    )
                    var ok = 0
                    for ((data, to, port) in packets) {
                        try {
                            s.send(DatagramPacket(data, data.size, to, port))
                            ok++
                        } catch (e: Exception) {
                            if (!errLogged) {
                                errLogged = true
                                WLog.ev("udp", "hello-fail", "to" to "${to.hostAddress}:$port", "err" to "${e.javaClass.simpleName}: ${e.message}")
                            }
                        }
                    }
                    rounds++
                    if (!firstOk && ok > 0) {
                        firstOk = true
                        WLog.ev("udp", "hello-first", "sent" to ok, "owner" to owner.hostAddress)
                    }
                    if (rounds % SUMMARY_ROUNDS == 0L) WLog.ev("udp", "hello", "rounds" to rounds, "replies" to replies)
                    // ждём ответ ГУ до следующего раунда, вместо sleep
                    val until = System.currentTimeMillis() + intervalMs
                    while (running.get()) {
                        val left = until - System.currentTimeMillis()
                        if (left <= 0) break
                        s.soTimeout = left.toInt().coerceAtLeast(1)
                        val p = DatagramPacket(buf, buf.size)
                        try {
                            s.receive(p)
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                        replies++
                        if (replies <= 5 || replies % 50 == 0L) {
                            WLog.ev("udp", "hello-reply", "from" to "${p.address.hostAddress}:${p.port}", "len" to p.length, "n" to replies)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) WLog.ev("udp", "hello-dead", "err" to "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            socket = null
        }
    }

    companion object {
        private const val SUMMARY_ROUNDS = 20L

        /** .1 подсети интерфейса: так адресует владельца группы Android (192.168.49.1) и ГУ Baidu. */
        fun guessOwner(l: LanIface): Inet4Address {
            val mask = if (l.prefix == 0) 0 else -1 shl (32 - l.prefix)
            return LanIface.fromInt((LanIface.toInt(l.ip) and mask) + 1)
        }
    }
}
