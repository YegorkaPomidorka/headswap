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
 * Группа распалась (ГУ сняла её, например ушла в своё меню и закрыла CarLife) - сбрасываем вход целиком и зовём
 * [onLost]: следующий TARGET_INFO от ГУ (она переподключает Bluetooth) входит заново, а не теряется на «уже вхожу».
 * Вызовы из любых потоков, состояние - на главной нити.
 * Поиск пиров повторяем раз в [DISCOVER_RETRY_MS], пока не вошли: после короткой стоянки ГУ поднимает группу
 * позже, чем при холодном старте, и одного поиска не хватает (h214, Mazda G6S). ERROR/BUSY от discoverPeers тоже
 * повторяем: сначала через stopPeerDiscovery, после двух неудач подряд - на свежем канале. BUSY при поднятой точке
 * доступа - это она мешает Wi-Fi Direct (Samsung): сообщаем [onBusy] один раз за попытку.
 * Новый TARGET_INFO, пока не вошли, начинает попытку заново с новыми реквизитами (h126). Wi-Fi выключен (P2P тогда
 * тоже): зовём [onWifi] (false) и ждём WIFI_P2P_STATE_ENABLED, потом входим. Поколение [gen] отсекает колбэки и
 * отложенные шаги прошлой попытки.
 */
class P2pJoin(
    private val ctx: Context,
    private val onBusy: () -> Unit = {},
    private val onWifi: (on: Boolean) -> Unit = {},
    private val onJoined: (local: Inet4Address) -> Unit,
    private val onLost: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val mgr: WifiP2pManager? = ctx.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var target: P2pTarget? = null
    private var receiver: BroadcastReceiver? = null
    /** Поколение попытки: колбэки и отложенные шаги прошлой (после стопа или рестарта) с ним не совпадут и ничего не сделают. */
    private var gen = 0
    /** Wi-Fi был выключен на старте: входим, когда P2P включится. */
    private var waitingWifi = false
    /** Мы подключились (или подключаемся): при stop снимаем группу. */
    private var connecting = false
    private var peerMode = false
    /** Когда ушёл connect по пиру: приглашение без ответа дольше [CONNECT_STALE_MS] снимаем и ищем снова. */
    private var connectAt = 0L
    private var discoverRuns = 0
    private var busyReported = false
    /** Неудачных discoverPeers подряд: после [REINIT_AFTER_FAILS] пересоздаём канал. */
    private var discoverFails = 0
    /** Поисков со сбросом как у Baidu: первые [RESET_RUNS] раз через [RESET_RETRY_MS], дальше обычный повтор. */
    private var resets = 0
    /** PEERS_CHANGED за попытку: ноль в логе значит, что фреймворк пиров не присылал вовсе. */
    private var peersChanged = 0
    private var lastPeers: String? = null
    /** Группа собрана, ждём IPv4 по DHCP: приглашение уже не висит. */
    private var formed = false
    private var joinedIp: Inet4Address? = null
    @Volatile private var iface: LanIface? = null

    /** Адрес из P2P-подсети, куда мы вошли (для фильтра входящих TCP). */
    fun owns(a: InetAddress): Boolean = iface?.contains(a) == true

    fun hasPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    fun start(t: P2pTarget) = main.post { startMain(t) }

    fun stop(why: String) = main.post { stopMain(why) }

    @SuppressLint("MissingPermission")
    private fun startMain(t: P2pTarget) {
        if (target != null && joinedIp != null && p2pIface() == null) {
            // вошли, но интерфейса уже нет, а CONNECTION_CHANGED о распаде не пришёл
            WLog.ev(WLog.P2P, "lost", "how" to "нет p2p-интерфейса при новом TARGET_INFO")
            lostMain()
        }
        when (P2pPolicy.onTarget(active = target != null, joined = joinedIp != null)) {
            P2pOnTarget.SKIP -> {
                WLog.ev(WLog.P2P, "skip", "why" to "уже в группе")
                return
            }
            P2pOnTarget.RESTART -> {
                WLog.ev(WLog.P2P, "restart", "why" to "новый TARGET_INFO", "peerMode" to peerMode, "waitingWifi" to waitingWifi)
                stopMain("рестарт")
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
            // без Wi-Fi P2P выключен: discover/connect ответят BUSY. Таймаут и повторы не взводим, ждём включения Wi-Fi
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
        main.removeCallbacks(retry)
        if (t.canJoinBySsid) joinBySsid(m, ch, t) else resetThenDiscover(m, ch)
        main.postDelayed(timeout, JOIN_TIMEOUT_MS)
        main.postDelayed(retry, if (t.canJoinBySsid) DISCOVER_RETRY_MS else RESET_RETRY_MS)
    }

    /**
     * Как Baidu (WifiP2pResetTask, WifiDirectDiscoverTask): перед поиском снимаем остатки прошлой попытки -
     * stopPeerDiscovery, removeGroup, cancelConnect - и только потом discoverPeers. У Pixel на Android 17 голый
     * discoverPeers ни разу не нашёл ГУ G6S, у Samsung и Xiaomi находит (h406).
     */
    @SuppressLint("MissingPermission")
    private fun resetThenDiscover(m: WifiP2pManager, ch: WifiP2pManager.Channel) {
        val g = gen
        peerMode = true   // повтор идёт и тогда, когда цепочка сброса не дошла до discoverPeers
        resets++
        WLog.ev(WLog.P2P, "reset", "n" to resets)
        fun alive() = g == gen && channel === ch && target != null && joinedIp == null && !connecting
        fun step(next: () -> Unit) = object : WifiP2pManager.ActionListener {
            override fun onSuccess() { if (alive()) next() }
            override fun onFailure(reason: Int) { if (alive()) next() }
        }
        m.stopPeerDiscovery(ch, step {
            m.removeGroup(ch, step {
                m.cancelConnect(ch, step { discoverPeers(m, ch) })
            })
        })
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

    private val retry = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val m = mgr ?: return
            val ch = channel ?: return
            if (target == null || joinedIp != null) return
            if (peerMode) {
                if (connecting && !formed && System.currentTimeMillis() - connectAt > CONNECT_STALE_MS) {
                    WLog.ev(WLog.P2P, "connect-stale", "ms" to CONNECT_STALE_MS)
                    m.cancelConnect(ch, null)
                    connecting = false
                }
                if (!connecting) when {
                    // ERROR/BUSY без хотспота (h214, Samsung): фреймворк застрял, свежий канал помогает, как ручной перезапуск
                    discoverFails >= REINIT_AFTER_FAILS -> {
                        WLog.ev(WLog.P2P, "reinit", "fails" to discoverFails)
                        ch.close()
                        val fresh = m.initialize(ctx, Looper.getMainLooper()) { WLog.ev(WLog.P2P, "channel-lost") }
                        channel = fresh
                        discoverFails = 0
                        discoverPeers(m, fresh)
                    }
                    resets < RESET_RUNS -> resetThenDiscover(m, ch)
                    discoverFails > 0 -> m.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = again()
                        override fun onFailure(reason: Int) = again()
                        private val g = gen
                        private fun again() { if (g == gen && channel === ch && target != null && joinedIp == null && !connecting) discoverPeers(m, ch) }
                    })
                    else -> discoverPeers(m, ch)
                }
            }
            main.postDelayed(this, if (peerMode && resets < RESET_RUNS) RESET_RETRY_MS else DISCOVER_RETRY_MS)
        }
    }

    private val timeout = Runnable {
        if (target != null && joinedIp == null) {
            WLog.ev(WLog.P2P, "timeout", "ms" to JOIN_TIMEOUT_MS, "peerMode" to peerMode)
            AppLog.i("Wi-Fi Direct: за ${JOIN_TIMEOUT_MS / 1000} с в группу ГУ не вошли")
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
        val n = ++discoverRuns
        val g = gen
        m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (g != gen) return
                discoverFails = 0
                WLog.ev(WLog.P2P, "discover", "res" to "ok", "n" to n, "peersChanged" to peersChanged)
            }
            override fun onFailure(reason: Int) {
                if (g != gen) return
                discoverFails++
                val hotspot = hotspotUp()
                WLog.ev(WLog.P2P, "discover", "res" to "fail", "reason" to reasonName(reason), "n" to n, "hotspot" to hotspot)
                // BUSY бывает и без хотспота (h214): тогда просто повторяем, подсказку не показываем
                if (reason == WifiP2pManager.BUSY && hotspot && !busyReported && target != null) {
                    busyReported = true
                    AppLog.i("Wi-Fi Direct занят (BUSY): включена точка доступа на телефоне")
                    onBusy()
                }
            }
        })
        // первые пиры могли прийти до нового поиска: проверяем уже известный список
        if (n > 1) onPeers()
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
            val devs = list?.deviceList.orEmpty()
            val exact = devs.firstOrNull { it.deviceName == name }
            // запасное сравнение: пробелы по краям и регистр (h406, Pixel)
            val dev: WifiP2pDevice? = exact ?: devs.firstOrNull { it.deviceName?.trim().equals(name.trim(), ignoreCase = true) }
            logPeers(devs, name, dev)
            if (dev == null || connecting || target == null) return@requestPeers
            connecting = true
            connectAt = System.currentTimeMillis()
            WLog.ev(WLog.P2P, "peer", "matched" to true, "how" to if (exact != null) "exact" else "loose", "status" to dev.status)
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

    /**
     * Список пиров в лог, только когда он поменялся: без него не отличить «ГУ не видна» от «видна, но имя не то» (h406).
     * Имена чужих устройств маскируем: начало, длина и признак совпадения с именем ГУ.
     */
    private fun logPeers(devs: Collection<WifiP2pDevice>, name: String, dev: WifiP2pDevice?) {
        val sig = devs.joinToString { "${it.deviceName}/${it.status}" }
        if (sig == lastPeers) return
        lastPeers = sig
        val names = devs.joinToString(" ") { d ->
            val n = d.deviceName.orEmpty()
            val eq = when {
                n == name -> "=="
                n.trim().equals(name.trim(), ignoreCase = true) -> "~="
                else -> ""
            }
            "${maskName(n)}$eq/${d.status}"
        }
        WLog.ev(WLog.P2P, "peers", "n" to devs.size, "want" to maskName(name), "names" to names, "match" to (dev != null))
    }

    private fun maskName(n: String) = "${n.take(4)}…(${n.length})"

    private fun onConnectionChanged() {
        val m = mgr ?: return
        val ch = channel ?: return
        val g = gen
        m.requestConnectionInfo(ch) { info ->
            if (g != gen || target == null) return@requestConnectionInfo
            if (info == null || !info.groupFormed) {
                formed = false
                if (joinedIp != null) {
                    WLog.ev(WLog.P2P, "lost")
                    lostMain()
                }
                return@requestConnectionInfo
            }
            formed = true
            if (info.isGroupOwner) {
                WLog.ev(WLog.P2P, "formed", "owner" to "phone", "note" to "ждали ГУ владельцем")
            }
            awaitIp(g, 0)
        }
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

    /** Точка доступа телефона поднята: swlan* у Samsung, ap* у остальных, с IPv4. */
    private fun hotspotUp(): Boolean = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { ni ->
        (ni.name.startsWith("swlan") || ni.name.startsWith("ap")) && ni.isUp && ni.inetAddresses.toList().any { it is Inet4Address }
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
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> { peersChanged++; onPeers() }
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

    private fun lostMain() {
        AppLog.i("Wi-Fi Direct: группа ГУ распалась, жду от ГУ новый TARGET_INFO")
        stopMain("группа распалась")
        onLost()
    }

    private fun stopMain(why: String) {
        gen++
        main.removeCallbacks(timeout)
        main.removeCallbacks(retry)
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
        discoverRuns = 0
        busyReported = false
        discoverFails = 0
        resets = 0
        peersChanged = 0
        lastPeers = null
        formed = false
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
        const val DISCOVER_RETRY_MS = 15_000L
        const val RESET_RETRY_MS = 5_000L
        const val RESET_RUNS = 6
        const val CONNECT_STALE_MS = 20_000L
        const val REINIT_AFTER_FAILS = 2
        const val IP_RETRY_MS = 500L
        const val IP_ATTEMPTS = 30
    }
}
