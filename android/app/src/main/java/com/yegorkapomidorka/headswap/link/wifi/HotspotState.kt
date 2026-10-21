package com.yegorkapomidorka.headswap.link.wifi

import java.net.Inet4Address
import java.net.NetworkInterface

/** Точка доступа телефона поднята: swlan* у Samsung, ap* у остальных, с IPv4. Тумблер в настройках не смотрим. */
object HotspotState {
    private fun hotspotName(name: String) = name.startsWith("swlan") || name.startsWith("ap")

    private fun interfaces(): List<NetworkInterface> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    /** Имена поднятых интерфейсов точки доступа (с IPv4). */
    fun upNames(): List<String> = interfaces().filter { ni ->
        try {
            hotspotName(ni.name) && ni.isUp && ni.inetAddresses.toList().any { it is Inet4Address }
        } catch (_: Exception) {
            false
        }
    }.map { it.name }

    fun up(): Boolean = upNames().isNotEmpty()

    /**
     * Все поднятые интерфейсы с IPv4 (имя адрес/префикс), кроме loopback: для лога зонда. На незнакомых прошивках
     * (HyperOS) точка может называться не swlan/ap, а wlan1 и т.п.
     */
    fun allUp(): String = interfaces().mapNotNull { ni ->
        try {
            if (!ni.isUp || ni.isLoopback) return@mapNotNull null
            val v4 = ni.interfaceAddresses.filter { it.address is Inet4Address }
            if (v4.isEmpty()) null else "${ni.name} " + v4.joinToString(",") { "${it.address.hostAddress}/${it.networkPrefixLength}" }
        } catch (_: Exception) {
            null
        }
    }.joinToString("; ").ifEmpty { "нет" }
}
