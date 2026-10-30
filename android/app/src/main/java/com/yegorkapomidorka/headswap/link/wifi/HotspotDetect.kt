package com.yegorkapomidorka.headswap.link.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Включена ли точка доступа телефона. Имя интерфейса само по себе не ответ: у Samsung swlan0, у большинства ap0,
 * а у Xiaomi/Qualcomm хотспот сидит на wlan1 (h420), и тот же wlan1 бывает вторым STA (сеть машины, WifiNetworkSpecifier).
 * Порядок: скрытый API WifiManager; если не вышло - имена интерфейсов, где wlan[1-9] берём только когда он точно не STA.
 */
object HotspotDetect {
    private const val WIFI_AP_STATE_ENABLED = 13

    /** Хотспотные имена без оговорок. */
    fun apName(name: String): Boolean = name.startsWith("swlan") || name.startsWith("ap")

    /**
     * Интерфейс похож на хотспот. [sta] - имена интерфейсов, на которых телефон сам клиент Wi-Fi (известны из
     * ConnectivityManager); null - узнать не вышло, тогда wlan[1-9] не трогаем: это может быть второй STA.
     */
    fun apIface(name: String, sta: Set<String>?): Boolean {
        if (apName(name)) return true
        return sta != null && name !in sta && SECOND_WLAN.matches(name)
    }

    private val SECOND_WLAN = Regex("wlan[1-9]\\d*")

    fun isUp(ctx: Context): Boolean = apEnabledByApi(ctx) ?: ifacesSayUp(staIfaces(ctx))

    /** Скрытые isWifiApEnabled()/getWifiApState(): null, если система их не отдала (hidden API, SecurityException). */
    private fun apEnabledByApi(ctx: Context): Boolean? {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        try {
            return wm.javaClass.getMethod("isWifiApEnabled").invoke(wm) as? Boolean
        } catch (_: Throwable) {
        }
        try {
            val st = wm.javaClass.getMethod("getWifiApState").invoke(wm) as? Int ?: return null
            return st == WIFI_AP_STATE_ENABLED
        } catch (_: Throwable) {
            return null
        }
    }

    /** Интерфейсы Wi-Fi-сетей, известных ConnectivityManager (хотспот телефона среди них не бывает). null - не вышло. */
    @Suppress("DEPRECATION")
    private fun staIfaces(ctx: Context): Set<String>? = try {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks
            .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .mapNotNull { cm.getLinkProperties(it)?.interfaceName }
            .toSet()
    } catch (_: Throwable) {
        null
    }

    private fun ifacesSayUp(sta: Set<String>?): Boolean = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { ni ->
            apIface(ni.name, sta) && ni.isUp && ni.inetAddresses.toList().any { it is Inet4Address }
        }
    } catch (_: Throwable) {
        false
    }
}
