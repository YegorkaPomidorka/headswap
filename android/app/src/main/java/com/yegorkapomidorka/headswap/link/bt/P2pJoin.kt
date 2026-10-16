package com.yegorkapomidorka.headswap.link.bt

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.WpsInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.link.diag.WLog
import com.yegorkapomidorka.headswap.link.wifi.LanIface
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Ветка A, шаг Wi-Fi Direct: телефон входит клиентом в P2P-группу ГУ (реквизиты из TARGET_INFO по Bluetooth).
 * Сначала напрямую по ssid и паролю (WifiP2pConfig.Builder), если не вышло - находим ГУ среди пиров по имени
 * и подключаемся PBC с groupOwnerIntent=0 (владелец группы - ГУ). Когда у интерфейса p2p* есть IPv4,
 * зовём [onJoined]: адрес телефона уходит ГУ в MD_WIFI_IP, ГУ подключает TCP-каналы.
 * Wi-Fi выключен (P2P тогда тоже, discover отвечает BUSY): зовём [onWifi] (false) и ждём включения, потом входим.
 * Не вошли за [JOIN_TIMEOUT_MS] - сброс, следующий TARGET_INFO (переподключение BT) начинает заново.
 * Вызовы из любых потоков, состояние - на главной нити.
 */
class P2pJoin(
    private val ctx: Context,
    private val onWifi: (on: Boolean) -> Unit = {},
    private val onJoined: (local: Inet4Address) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val mgr: WifiP2pManager? = ctx.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var target: P2pTarget? = null
    private var receiver: BroadcastReceiver? = null
    /** Поколение попытки: колбэки и отложенные шаги прошлой (после сброса или рестарта) его не совпадут и ничего не сделают. */
    private var gen = 0
    /** Мы подключились (или подключаемся): при stop снимаем группу. */
    private var connecting = false
    private var peerMode = false
    /** Wi-Fi был выключен на старте: входим, когда P2P включится. */
    private var waitingWifi = false
    private var busyRetries = 0
    private var joinedIp: Inet4Address? = null
    @Volatile private var iface: LanIface? = null

    /** Адрес из P2P-подсети, куда мы вошли (для фильтра входящих TCP). */
    fun owns(a: InetAddress): Boolean = iface?.contains(a) == true

    fun hasPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    fun start(t: P2pTarget) = main.post { startMain(t) }

    fun stop(why: String) = main.post { reset(why) }

    @SuppressLint("MissingPermission")
    private fun startMain(t: P2pTarget) {
        when (P2pPolicy.onTarget(active = target != null, joined = joinedIp != null)) {
            P2pOnTarget.SKIP -> {
                WLog.ev(WLog.P2P, "skip", "why" to "уже в группе")
                return
            }
            P2pOnTarget.RESTART -> {
                WLog.ev(WLog.P2P, "restart", "why" to "новый TARGET_INFO", "peerMode" to peerMode, "waitingWifi" to waitingWifi)
                reset("рестарт")
            }
            P2pOnTarget.START -> {}
        }
        val m = mgr ?: return fail("нет WifiP2pManager")
        if (!hasPermission()) return fail("нет разрешения «Устройства поблизости»")
        gen++
        target = t
        val ch = m.initialize(ctx, Looper.getMainLooper()) { WLog.ev(WLog.P2P, "channel-lost") }
        channel = ch
        registerReceiver()
        WLog.ev(WLog.P2P, "start", "bySsid" to t.canJoinBySsid, "peerName" to (t.deviceName != null), "freq" to t.freqMhz)
        AppLog.i("Wi-Fi Direct: вхожу в группу ГУ (${if (t.canJoinBySsid) "по ssid" else "по имени пира"})")
        if (!wifiEnabled()) {
            // без Wi-Fi P2P выключен: discover/connect ответят BUSY. Таймаут не взводим: ждём, пока человек включит Wi-Fi
            waitingWifi = true
            WLog.ev(WLog.P2P, "wifi-off")
            AppLog.i("Wi-Fi Direct: Wi-Fi на телефоне выключен, жду включения")
            onWifi(false)
            return
        }
        onWifi(true)      // Wi-Fi включили до нового TARGET_INFO: снять просьбу, если висела
        begin(m, ch, t)
    }

    private fun wifiEnabled(): Boolean = try {
        ctx.getSystemService(WifiManager::class.java)?.isWifiEnabled != false
    } catch (_: SecurityException) {
        true
    }

    private fun begin(m: WifiP2pManager, ch: WifiP2pManager.Channel, t: P2pTarget) {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, JOIN_TIMEOUT_MS)
        if (t.canJoinBySsid) joinBySsid(m, ch, t) else discoverPeers(m, ch)
    }

    private val timeout = Runnable {
        if (target != null && joinedIp == null) {
            WLog.ev(WLog.P2P, "timeout", "ms" to JOIN_TIMEOUT_MS, "peerMode" to peerMode, "busyRetries" to busyRetries)
            AppLog.i("Wi-Fi Direct: за ${JOIN_TIMEOUT_MS / 1000} с в группу ГУ не вошли, жду новый TARGET_INFO")
            reset("таймаут")
        }
    }

    @SuppressLint("MissingPermission")
    private fun joinBySsid(m: WifiP2pManager, ch: WifiP2pManager.Channel, t: P2pTarget) {
        val cfg = try {
            val b = WifiP2pConfig.Builder().setNetworkName(t.ssid!!).setPassphrase(t.passphrase!!)
            if (t.freqMhz != null) b.setGroupOperatingFrequency(t.freqMhz) else b.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
            b.build()
        } catch (e: RuntimeException) {
            WLog.ev(WLog.P2P, "ssid-config-fail", "err" to e.javaClass.simpleName)
            return discoverPeers(m, ch)
        }
        val g = gen
        connecting = true
        m.connect(ch, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (g == gen) WLog.ev(WLog.P2P, "connect", "mode" to "ssid", "res" to "invited")
            }
            override fun onFailure(reason: Int) {
                if (g != gen) return
                WLog.ev(WLog.P2P, "connect", "mode" to "ssid", "res" to "fail", "reason" to reasonName(reason))
                connecting = false
                if (target != null) discoverPeers(m, ch)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeers(m: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (target?.deviceName == null) return fail("нет имени пира ГУ")
        peerMode = true
        val g = gen
        m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (g == gen) WLog.ev(WLog.P2P, "discover", "res" to "ok", "retry" to busyRetries)
            }
            override fun onFailure(reason: Int) {
                if (g != gen) return
                WLog.ev(WLog.P2P, "discover", "res" to "fail", "reason" to reasonName(reason), "retry" to busyRetries)
                if (!P2pPolicy.retryDiscover(busy = reason == WifiP2pManager.BUSY, retriesDone = busyRetries)) return
                busyRetries++
                main.postDelayed({
                    if (g == gen && target != null && joinedIp == null && !connecting) discoverPeers(m, ch)
                }, P2pPolicy.BUSY_RETRY_MS)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun onPeers() {
        val m = mgr ?: return
        val ch = channel ?: return
        val name = target?.deviceName ?: return
        if (!peerMode || connecting || joinedIp != null) return
        val g = gen
        m.requestPeers(ch) { list ->
            if (g != gen) return@requestPeers
            val dev: WifiP2pDevice = list.deviceList.firstOrNull { it.deviceName == name } ?: return@requestPeers
            if (connecting || target == null) return@requestPeers
            connecting = true
            WLog.ev(WLog.P2P, "peer", "matched" to true, "status" to dev.status)
            val cfg = WifiP2pConfig().apply {
                deviceAddress = dev.deviceAddress
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 0
            }
            m.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (g == gen) WLog.ev(WLog.P2P, "connect", "mode" to "peer", "res" to "invited")
                }
                override fun onFailure(reason: Int) {
                    if (g != gen) return
                    WLog.ev(WLog.P2P, "connect", "mode" to "peer", "res" to "fail", "reason" to reasonName(reason))
                    connecting = false
                }
            })
        }
    }

    private fun onConnectionChanged() {
        val m = mgr ?: return
        val ch = channel ?: return
        val g = gen
        m.requestConnectionInfo(ch) { info ->
            if (g != gen || target == null) return@requestConnectionInfo
            if (info == null || !info.groupFormed) {
                if (joinedIp != null) {
                    WLog.ev(WLog.P2P, "lost")
                    AppLog.i("Wi-Fi Direct: группа ГУ распалась")
                    joinedIp = null
                    iface = null
                }
                return@requestConnectionInfo
            }
            if (info.isGroupOwner) {
                WLog.ev(WLog.P2P, "formed", "owner" to "phone", "note" to "ждали ГУ владельцем")
            }
            awaitIp(g, 0)
        }
    }

    /** Wi-Fi включили, пока ждали: P2P поднялся, входим в группу. */
    private fun onP2pState(enabled: Boolean) {
        val m = mgr ?: return
        val ch = channel ?: return
        val t = target ?: return
        if (!enabled || !waitingWifi) return
        waitingWifi = false
        WLog.ev(WLog.P2P, "wifi-on")
        AppLog.i("Wi-Fi Direct: Wi-Fi включён, вхожу в группу ГУ")
        onWifi(true)
        begin(m, ch, t)
    }

    /** IPv4 на p2p-интерфейсе появляется после DHCP, иногда через секунду-две после groupFormed. */
    private fun awaitIp(g: Int, attempt: Int) {
        if (g != gen || target == null || joinedIp != null) return
        val lan = p2pIface()
        if (lan == null) {
            if (attempt < IP_ATTEMPTS) main.postDelayed({ awaitIp(g, attempt + 1) }, IP_RETRY_MS)
            else WLog.ev(WLog.P2P, "no-ip", "waitedMs" to IP_ATTEMPTS * IP_RETRY_MS)
            return
        }
        iface = lan
        joinedIp = lan.ip
        main.removeCallbacks(timeout)
        WLog.ev(WLog.P2P, "joined", "iface" to lan.toString())
        AppLog.i("Wi-Fi Direct: вошли в группу ГУ, адрес телефона ${lan.ip.hostAddress}")
        onJoined(lan.ip)
    }

    private fun p2pIface(): LanIface? {
        for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
            if (!ni.name.startsWith("p2p") || !ni.isUp) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address as? Inet4Address ?: continue
                val b = ia.broadcast as? Inet4Address ?: a
                return LanIface(ni.name, a, ia.networkPrefixLength.toInt(), b)
            }
        }
        return null
    }

    private fun registerReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> onPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnectionChanged()
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> onP2pState(
                        i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    )
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    /** Остановка, таймаут или рестарт: снять discover/группу, канал и receiver; следующий start начнёт с нуля. */
    private fun reset(why: String) {
        gen++
        main.removeCallbacks(timeout)
        receiver?.let { try { ctx.unregisterReceiver(it) } catch (_: IllegalArgumentException) {} }
        receiver = null
        val m = mgr
        val ch = channel
        if (m != null && ch != null) {
            if (peerMode) m.stopPeerDiscovery(ch, null)
            if (connecting || joinedIp != null) m.removeGroup(ch, null)
            ch.close()
        }
        if (target != null) WLog.ev(WLog.P2P, "stop", "why" to why, "joined" to (joinedIp != null))
        channel = null
        target = null
        connecting = false
        peerMode = false
        waitingWifi = false
        busyRetries = 0
        joinedIp = null
        iface = null
    }

    private fun fail(why: String) {
        WLog.ev(WLog.P2P, "off", "why" to why)
        AppLog.i("Wi-Fi Direct: $why")
    }

    private fun reasonName(r: Int) = when (r) {
        WifiP2pManager.ERROR -> "error"
        WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no_service_requests"
        else -> r.toString()
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 30_000L
        const val IP_RETRY_MS = 500L
        const val IP_ATTEMPTS = 30
    }
}
