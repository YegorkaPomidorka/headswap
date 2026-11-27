package com.yegorkapomidorka.headswap.link.box

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Рукопожатие беспроводного Android Auto с боксом smartBox (AK3918), чтобы он ушёл в AA-режим и поднял
 * TCP-сервер данных. Расшифровано из живого захвата — см. smartbox/firmware/aa-wireless-handshake.md.
 *
 * RFCOMM-клиент к боксу (UUID [SERVICE_UUID]). Кадр: [uint16 len BE][uint16 type BE][protobuf payload].
 * Поля боксовой нумерации (не Google): 4 VersionReq, 5 StartReq, 1 StartResp(ip,port), 2 InfoReq,
 * 3 InfoResp(ssid,psk,bssid,sec), 6/7 статус.
 *
 * Чистая логика (работает со стримами) — тестируется без Bluetooth.
 */
object BoxHandshake {
    const val SERVICE_UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66"  // стандартный AA-wireless

    const val T_START_RESPONSE = 1
    const val T_INFO_REQUEST = 2
    const val T_INFO_RESPONSE = 3
    const val T_VERSION_REQUEST = 4
    const val T_START_REQUEST = 5
    const val T_STATUS6 = 6
    const val T_STATUS7 = 7

    /** Куда коннектиться после рукопожатия (из StartResponse бокса). */
    data class Wifi(val ip: String, val port: Int)

    /** Учётка Wi-Fi бокса (из InfoResponse). */
    data class Creds(val ssid: String, val psk: String, val bssid: String, val security: Int)

    /** Итог рукопожатия: куда и по какому Wi-Fi идти. */
    data class Outcome(val wifi: Wifi, val creds: Creds)

    // --- кадры ---

    fun frame(type: Int, payload: ByteArray): ByteArray {
        val b = ByteArray(4 + payload.size)
        b[0] = (payload.size ushr 8).toByte(); b[1] = payload.size.toByte()
        b[2] = (type ushr 8).toByte();         b[3] = type.toByte()
        payload.copyInto(b, 4)
        return b
    }

    /** Прочитать один кадр. null — EOF. */
    fun readFrame(input: DataInputStream): Pair<Int, ByteArray>? {
        val len = try { input.readUnsignedShort() } catch (e: Exception) { return null }
        val type = input.readUnsignedShort()
        val payload = ByteArray(len)
        input.readFully(payload)
        return type to payload
    }

    // --- исходящие сообщения (байты сверены с захватом Google AA) ---

    /** type5: f1=0, f2=0, f3=id(строка), f4=0. id — случайный токен телефона. */
    fun startRequest(id: String): ByteArray {
        val idb = id.toByteArray(Charsets.US_ASCII)
        val p = ByteArray(6 + idb.size)
        p[0] = 0x08; p[1] = 0x00; p[2] = 0x10; p[3] = 0x00
        p[4] = 0x1A; p[5] = idb.size.toByte(); idb.copyInto(p, 6)
        // хвост f4=0: 20 00
        return frame(T_START_REQUEST, p + byteArrayOf(0x20, 0x00))
    }

    fun infoRequest(): ByteArray = frame(T_INFO_REQUEST, ByteArray(0))
    fun status7(): ByteArray = frame(T_STATUS7, byteArrayOf(0x18, 0x00))
    fun status6(): ByteArray = frame(T_STATUS6, byteArrayOf(0x08, 0x00))

    // --- разбор входящих ---

    /** StartResponse (type1): f1=ip(строка), f2=port(varint), f3=status. */
    fun parseStartResponse(payload: ByteArray): Wifi {
        val f = readFields(payload)
        val ip = f[1]?.let { String(it, Charsets.US_ASCII) } ?: error("StartResponse без ip")
        val port = f[2]?.let { varintOf(it) } ?: error("StartResponse без port")
        return Wifi(ip, port.toInt())
    }

    /** InfoResponse (type3): f1=ssid, f2=psk, f3=bssid, f4=security. */
    fun parseInfoResponse(payload: ByteArray): Creds {
        val f = readFields(payload)
        val ssid = f[1]?.let { String(it, Charsets.US_ASCII) } ?: error("InfoResponse без ssid")
        val psk = f[2]?.let { String(it, Charsets.US_ASCII) } ?: ""
        val bssid = f[3]?.let { String(it, Charsets.US_ASCII) } ?: ""
        val sec = f[4]?.let { varintOf(it).toInt() } ?: 0
        return Creds(ssid, psk, bssid, sec)
    }

    /**
     * Прогнать рукопожатие поверх открытого RFCOMM-стрима. Возвращает Wi-Fi и учётку бокса; после этого
     * бокс открывает TCP-сервер данных. Бросает при обрыве/мусоре. [send] шлёт статусы в конце.
     */
    fun run(input: InputStream, output: OutputStream, deviceId: String): Outcome {
        val din = DataInputStream(input)
        output.write(startRequest(deviceId)); output.flush()
        var wifi: Wifi? = null
        var creds: Creds? = null
        var infoAsked = false
        // Бокс ведёт: VersionRequest -> StartResponse -> InfoResponse. Читаем, отвечаем по месту.
        while (wifi == null || creds == null) {
            val (type, payload) = readFrame(din) ?: error("рукопожатие: обрыв до конца")
            when (type) {
                T_START_RESPONSE -> {
                    wifi = parseStartResponse(payload)
                    if (!infoAsked) { output.write(infoRequest()); output.flush(); infoAsked = true }
                }
                T_INFO_RESPONSE -> creds = parseInfoResponse(payload)
                T_VERSION_REQUEST -> { /* бокс знает freq сам; явный ответ не требуется */ }
                else -> { /* прочие — игнор */ }
            }
        }
        output.write(status7()); output.write(status6()); output.flush()
        return Outcome(wifi, creds)
    }

    // --- минимальный protobuf-разбор ---

    /** Поля wire 2 (строки/байты) и 0 (varint) по номеру. Для нашего рукопожатия хватает. */
    private fun readFields(b: ByteArray): Map<Int, ByteArray> {
        val out = HashMap<Int, ByteArray>()
        var i = 0
        while (i < b.size) {
            val tag = b[i].toInt() and 0xff; i++
            val field = tag ushr 3
            when (tag and 7) {
                0 -> { val s = i; while (i < b.size && (b[i].toInt() and 0x80) != 0) i++; i++; out[field] = b.copyOfRange(s, i) }
                2 -> { val len = b[i].toInt() and 0xff; i++; out[field] = b.copyOfRange(i, i + len); i += len }
                else -> error("неожиданный wire-type ${tag and 7}")
            }
        }
        return out
    }

    private fun varintOf(b: ByteArray): Long {
        var v = 0L; var shift = 0
        for (x in b) { v = v or ((x.toLong() and 0x7f) shl shift); if ((x.toInt() and 0x80) == 0) break; shift += 7 }
        return v
    }
}
