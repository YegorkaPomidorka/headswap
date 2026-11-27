// Derived from FT (github.com/OneAboveAll1964/ft-app, Apache-2.0): IccoaEvidence.kt, IccoaClientProfile.kt,
// IccoaSession.kt (receiveBootstrap). Код свой; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import org.json.JSONObject
import java.util.Locale
import java.util.Random

/** Отказ подъёма по воздуху: только короткий код. Текст JSON сюда не попадает: в SERVER_INFO лежит пароль Wi-Fi. */
class IccoaBleError(val code: String) : Exception(code)

/** Wi-Fi машины из SERVER_INFO. */
class IccoaWifiInfo(val ssid: String, val psk: String, val bssid: String?) {
    /** Имя сети, пароль и BSSID в лог попадать не должны. */
    override fun toString() = "IccoaWifiInfo(bssid=${if (bssid != null) "yes" else "no"})"
}

/** Чистая часть подъёма ICCOA по BLE (Э4, ADR 0066): кого в эфире считать машиной, CLIENT_INFO, адреса для лога. */
object IccoaBleInfo {
    const val SERVICE = "2abcc850-9935-4f8a-ba84-123456789100"
    const val CLIENT_INFO = "2abcc850-9935-4f8a-ba84-123456789101"
    const val SERVER_INFO = "2abcc850-9935-4f8a-ba84-123456789102"
    private const val ADVERTISED = "0000fcfb-0000-1000-8000-00805f9b34fb"
    /** serviceData с этим ключом у одной машины постоянны при сменных BLE-адресах (наблюдение FT на XA60). Не идентичность. */
    const val TOKEN = "00000002-0000-1000-8000-00805f9b34fb"
    const val MAX_CLIENT_INFO = 512
    private val PIN = Regex("[0-9]{6}")
    internal val MAC = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")

    /** Похоже на машину с ICCOA: [uuids] — serviceUuids и ключи serviceData рекламы. */
    fun hint(name: String?, uuids: Collection<String>): Boolean =
        uuids.any { it.equals(SERVICE, ignoreCase = true) || it.equals(ADVERTISED, ignoreCase = true) } ||
            name != null && (name.contains("iccoa", ignoreCase = true) || name.contains("carlink", ignoreCase = true))

    /** Receiver token в hex; ключи [serviceData] — uuid в нижнем регистре. null — нет, не 16 байт или одни нули. */
    fun token(serviceData: Map<String, ByteArray>): String? {
        val data = serviceData[TOKEN] ?: return null
        if (data.size != 16 || data.all { it == 0.toByte() }) return null
        return data.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }

    fun validPin(pin: String) = PIN.matches(pin)

    /**
     * CLIENT_INFO для записи в GATT. [pin] == null — быстрый вход: тот же JSON без pinCodeOrAuthentication.
     * Вызывающий зануляет массив после записи.
     */
    fun clientInfo(id: String, model: String, mac: String, channel: Int, pin: String?): ByteArray {
        require(pin == null || validPin(pin)) { "pin" }
        val json = JSONObject().put("id", id).put("model", model).put("name", "Headswap")
        if (pin != null) json.put("pinCodeOrAuthentication", pin)
        // type 1002 — телефон идёт клиентом в точку доступа машины; manufactures — как у FT, от марки телефона не зависит.
        json.put("band", 1).put("mac", mac).put("type", 1002).put("manufactures", "Xiaomi").put("channel", channel)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_CLIENT_INFO) {
            bytes.fill(0)
            throw IllegalArgumentException("size")
        }
        return bytes
    }

    /** Синтетический MAC для CLIENT_INFO: locally administered, unicast. */
    fun newMac(random: Random): String {
        val bytes = ByteArray(6).also(random::nextBytes)
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 2).toByte()
        return bytes.joinToString(":") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }

    fun validMac(mac: String) = MAC.matches(mac) && (mac.take(2).toInt(16) and 3) == 2

    /** Канал Wi-Fi по частоте (МГц); неизвестная частота — 1. */
    fun wifiChannel(frequency: Int): Int = when {
        frequency in 2412..2472 && (frequency - 2412) % 5 == 0 -> (frequency - 2412) / 5 + 1
        frequency == 2484 -> 14
        frequency in 5180..5825 && (frequency - 5180) % 5 == 0 -> (frequency - 5180) / 5 + 36
        else -> 1
    }

    /** Адрес для лога: только последний октет, `x.x.x.23`. */
    fun mask(ip: String?): String? = ip?.let { "x.x.x." + it.substringAfterLast('.') }
}

/**
 * Сборщик SERVER_INFO из notify-фрагментов: фрагмент с `{` в начале начинает сообщение заново, `}` в конце фрагмента
 * его завершает. Одна нить. Буфер зануляется; строки ssid/psk живут только в [IccoaWifiInfo].
 */
class IccoaServerInfo {
    private val buffer = ByteArray(MAX)
    private var size = 0

    /** Размер и поле type последнего собранного сообщения — для лога. */
    var bytes = 0
        private set
    var type: Int? = null
        private set

    /** null — сообщение ещё не целое. Отказ — [IccoaBleError]: код без текста JSON. */
    fun add(fragment: ByteArray): IccoaWifiInfo? {
        if (fragment.firstOrNull() == '{'.code.toByte()) reset()
        if (size + fragment.size > MAX) {
            reset()
            throw IccoaBleError("server-info-too-big")
        }
        fragment.copyInto(buffer, size)
        size += fragment.size
        val last = fragment.lastOrNull { it.toInt() !in WHITESPACE }
        if (last != '}'.code.toByte()) return null
        bytes = size
        type = null
        val text = String(buffer, 0, size, Charsets.UTF_8)
        reset()
        // Текст JSONException цитирует сообщение, а в нём пароль: наружу только код.
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IccoaBleError("server-info-json")
        }
        // 1001 — Wi-Fi Direct (его же машина имеет в виду без type), 1002 — точка доступа машины.
        val kind = json.optInt("type", 1001)
        type = kind
        if (kind != 1002) throw IccoaBleError("p2p-unsupported")
        val ssid = json.optString("ssid")
        val psk = json.optString("psk")
        if (ssid.isEmpty() || psk.isEmpty()) throw IccoaBleError("server-info-fields")
        val mac = json.optString("mac").lowercase(Locale.ROOT)
        return IccoaWifiInfo(ssid, psk, mac.takeIf(IccoaBleInfo.MAC::matches))
    }

    fun reset() {
        buffer.fill(0, 0, size)
        size = 0
    }

    private companion object {
        const val MAX = 4096
        val WHITESPACE = 0..32
    }
}
