package com.yegorkapomidorka.headswap.link.box

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.yegorkapomidorka.headswap.link.diag.WLog
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Режим бокса: телефон играет роль беспроводного Android Auto перед боксом smartBox (AK3918).
 *
 * 1) RFCOMM-клиент к боксу ([BoxHandshake.SERVICE_UUID]), прогон [BoxHandshake] → бокс уходит в AA-режим,
 *    отдаёт Wi-Fi (ssid/psk) и адрес сервера данных (ip:6733).
 * 2) Подключение к Wi-Fi бокса ([WifiNetworkSpecifier], Android 10+), привязка к этой сети.
 * 3) TCP на ip:port поверх сети бокса → сокет отдаётся в обычную сессию (бокс прозрачен нашим патчем
 *    libSdAuto, поток — CarLife как есть, тот же StreamTransport(USB_TCP), что у свистка).
 *
 * Запускать на фоновой нити. Bluetooth-устройство бокса должно быть сопряжено.
 */
class BoxConnector(
    private val ctx: Context,
    /** Фильтр имени сопряжённого BT-устройства бокса. */
    private val btNameMatch: (String?) -> Boolean = { it != null && it.contains("Dongle", true) },
) {
    @Volatile private var cancelled = false
    private var cm: ConnectivityManager? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    fun cancel() { cancelled = true }

    /** Выполнить всё и вернуть подключённый сокет к серверу данных бокса. Бросает при любой неудаче. */
    @SuppressLint("MissingPermission")
    fun connect(): Socket {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            ?: error("Bluetooth недоступен")
        val dev = adapter.bondedDevices?.firstOrNull { btNameMatch(it.name) }
            ?: error("бокс не найден среди сопряжённых BT (включи и спарь)")
        WLog.ev(WLog.BT, "box-bt", "dev" to WLog.maskMac(dev.address), "name" to dev.name)

        val rfcomm: BluetoothSocket = dev.createRfcommSocketToServiceRecord(UUID.fromString(BoxHandshake.SERVICE_UUID))
        val outcome: BoxHandshake.Outcome
        try {
            adapter.cancelDiscovery()
            rfcomm.connect()
            outcome = BoxHandshake.run(rfcomm.inputStream, rfcomm.outputStream, randomId())
            WLog.ev(WLog.BT, "box-handshake", "ip" to outcome.wifi.ip, "port" to outcome.wifi.port, "ssid" to outcome.creds.ssid)
        } finally {
            try { rfcomm.close() } catch (_: Exception) {}   // рукопожатие сделано, RFCOMM больше не нужен
        }
        if (cancelled) error("отменено")

        val net = joinWifi(outcome.creds.ssid, outcome.creds.psk)
        val sock = net.socketFactory.createSocket()
        try {
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(outcome.wifi.ip, outcome.wifi.port), CONNECT_MS)
            WLog.ev(WLog.TCP, "box-connected", "ip" to outcome.wifi.ip, "port" to outcome.wifi.port)
            return sock
        } catch (e: Exception) {
            try { sock.close() } catch (_: Exception) {}
            throw e
        }
    }

    /** Подключиться к Wi-Fi бокса через WifiNetworkSpecifier; вернуть привязанную Network. */
    private fun joinWifi(ssid: String, psk: String): Network {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "режим бокса: нужен Android 10+" }
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(psk).build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)  // AP бокса без интернета
            .setNetworkSpecifier(spec).build()
        val c = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm = c
        val latch = CountDownLatch(1)
        var result: Network? = null  // публикуется через latch (await/countDown — барьер)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { result = network; latch.countDown() }
            override fun onUnavailable() { latch.countDown() }
        }
        netCb = cb
        c.requestNetwork(req, cb)
        val net = if (latch.await(WIFI_JOIN_S, TimeUnit.SECONDS)) result else null
        if (net == null) {
            release()
            error("Wi-Fi бокса '$ssid' не подключился за ${WIFI_JOIN_S}с")
        }
        WLog.ev(WLog.P2P, "box-wifi", "ssid" to ssid)
        return net
    }

    /** Отпустить сеть бокса (после закрытия сессии). */
    fun release() {
        netCb?.let { try { cm?.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCb = null
    }

    private fun randomId(): String =
        (1..8).map { "0123456789abcdef"[(Math.random() * 16).toInt()] }.joinToString("")

    companion object {
        private const val CONNECT_MS = 4000
        private const val WIFI_JOIN_S = 25L
    }
}
