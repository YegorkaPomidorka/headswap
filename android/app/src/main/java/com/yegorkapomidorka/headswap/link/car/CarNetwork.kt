package com.yegorkapomidorka.headswap.link.car

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yegorkapomidorka.headswap.link.diag.WLog
import java.net.Inet4Address

/** Состояние запроса сети машины (в отчёте как [label]). */
enum class CarNetState(val label: String) {
    IDLE("idle"),
    REQUESTING("requesting"),
    AVAILABLE("available"),
    UNAVAILABLE("unavailable"),
}

/**
 * Вход телефона в Wi-Fi машины через WifiNetworkSpecifier: локальная сеть без интернета, интернет остаётся на основной
 * сети (bindProcessToNetwork не используем). Колбэки приходят на главной нити. Запрос держится, пока не вызван [release]
 * (или сеть не пропала: тогда [state] сбрасывается в IDLE и вызывающий решает, просить ли заново).
 * Первая попытка WPA2, при отказе с паролем - одна повторная WPA3; без пароля сеть открытая.
 * Если телефон уже в подсети прошлой сессии с ГУ, сеть держим через requestNetwork без specifier: specifier поднимает
 * второе подключение с новой MAC, и ГУ заводит ещё один CarLife-девайс (ADR 0053). Имя сети на Android 13+ из снимка
 * не видно, поэтому сравниваем адреса. SSID и пароль в WL не попадают, из исключений в лог идёт только имя класса.
 *
 * [onState] - зеркало состояния для отчёта: пишет только экземпляр сервиса, проверка из настроек его не передаёт.
 */
class CarNetwork(ctx: Context, private val onState: (CarNetState) -> Unit = {}) {
    private val app = ctx.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    var state: CarNetState = CarNetState.IDLE
        private set(v) {
            field = v
            onState(v)
        }

    /** Сеть машины, пока [state] == AVAILABLE. */
    @Volatile var current: Network? = null
        private set

    /** Интерфейс сети машины (wlan0/wlan1): по нему отбираем адреса для маяка. Известен вместе с [current]. */
    @Volatile var ifaceName: String? = null
        private set

    private var watch: Watch? = null

    private enum class Sec(val label: String) { OPEN("open"), WPA2("wpa2"), WPA3("wpa3") }

    private class Params(
        val ssid: String,
        val pass: String,
        val timeoutMs: Long,
        val onAvailable: (Network) -> Unit,
        val onUnavailable: (String) -> Unit,
        val onLost: () -> Unit,
        val bssid: String?,
    )

    /**
     * Запросить сеть. Уже просим или получили - ничего не делаем. [onAvailable] зовётся, когда известны и сеть, и имя
     * интерфейса; [onUnavailable] - сеть не нашлась, отказ в диалоге или ошибка запроса (reason - короткий код).
     * [bssid] (aa:bb:cc:dd:ee:ff) сужает specifier до одной точки доступа: ICCOA присылает его вместе с SSID.
     */
    fun request(
        ssid: String,
        pass: String,
        timeoutMs: Long,
        onAvailable: (Network) -> Unit,
        onUnavailable: (String) -> Unit,
        onLost: () -> Unit,
        bssid: String? = null,
    ) {
        if (state == CarNetState.REQUESTING || state == CarNetState.AVAILABLE) {
            WLog.ev(NET, "car-request-skip", "state" to state.label)
            return
        }
        logStaConcurrency()
        val p = Params(ssid, pass, timeoutMs, onAvailable, onUnavailable, onLost, bssid)
        val lan = rememberedLan(ssid)
        if (lan != null && classify(lan) != Seen.OTHER) {
            hold(p, lan)
            return
        }
        specifier(p)
    }

    /** Удачная защита из [CarWifi], если просим именно сохранённую сеть машины. */
    private fun rememberedSec(ssid: String): String? = CarWifi(app).let { if (it.ssid == ssid) it.sec else null }

    /** Снять запрос (и сеть, если её держал только он). Можно звать сколько угодно раз. */
    fun release(why: String) {
        val c = watch
        watch = null
        if (c != null) {
            c.unregister()
            WLog.ev(NET, "car-release", "why" to why)
        }
        current = null
        ifaceName = null
        if (state != CarNetState.IDLE) state = CarNetState.IDLE
    }

    /** Подсеть и шлюз прошлой сессии в этой сети машины. Имя сети не читаем: на Android 13+ оно скрыто. */
    private fun rememberedLan(ssid: String): Lan? {
        val w = CarWifi(app)
        if (w.ssid != ssid) return null
        val addr = CarNetPolicy.ipv4(w.lanAddr) ?: return null
        if (w.lanPrefix !in 16..30) return null
        return Lan(addr, w.lanPrefix, CarNetPolicy.ipv4(w.lanGw))
    }

    /**
     * Есть ли уже Wi-Fi машины. MATCH — непроверенный Wi-Fi в запомненной подсети или с тем же шлюзом.
     * PENDING — непроверенный Wi-Fi ещё без IPv4: подождём DHCP до [HOLD_MS], specifier пока не шлём.
     */
    @Suppress("DEPRECATION") // снимок на момент запроса; колбэк здесь ещё не за что держать
    private fun classify(lan: Lan): Seen = try {
        var pending = false
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            if (onLan(caps, lp, lan)) return Seen.MATCH
            if (lp.linkAddresses.none { it.address is Inet4Address } &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) pending = true
        }
        if (pending) Seen.PENDING else Seen.OTHER
    } catch (e: Exception) {
        WLog.ev(NET, "car-lan-fail", "err" to e.javaClass.simpleName)
        Seen.OTHER
    }

    /**
     * Сеть машины узнаём только среди Wi-Fi без проверенного интернета: домашний роутер часто в той же
     * 192.168.1.0/24, что и ГУ. Сохранённая системой сеть ГУ несёт INTERNET и без интернета, поэтому смотрим VALIDATED.
     */
    private fun onLan(caps: NetworkCapabilities, lp: LinkProperties, lan: Lan): Boolean {
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        if (lp.linkAddresses.any { a ->
                val ip = (a.address as? Inet4Address)?.hostAddress?.let(CarNetPolicy::ipv4) ?: return@any false
                CarNetPolicy.sameCarLan(ip, a.prefixLength, lan.addr, lan.prefix)
            }
        ) return true
        val gw = CarNetPolicy.ipv4(gateway(lp))
        return lan.gw != null && gw == lan.gw
    }

    /** Держим уже ассоциированную сеть: без specifier и без требования интернета. */
    private fun hold(p: Params, lan: Lan) {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val h = Hold(p, lan)
        watch = h
        state = CarNetState.REQUESTING
        WLog.ev(NET, "car-hold", "prefix" to lan.prefix, "timeoutMs" to HOLD_MS)
        try {
            cm.requestNetwork(req, h, handler, HOLD_MS)
            h.registered = true
        } catch (e: Exception) {
            watch = null
            WLog.ev(NET, "car-hold-fail", "err" to e.javaClass.simpleName)
            specifier(p)
            return
        }
        // Таймаут requestNetwork не сработает, если сеть уже подошла под запрос, а DHCP так и не дал IPv4.
        handler.postDelayed({ if (!h.ready) missHold(h) }, HOLD_MS.toLong())
    }

    /** Сеть из запроса не та или DHCP не успел: обычный specifier. */
    private fun missHold(h: Hold) {
        if (watch !== h || h.handed) return
        h.handed = true
        h.unregister()
        if (watch === h) watch = null
        current = null
        ifaceName = null
        WLog.ev(NET, "car-hold-miss")
        specifier(h.p)
    }

    private fun specifier(p: Params) {
        val first = CarSecPolicy.first(rememberedSec(p.ssid), p.pass.isNotEmpty())
        start(p, Sec.entries.first { it.label == first })
    }

    private fun start(p: Params, sec: Sec) {
        val req = try {
            val spec = WifiNetworkSpecifier.Builder().setSsid(p.ssid).apply {
                p.bssid?.let { setBssid(MacAddress.fromString(it)) }
                when (sec) {
                    Sec.OPEN -> {}
                    Sec.WPA2 -> setWpa2Passphrase(p.pass)
                    Sec.WPA3 -> setWpa3Passphrase(p.pass)
                }
            }.build()
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
        } catch (e: Exception) {      // длина SSID, короткий пароль: message может содержать SSID, в лог только класс
            watch = null
            fail(p, sec, e.javaClass.simpleName)
            return
        }
        val c = Cb(p, sec)
        watch = c
        state = CarNetState.REQUESTING
        WLog.ev(NET, "car-request", "sec" to sec.label, "timeoutMs" to p.timeoutMs)
        try {
            cm.requestNetwork(req, c, handler, p.timeoutMs.toInt())
            c.registered = true
        } catch (e: Exception) {
            watch = null
            fail(p, sec, e.javaClass.simpleName)
        }
    }

    private fun fail(p: Params, sec: Sec, err: String) {
        WLog.ev(NET, "car-unavailable", "sec" to sec.label, "err" to err)
        state = CarNetState.UNAVAILABLE
        p.onUnavailable("error:$err")
    }

    private interface Watch {
        fun unregister()
    }

    private inner class Cb(val p: Params, val sec: Sec) : ConnectivityManager.NetworkCallback(), Watch {
        var registered = false
        var net: Network? = null
        var ready = false

        /** Колбэк прошлой попытки или уже снятый запрос. */
        private fun stale() = watch !== this

        override fun unregister() {
            if (!registered) return
            registered = false
            try {
                cm.unregisterNetworkCallback(this)
            } catch (_: IllegalArgumentException) {      // таймаут: система регистрацию уже сняла
            }
        }

        override fun onAvailable(network: Network) {
            if (stale()) return
            net = network
        }

        /** Имя интерфейса берём отсюда: в onAvailable LinkProperties ещё нет. */
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (stale() || ready || network != net) return
            val name = linkProperties.interfaceName ?: return
            ready = true
            current = network
            ifaceName = name
            state = CarNetState.AVAILABLE
            // Только сохранённая сеть машины: сеть ICCOA (ssid из BLE) её подсеть и защиту затирать не должна.
            if (CarWifi(app).ssid == p.ssid) {
                CarWifi(app).rememberSec(sec.label)
                rememberLan(linkProperties)
            }
            WLog.ev(NET, "car-available", "iface" to name, "sec" to sec.label, "addr" to v4(linkProperties))
            p.onAvailable(network)
        }

        override fun onUnavailable() {
            if (stale()) return
            unregister()
            WLog.ev(NET, "car-unavailable", "sec" to sec.label)
            if (CarSecPolicy.retryWpa3(rememberedSec(p.ssid), sec.label, p.pass.isNotEmpty())) {
                start(p, Sec.WPA3)      // сеть могла быть WPA3: одна повторная попытка
                return
            }
            watch = null
            state = CarNetState.UNAVAILABLE
            p.onUnavailable("no-network")
        }

        override fun onLost(network: Network) {
            if (stale() || network != net) return
            unregister()
            watch = null
            current = null
            ifaceName = null
            state = CarNetState.IDLE
            WLog.ev(NET, "car-lost")
            p.onLost()
        }
    }

    /**
     * Удержание сети без specifier. Колбэк системы может прийти прямо из requestNetwork, поэтому [Params.onAvailable]
     * уходит через [handler]: иначе BridgeService.restartForCarNet зайдёт в request повторно.
     */
    private inner class Hold(val p: Params, val lan: Lan) : ConnectivityManager.NetworkCallback(), Watch {
        var registered = false
        var handed = false
        private var net: Network? = null
        var ready = false
            private set

        private fun stale() = watch !== this || handed

        override fun unregister() {
            if (!registered) return
            registered = false
            try {
                cm.unregisterNetworkCallback(this)
            } catch (_: IllegalArgumentException) {
            }
        }

        override fun onAvailable(network: Network) {
            if (stale()) return
            net = network
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (stale()) return
            net = network
            val lp = cm.getLinkProperties(network) ?: return
            accept(network, caps, lp)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (stale()) return
            net = network
            val caps = cm.getNetworkCapabilities(network) ?: return
            accept(network, caps, linkProperties)
        }

        private fun accept(network: Network, caps: NetworkCapabilities, lp: LinkProperties) {
            if (ready || network != net) return
            val name = lp.interfaceName ?: return
            if (lp.linkAddresses.none { it.address is Inet4Address }) return
            if (!onLan(caps, lp, lan)) {
                missHold(this)
                return
            }
            ready = true
            current = network
            ifaceName = name
            state = CarNetState.AVAILABLE
            rememberLan(lp)
            val addr = v4(lp)
            WLog.ev(NET, "car-available", "iface" to name, "sec" to "saved", "addr" to addr, "held" to "lan")
            val held = network
            handler.post { if (watch === this && current == held) p.onAvailable(held) }
        }

        override fun onUnavailable() {
            if (stale()) return
            missHold(this)
        }

        override fun onLost(network: Network) {
            if (stale() || network != net) return
            if (!ready) {
                missHold(this)
                return
            }
            unregister()
            watch = null
            current = null
            ifaceName = null
            state = CarNetState.IDLE
            WLog.ev(NET, "car-lost")
            p.onLost()
        }
    }

    private fun rememberLan(lp: LinkProperties) {
        val addr = v4(lp) ?: return
        val prefix = lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.prefixLength ?: return
        CarWifi(app).rememberLan(addr, prefix, gateway(lp))
    }

    private fun v4(lp: LinkProperties): String? =
        lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress

    /**
     * Адрес телефона и шлюз в сети [current] (ICCOA: шлюз и есть машина). Сразу после onAvailable DHCP мог ещё не
     * закончиться: тогда null, вызывающий повторяет. Шлюз — первый IPv4 из маршрутов; его нет — адрес DHCP-сервера.
     */
    fun addresses(): Pair<String?, String?> {
        val lp = current?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() } ?: return null to null
        val routed = lp.routes.firstOrNull { it.hasGateway() && it.gateway is Inet4Address }?.gateway?.hostAddress
        return v4(lp) to (routed ?: gateway(lp))
    }

    private fun gateway(lp: LinkProperties): String? {
        if (Build.VERSION.SDK_INT >= 30) {
            val dhcp = lp.dhcpServerAddress
            if (dhcp is Inet4Address) return dhcp.hostAddress
        }
        return lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    }

    private fun logStaConcurrency() {
        if (staLogged) return
        staLogged = true
        val v = if (Build.VERSION.SDK_INT >= 31) {
            try {
                app.getSystemService(WifiManager::class.java).isStaConcurrencyForLocalOnlyConnectionsSupported.toString()
            } catch (e: Exception) {
                e.javaClass.simpleName
            }
        } else "n/a"
        WLog.ev(NET, "sta-concurrency", "local" to v)
    }

    private class Lan(val addr: Int, val prefix: Int, val gw: Int?)

    private enum class Seen { MATCH, PENDING, OTHER }

    private companion object {
        const val NET = "net"
        const val HOLD_MS = 4_000
        @Volatile var staLogged = false
    }
}
