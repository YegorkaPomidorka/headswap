package com.yegorkapomidorka.headswap.link.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.link.diag.WLog
import java.io.IOException
import java.util.UUID

/**
 * Ветка A (Bluetooth + Wi-Fi Direct): RFCOMM-серверы `carlife`, как у Baidu MD (BluetoothClient:28-29,76,119).
 * Если ГУ умеет беспроводной CarLife через Wi-Fi Direct, она сама подключается сюда. Обмен: WIRELESS_INFO_REQUEST,
 * TARGET_INFO_REQUEST, по реквизитам из TARGET_INFO телефон входит в P2P-группу ГУ ([P2pJoin]), на IP_REQUEST
 * отвечает MD_WIFI_IP с адресом в группе. Дальше ГУ подключает TCP-каналы к Tcp6Listener (пускает [ownsPeer]).
 * Bluetooth-соединение держим, пока ждём машину и идёт сессия. Секретов в лог не пишет (см. BtSummary).
 */
class CarlifeBtProbe(
    private val ctx: Context,
    /** ГУ подключилась к RFCOMM (нить bt-accept-*). */
    private val onAccept: () -> Unit = {},
    /** ГУ прислала реквизиты Wi-Fi Direct, mac - адрес её Bluetooth (нить bt-accept-*). */
    private val onTargetInfo: (mac: String?) -> Unit = {},
    /** Wi-Fi Direct занят (обычно включён хотспот телефона), главная нить. */
    private val onP2pBusy: () -> Unit = {},
    /** Wi-Fi на телефоне выключен (false, Wi-Fi Direct не войдёт) или включён (true) на входе в группу ГУ (главная нить). */
    private val onWifi: (on: Boolean) -> Unit = {},
) {
    @Volatile private var running = false
    private val servers = ArrayList<BluetoothServerSocket>()
    private val lock = Any()
    @Volatile private var accepts = 0
    @Volatile private var active: BluetoothSocket? = null
    private val p2p = P2pJoin(ctx, onBusy = { onP2pBusy() }, onWifi = { on -> onWifi(on) }, onJoined = { ip -> onP2pJoined(ip) }, onLost = { onP2pLost() })
    /** Адрес телефона в группе ГУ (после входа) и запрос IP от ГУ: MD_WIFI_IP уходит, когда есть оба. */
    @Volatile private var p2pIp: String? = null
    @Volatile private var ipRequested = false
    private val txLock = Any()
    private val main = Handler(Looper.getMainLooper())
    /** Последние реквизиты от ГУ, даже пропущенные «уже в группе»: по ним входим заново после распада группы (h211). */
    @Volatile private var lastTarget: P2pTarget? = null
    /** Поколение восстановления после распада группы: новый BT-сеанс, вход в группу или стоп его отменяют. */
    @Volatile private var rejoinGen = 0
    @Volatile private var rejoining = false

    /** Входящий TCP из P2P-группы ГУ: пускаем в Tcp6Listener наравне с подсетями маяка. */
    fun ownsPeer(a: java.net.InetAddress): Boolean = p2p.owns(a)

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        p2pIp = null
        ipRequested = false
        lastTarget = null
        rejoinGen++
        rejoining = false
        accepts = 0     // за этот запуск: в строке `stop` accepts= считаются подключения ГУ с последнего start
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> return fail("нет адаптера")
            !hasPermission() -> return fail("нет разрешения BLUETOOTH_CONNECT")
            !adapter.isEnabled -> return fail("Bluetooth выключен")
        }
        for ((tag, uuid) in UUIDS) {
            val ss = try {
                adapter!!.listenUsingRfcommWithServiceRecord(SERVICE_NAME, uuid)
            } catch (e: Exception) {
                WLog.ev(WLog.BT, "listen-fail", "uuid" to tag, "err" to e.message)
                continue
            }
            synchronized(lock) { servers += ss }
            WLog.ev(WLog.BT, "listen", "uuid" to tag, "name" to SERVICE_NAME)
            Thread({ acceptLoop(tag, ss) }, "bt-accept-$tag").apply { isDaemon = true }.start()
        }
        CarLifeState.btProbe = if (synchronized(lock) { servers.isEmpty() }) "не слушает" else "слушаю carlife, ГУ не подключалась"
    }

    fun stop(why: String) {
        val list = synchronized(lock) {
            if (!running) return
            running = false
            servers.toList().also { servers.clear() }
        }
        for (s in list) try { s.close() } catch (_: IOException) {}
        rejoinGen++
        rejoining = false
        active?.let { try { it.close() } catch (_: IOException) {} }
        p2p.stop(why)
        WLog.ev(WLog.BT, "stop", "why" to why, "accepts" to accepts)
    }

    private fun fail(why: String) {
        running = false
        CarLifeState.btProbe = "не слушает: $why"
        WLog.ev(WLog.BT, "off", "why" to why)
    }

    private fun acceptLoop(tag: String, ss: BluetoothServerSocket) {
        while (running) {
            val s = try {
                ss.accept()
            } catch (_: IOException) {
                return
            }
            accepts++
            onAccept()
            talk(tag, s)
        }
    }

    @SuppressLint("MissingPermission")
    private fun talk(tag: String, s: BluetoothSocket) {
        val dev = s.remoteDevice
        val name = try { dev?.name } catch (_: SecurityException) { null }
        WLog.ev(WLog.BT, "accept", "uuid" to tag, "dev" to WLog.maskMac(dev?.address), "devName" to name)
        AppLog.i("BT carlife: ГУ подключилась по Bluetooth ($tag, ${name ?: "?"})")
        CarLifeState.btProbe = "ГУ подключилась ($tag, ${name ?: "?"})"
        val t0 = SystemClock.elapsedRealtime()
        val parser = BtFrameParser()
        var why = "?"
        var targetAsked = false
        active?.let { old -> try { old.close() } catch (_: IOException) {} }      // ГУ переподключилась: старый канал закрываем
        active = s
        ipRequested = false
        rejoinGen++      // ГУ переподключила BT: TARGET_INFO придёт обычным обменом, свои запросы снимаем
        rejoining = false
        try {
            val inp = s.inputStream
            Thread.sleep(300)
            send(s, BtMsg.MD_WIRELESS_INFO_REQUEST)
            val buf = ByteArray(1024)
            while (running) {
                val n = inp.read(buf)
                if (n < 0) { why = "EOF"; break }
                for (f in parser.feed(buf, n)) {
                    val kv: List<Pair<String, Any?>> = when (f.type) {
                        BtMsg.HU_WIRELESS_INFO -> BtSummary.wirelessInfo(f.body)
                        BtMsg.HU_TARGET_INFO -> BtSummary.targetInfo(f.body)
                        BtMsg.HU_STATUS -> BtSummary.status(f.body)
                        else -> listOf("shape" to BtSummary.shape(f.body))
                    }
                    WLog.ev(WLog.BT, "rx", "type" to BtMsg.name(f.type), "len" to f.body.size, *kv.toTypedArray())
                    when (f.type) {
                        BtMsg.HU_WIRELESS_INFO -> {
                            CarLifeState.btProbe = "ГУ ответила: ${kv.joinToString(" ") { "${it.first}=${it.second}" }}"
                            if (!targetAsked) {
                                targetAsked = true
                                send(s, BtMsg.MD_TARGET_INFO_REQUEST)
                            }
                        }
                        BtMsg.HU_TARGET_INFO -> {
                            CarLifeState.btProbe = "ГУ прислала реквизиты Wi-Fi Direct, вхожу в группу"
                            val t = P2pTarget.parse(f.body)
                            if (t == null) WLog.ev(WLog.P2P, "off", "why" to "TARGET_INFO не разобран")
                            else {
                                lastTarget = t
                                onTargetInfo(dev?.address)
                                p2p.start(t)
                            }
                        }
                        BtMsg.HU_IP_REQUEST -> {
                            ipRequested = true
                            CarLifeState.btProbe = "ГУ просит IP телефона" + if (p2pIp == null) ", жду вход в группу" else ""
                            sendIpIfReady()
                        }
                    }
                }
            }
            if (why == "?") why = "остановлено"
        } catch (e: Exception) {
            why = "ошибка: ${e.message}"
        } finally {
            if (active === s) active = null
            try { s.close() } catch (_: IOException) {}
            WLog.ev(WLog.BT, "close", "why" to why, "aliveMs" to SystemClock.elapsedRealtime() - t0, "skipped" to parser.skipped)
        }
    }

    private fun send(s: BluetoothSocket, type: Int, body: ByteArray? = null) {
        synchronized(txLock) {
            s.outputStream.write(BtMsg.frame(type, body))
            s.outputStream.flush()
        }
        WLog.ev(WLog.BT, "tx", "type" to BtMsg.name(type), "len" to (body?.size ?: 0))
    }

    private fun onP2pJoined(ip: java.net.Inet4Address) {
        p2pIp = ip.hostAddress
        CarLifeState.btProbe = "телефон в группе ГУ (${ip.hostAddress})" + if (ipRequested) "" else ", ГУ ещё не просила IP"
        if (rejoining) WLog.ev(WLog.P2P, "rejoin-joined", "ipRequested" to ipRequested)
        rejoining = false
        rejoinGen++
        // MD_WIFI_IP только на IP_REQUEST, как Baidu (BluetoothWirlessHelper:65-69, 289-291): без запроса не шлём
        sendIpIfReady()
    }

    /**
     * Группа ГУ распалась (главная нить). Если Bluetooth тоже упал, ГУ переподключит его и пришлёт TARGET_INFO сама.
     * Если жив (h211: Mazda G6S на заднем ходу снимает Wi-Fi Direct, RFCOMM держит), новый TARGET_INFO не придёт до сна
     * машины: входим заново по последним реквизитам и просим TARGET_INFO сами, пока не войдём.
     */
    private fun onP2pLost() {
        p2pIp = null
        val s = active
        if (!running || s == null) return
        rejoining = true
        val g = ++rejoinGen
        WLog.ev(WLog.P2P, "rejoin-arm", "cached" to (lastTarget != null))
        AppLog.i("Wi-Fi Direct: группа ГУ распалась, Bluetooth жив - сама прошу у ГУ реквизиты")
        scheduleRejoin(g, s, 0)
    }

    private fun scheduleRejoin(g: Int, s: BluetoothSocket, attempt: Int) {
        val d = P2pPolicy.rejoinDelayMs(attempt)
        if (d == null) {
            WLog.ev(WLog.P2P, "rejoin-give-up", "attempts" to attempt)
            AppLog.i("Wi-Fi Direct: ГУ не подняла группу за $attempt запросов, жду переподключения Bluetooth")
            return
        }
        main.postDelayed({ rejoinStep(g, s, attempt) }, d)
    }

    private fun rejoinStep(g: Int, s: BluetoothSocket, attempt: Int) {
        if (g != rejoinGen || !running || active !== s || p2pIp != null) return
        if (attempt == 0) lastTarget?.let {
            WLog.ev(WLog.P2P, "rejoin-cached")
            p2p.start(it)
        }
        Thread({
            try {
                WLog.ev(WLog.P2P, "rejoin-ask", "n" to attempt + 1)
                send(s, BtMsg.MD_TARGET_INFO_REQUEST)
            } catch (e: IOException) {
                WLog.ev(WLog.BT, "tx-fail", "type" to "TARGET_INFO_REQUEST", "err" to e.message)
            }
        }, "bt-rejoin").apply { isDaemon = true }.start()
        scheduleRejoin(g, s, attempt + 1)
    }

    /** MD_WIFI_IP после IP_REQUEST и входа в группу; на повторный запрос того же адреса отвечаем снова. */
    private fun sendIpIfReady() {
        val ip = p2pIp ?: return
        val s = active ?: return
        if (!ipRequested) return
        try {
            send(s, BtMsg.MD_WIFI_IP, BtMsg.wifiIp(ip))
            ipRequested = false
            CarLifeState.btProbe = "отдал ГУ IP $ip, жду TCP от ГУ"
            AppLog.i("BT carlife: отдал ГУ адрес телефона $ip")
        } catch (e: IOException) {
            WLog.ev(WLog.BT, "tx-fail", "type" to "WIFI_IP", "err" to e.message)
        }
    }

    companion object {
        const val SERVICE_NAME = "carlife"
        val UUIDS = listOf(
            "spp" to UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
            "carlife" to UUID.fromString("a45bc7e5-bb50-4949-9de1-f78299cf6d78"),
        )
    }
}
