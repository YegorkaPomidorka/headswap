package com.yegorkapomidorka.headswap.link.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Точка доступа телефона поднята. Первичный источник - липкая рассылка WIFI_AP_STATE_CHANGED (имя интерфейса от
 * прошивки не зависит), вторичный - сетевые интерфейсы с IPv4 (swlan* у Samsung, ap*, wlan1+ у HyperOS).
 * Тумблер в настройках не смотрим.
 */
object HotspotState {
    private const val AP_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val AP_EXTRA = "wifi_state"

    const val AP_DISABLING = 10
    const val AP_DISABLED = 11
    const val AP_ENABLING = 12
    const val AP_ENABLED = 13
    const val AP_FAILED = 14

    /** Имя интерфейса похоже на точку доступа: swlan*, ap*, wlan1+ (wlan0 - клиентский Wi-Fi; p2p*, rmnet*, tun* - не точка). */
    fun isHotspotIface(name: String): Boolean {
        if (name.startsWith("p2p") || name.startsWith("rmnet") || name.startsWith("tun")) return false
        if (name.startsWith("swlan") || name.startsWith("ap")) return true
        return name.length > 4 && name.startsWith("wlan") && name[4] in '1'..'9'
    }

    /**
     * Итог по двум источникам; чистая функция. apState известен: 13 = поднята, всё остальное (11 выкл, 10/12 переходные,
     * 14 сбой) = «ещё не»; неизвестен (null или чужое число) - по наличию интерфейса точки.
     */
    fun decide(apState: Int?, hotspotIfaces: List<String>): Boolean = when (apState) {
        AP_ENABLED -> true
        AP_DISABLED, AP_ENABLING, AP_DISABLING, AP_FAILED -> false
        else -> hotspotIfaces.isNotEmpty()
    }

    /** Состояние из липкой рассылки; null = недоступно (рассылки нет или нет extra). */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun apState(ctx: Context): Int? {
        val filter = IntentFilter(AP_ACTION)
        val intent: Intent? = try {
            ctx.registerReceiver(null, filter)
        } catch (_: Exception) {
            // targetSdk 34+ для приёмника без флага может бросить SecurityException: пробуем с флагом
            try {
                if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED) else null
            } catch (_: Exception) {
                null
            }
        }
        if (intent == null || !intent.hasExtra(AP_EXTRA)) return null
        return intent.getIntExtra(AP_EXTRA, -1).takeIf { it != -1 }
    }

    private fun interfaces(): List<NetworkInterface> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    /** Имена поднятых интерфейсов точки доступа (с IPv4). */
    fun upNames(): List<String> = interfaces().filter { ni ->
        try {
            isHotspotIface(ni.name) && ni.isUp && ni.inetAddresses.toList().any { it is Inet4Address }
        } catch (_: Exception) {
            false
        }
    }.map { it.name }

    fun up(ctx: Context): Boolean = decide(apState(ctx), upNames())

    /**
     * Для лога: состояние рассылки и все поднятые интерфейсы с IPv4 (имя адрес/префикс), кроме loopback.
     * На незнакомых прошивках точка может называться как угодно.
     */
    fun snapshot(ctx: Context): String {
        val ifaces = interfaces().mapNotNull { ni ->
            try {
                if (!ni.isUp || ni.isLoopback) return@mapNotNull null
                val v4 = ni.interfaceAddresses.filter { it.address is Inet4Address }
                if (v4.isEmpty()) null else "${ni.name} " + v4.joinToString(",") { "${it.address.hostAddress}/${it.networkPrefixLength}" }
            } catch (_: Exception) {
                null
            }
        }.joinToString("; ").ifEmpty { "нет" }
        return "ap_state=${apState(ctx) ?: "?"} ifaces=$ifaces"
    }
}
