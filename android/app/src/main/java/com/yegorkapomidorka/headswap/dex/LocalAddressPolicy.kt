// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import java.net.InetAddress
import java.net.Inet6Address
import java.net.NetworkInterface

/** Parse literal IP addresses before InetAddress: user input must never trigger DNS. */
internal object LocalAddressPolicy {
    /** Activation talks to the phone's own adbd through loopback: it needs no local-network permission. */
    const val LOOPBACK = "127.0.0.1"

    fun normalizeOrNull(raw: String): String? {
        val host = raw.trim().removeSurrounding("[", "]")
        if (host.isEmpty() || host.length > 128) return null
        if (':' !in host) {
            val pieces = host.split('.')
            if (pieces.size != 4 || pieces.any {
                    it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } ||
                        (it.length > 1 && it.startsWith('0')) || (it.toIntOrNull() ?: 256) > 255
                }) return null
        } else if (!host.matches(Regex("[0-9a-fA-F:.]+(%[A-Za-z0-9_.-]+)?"))) return null
        return runCatching {
            val address = InetAddress.getByName(host)
            if (address.isAnyLocalAddress || address.isMulticastAddress) null else address.hostAddress
        }.getOrNull()
    }

    fun accepts(host: String, ownAddresses: Collection<String>): Boolean {
        val candidate = normalizeOrNull(host) ?: return false
        val address = runCatching { InetAddress.getByName(candidate) }.getOrNull() ?: return false
        val bytes = address.address
        if (bytes.contentEquals(byteArrayOf(127, 0, 0, 1)) ||
            bytes.contentEquals(ByteArray(16).also { it[15] = 1 })) return true
        return ownAddresses.any { own ->
            val normalized = normalizeOrNull(own) ?: return@any false
            val ownAddress = runCatching { InetAddress.getByName(normalized) }.getOrNull() ?: return@any false
            val matchingScope = address !is Inet6Address || ownAddress !is Inet6Address ||
                ((!address.isLinkLocalAddress && address.scopeId == 0) || address.scopeId == ownAddress.scopeId)
            matchingScope && ownAddress.address.contentEquals(bytes)
        }
    }

    fun ownAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())
}
