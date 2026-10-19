package com.yegorkapomidorka.headswap.link.car

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yegorkapomidorka.headswap.link.diag.WLog

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
 * SSID и пароль в WL не попадают, из исключений в лог идёт только имя класса.
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

    private var cb: Cb? = null

    private enum class Sec(val label: String) { OPEN("open"), WPA2("wpa2"), WPA3("wpa3") }

    private class Params(
        val ssid: String,
        val pass: String,
        val timeoutMs: Long,
        val onAvailable: (Network) -> Unit,
        val onUnavailable: (String) -> Unit,
        val onLost: () -> Unit,
    )

    /**
     * Запросить сеть. Уже просим или получили - ничего не делаем. [onAvailable] зовётся, когда известны и сеть, и имя
     * интерфейса; [onUnavailable] - сеть не нашлась, отказ в диалоге или ошибка запроса (reason - короткий код).
     */
    fun request(
        ssid: String,
        pass: String,
        timeoutMs: Long,
        onAvailable: (Network) -> Unit,
        onUnavailable: (String) -> Unit,
        onLost: () -> Unit,
    ) {
        if (state == CarNetState.REQUESTING || state == CarNetState.AVAILABLE) return
        logStaConcurrency()
        val first = CarSecPolicy.first(rememberedSec(ssid), pass.isNotEmpty())
        start(Params(ssid, pass, timeoutMs, onAvailable, onUnavailable, onLost), Sec.entries.first { it.label == first })
    }

    /** Удачная защита из [CarWifi], если просим именно сохранённую сеть машины. */
    private fun rememberedSec(ssid: String): String? = CarWifi(app).let { if (it.ssid == ssid) it.sec else null }

    /** Снять запрос (и сеть, если её держал только он). Можно звать сколько угодно раз. */
    fun release(why: String) {
        val c = cb
        cb = null
        if (c != null) {
            c.unregister()
            WLog.ev(NET, "car-release", "why" to why)
        }
        current = null
        ifaceName = null
        if (state != CarNetState.IDLE) state = CarNetState.IDLE
    }

    private fun start(p: Params, sec: Sec) {
        val req = try {
            val spec = WifiNetworkSpecifier.Builder().setSsid(p.ssid).apply {
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
            cb = null
            fail(p, sec, e.javaClass.simpleName)
            return
        }
        val c = Cb(p, sec)
        cb = c
        state = CarNetState.REQUESTING
        WLog.ev(NET, "car-request", "sec" to sec.label, "timeoutMs" to p.timeoutMs)
        try {
            cm.requestNetwork(req, c, handler, p.timeoutMs.toInt())
            c.registered = true
        } catch (e: Exception) {
            cb = null
            fail(p, sec, e.javaClass.simpleName)
        }
    }

    private fun fail(p: Params, sec: Sec, err: String) {
        WLog.ev(NET, "car-unavailable", "sec" to sec.label, "err" to err)
        state = CarNetState.UNAVAILABLE
        p.onUnavailable("error:$err")
    }

    private inner class Cb(val p: Params, val sec: Sec) : ConnectivityManager.NetworkCallback() {
        var registered = false
        private var net: Network? = null
        private var ready = false

        /** Колбэк прошлой попытки или уже снятый запрос. */
        private fun stale() = cb !== this

        fun unregister() {
            if (!registered) return
            registered = false
            try {
                cm.unregisterNetworkCallback(this)
            } catch (_: IllegalArgumentException) {      // таймаут: система регистрацию уже сняла
            }
        }

        override fun onAvailable(network: Network) {
            if (!stale()) net = network
        }

        /** Имя интерфейса берём отсюда: в onAvailable LinkProperties ещё нет. */
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (stale() || ready || network != net) return
            val name = linkProperties.interfaceName ?: return
            ready = true
            current = network
            ifaceName = name
            state = CarNetState.AVAILABLE
            CarWifi(app).let { if (it.ssid == p.ssid) it.rememberSec(sec.label) }
            WLog.ev(NET, "car-available", "iface" to name, "sec" to sec.label,
                "addr" to linkProperties.linkAddresses.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress)
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
            cb = null
            state = CarNetState.UNAVAILABLE
            p.onUnavailable("no-network")
        }

        override fun onLost(network: Network) {
            if (stale() || network != net) return
            unregister()
            cb = null
            current = null
            ifaceName = null
            state = CarNetState.IDLE
            WLog.ev(NET, "car-lost")
            p.onLost()
        }
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

    private companion object {
        const val NET = "net"
        @Volatile var staLogged = false
    }
}
