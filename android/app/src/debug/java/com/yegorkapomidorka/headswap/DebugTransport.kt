package com.yegorkapomidorka.headswap

import android.content.Intent
import android.os.ParcelFileDescriptor
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Debug-транспорт: CarLife-поток по TCP к эмулятору ГУ на ПК вместо USB-аксессуара.
 * На ПК: `adb reverse tcp:7240 tcp:7240`, `python pc-headunit/carlife_hu.py`; на телефоне:
 * `adb shell am start-foreground-service -n com.yegorkapomidorka.headswap/.BridgeService --ei debug_tcp 7240`.
 */
object DebugTransport {
    const val EXTRA_PORT = "debug_tcp"

    const val EXTRA_TCP6 = "debug_tcp6"

    /**
     * Беспроводной формат (6 сокетов, без внешнего заголовка): телефон слушает 7240..9340 на 127.0.0.1, ГУ на ПК
     * подключается через `adb forward tcp:P tcp:P`; `--ez debug_tcp6 true`, на ПК `carlife_hu.py --transport tcp6`.
     */
    fun tcp6(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_TCP6, false) == true

    /** Ветка B беспровода: хотспот телефона, маяк 7999, 6 портов на 0.0.0.0 (`--ez debug_wifi_b true`). */
    fun wifiB(intent: Intent?): Boolean = intent?.getBooleanExtra("debug_wifi_b", false) == true

    /** Адрес bind для tcp6: по умолчанию 127.0.0.1; `--es debug_tcp6_bind 0.0.0.0` для проверки входящего TCP из LAN. */
    fun tcp6Bind(intent: Intent?): String = intent?.getStringExtra("debug_tcp6_bind") ?: "127.0.0.1"

    /**
     * ICCOA без BLE и Wi-Fi: машина = `pc-headunit/iccoa_hu.py` (`--es debug_iccoa 127.0.0.1 [--es debug_iccoa_pin 123456]`).
     * На ПК: `adb reverse` 57209 и 15550, `adb forward` 57219, 57229, 57239, 7236, 4321. Пара (адрес машины, PIN).
     */
    fun iccoa(intent: Intent?): Pair<String, String?>? =
        intent?.getStringExtra("debug_iccoa")?.let { it to intent.getStringExtra("debug_iccoa_pin") }

    fun port(intent: Intent?): Int? =
        intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { it in 1..65535 }

    /** Блокирующее подключение, не с main-потока. Сокет dup-ается в PFD, сам он больше не нужен. */
    fun connect(port: Int): ParcelFileDescriptor? = try {
        Socket().use { s ->
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", port), 5000)
            ParcelFileDescriptor.fromSocket(s)
        }
    } catch (e: Exception) {
        AppLog.e("debug TCP 127.0.0.1:$port", e)
        null
    }
}
