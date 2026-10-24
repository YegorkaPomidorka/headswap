package com.yegorkapomidorka.headswap.link.bt

import com.yegorkapomidorka.headswap.PbReader
import com.yegorkapomidorka.headswap.PbWriter
import com.yegorkapomidorka.headswap.Wire

/**
 * Кадры Bluetooth-канала беспроводного CarLife (Baidu MD 8.6.7, CommonParams, BluetoothConnect.readData):
 * [длина тела u16 BE][резерв u16 = 0][тип u32 BE] + protobuf. Длина без заголовка.
 */
object BtMsg {
    const val MD_WIRELESS_INFO_REQUEST = 0x00100001
    const val HU_WIRELESS_INFO = 0x00108002
    const val MD_TARGET_INFO_REQUEST = 0x00100004
    const val HU_TARGET_INFO = 0x00108005
    const val HU_IP_REQUEST = 0x00108006
    const val MD_WIFI_IP = 0x00100007
    const val MD_STATUS = 0x00100008
    const val HU_STATUS = 0x00108009

    const val HDR = 8
    const val MAX_BODY = 4096

    fun name(type: Int): String = when (type) {
        MD_WIRELESS_INFO_REQUEST -> "WIRELESS_INFO_REQUEST"
        HU_WIRELESS_INFO -> "WIRELESS_INFO"
        MD_TARGET_INFO_REQUEST -> "TARGET_INFO_REQUEST"
        HU_TARGET_INFO -> "TARGET_INFO"
        HU_IP_REQUEST -> "IP_REQUEST"
        MD_WIFI_IP -> "WIFI_IP"
        MD_STATUS -> "MD_STATUS"
        HU_STATUS -> "HU_STATUS"
        else -> "0x%08X".format(type)
    }

    /** wirlessType из INFO_RESPONSE: 2..4 запускают беспроводную ветку у Baidu, 1 (WIFI) нет. */
    fun wirelessTypeName(t: Int?): String = when (t) {
        null -> "-"
        1 -> "1(wifi)"
        2 -> "2(p2p)"
        3 -> "3"
        4 -> "4"
        else -> t.toString()
    }

    fun frame(type: Int, body: ByteArray? = null): ByteArray = Wire.cmdInner(type, body)

    /**
     * Тело MD_WIFI_IP: адрес телефона в P2P-группе ГУ строкой в поле 1. Формат не подтверждён исходником MD
     * (у Baidu 8.6.7 мы его не читали, LifeAuto собирает тело в нативной части): по первому отчёту с машины сверить.
     */
    fun wifiIp(ip: String): ByteArray = PbWriter().string(1, ip).toByteArray()
}

/** Реквизиты P2P-группы ГУ из TARGET_INFO: имя устройства Wi-Fi Direct и p2pNetInfo «ssid,пароль[,частота]». */
class P2pTarget(val deviceName: String?, val ssid: String?, val passphrase: String?, val freqMhz: Int?) {
    /** Можно ли войти в группу напрямую (WifiP2pConfig.Builder): ssid DIRECT-xy и пароль 8..63 символа. */
    val canJoinBySsid: Boolean
        get() = ssid != null && ssid.startsWith("DIRECT-") && ssid.length >= 9 && passphrase != null && passphrase.length in 8..63

    /** Те же реквизиты: ГУ ответила на наш TARGET_INFO_REQUEST тем же, что уже прислала. */
    fun sameAs(o: P2pTarget): Boolean =
        deviceName == o.deviceName && ssid == o.ssid && passphrase == o.passphrase && freqMhz == o.freqMhz

    companion object {
        fun parse(body: ByteArray): P2pTarget? {
            val f = PbReader.parse(body) ?: return null
            val net = PbReader.str(f, 4)?.split(',')
            return P2pTarget(
                deviceName = PbReader.str(f, 1)?.takeIf { it.isNotEmpty() },
                ssid = net?.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() },
                passphrase = net?.getOrNull(1)?.takeIf { it.isNotEmpty() },
                freqMhz = net?.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it in 2400..5900 },
            )
        }
    }
}

class BtFrame(val type: Int, val body: ByteArray)

/** Сборка кадров из кусков RFCOMM-потока. Мусор в заголовке (резерв != 0, длина > MAX_BODY): сдвиг на байт. */
class BtFrameParser {
    private var buf = ByteArray(0)

    /** Сколько байт выброшено при ресинхронизации (для лога). */
    var skipped = 0L
        private set

    fun feed(chunk: ByteArray, n: Int = chunk.size): List<BtFrame> {
        buf += chunk.copyOf(n)
        val out = ArrayList<BtFrame>()
        var p = 0
        while (buf.size - p >= BtMsg.HDR) {
            val len = Wire.u16(buf, p)
            if (len > BtMsg.MAX_BODY || Wire.u16(buf, p + 2) != 0) {
                p++
                skipped++
                continue
            }
            if (buf.size - p < BtMsg.HDR + len) break
            out += BtFrame(Wire.u32(buf, p + 4), buf.copyOfRange(p + BtMsg.HDR, p + BtMsg.HDR + len))
            p += BtMsg.HDR + len
        }
        buf = buf.copyOfRange(p, buf.size)
        return out
    }
}

/**
 * Сводки ответов ГУ для WL-лога без секретов: имя сети и пароль из p2pNetInfo не выводятся, только число частей
 * и частота; scanRecord (зашифрованные реквизиты бокса) - только длина.
 */
object BtSummary {
    fun wirelessInfo(body: ByteArray): List<Pair<String, Any?>> {
        val f = PbReader.parse(body) ?: return listOf("parse" to "fail", "len" to body.size)
        return listOf("wirelessType" to BtMsg.wirelessTypeName(PbReader.int(f, 1)), "wifiFreq" to PbReader.int(f, 2))
    }

    fun targetInfo(body: ByteArray): List<Pair<String, Any?>> {
        val f = PbReader.parse(body) ?: return listOf("parse" to "fail", "len" to body.size)
        val name = PbReader.str(f, 1)
        val target = PbReader.str(f, 2)
        val scan = PbReader.all(f, 3).firstOrNull()?.data
        val net = PbReader.str(f, 4)
        return listOf(
            "p2pNameLen" to (name?.length ?: -1),
            "p2pNameDirect" to (name?.startsWith("DIRECT-") ?: false),
            "targetInfoLen" to (target?.length ?: -1),
            "scanRecordLen" to (scan?.size ?: -1),
        ) + netInfo(net)
    }

    /** p2pNetInfo = «ssid,пароль[,частота МГц]»: наличие, число частей, частота. */
    fun netInfo(net: String?): List<Pair<String, Any?>> {
        if (net == null) return listOf("p2pNetInfo" to "нет")
        val parts = net.split(',')
        val freq = parts.getOrNull(2)?.trim()?.toIntOrNull()
        return listOf("p2pNetInfo" to "есть", "netParts" to parts.size, "netFreq" to freq)
    }

    /** Состав тела без значений строк: номер поля, тип, число или длина (формат IP_REQUEST и ответов ГУ не подтверждён). */
    fun shape(body: ByteArray): String {
        val f = PbReader.parse(body) ?: return "parse-fail/${body.size}"
        if (f.isEmpty()) return "empty"
        return f.joinToString(",") { if (it.data == null) "${it.number}=${it.num}" else "${it.number}:len${it.data.size}" }
    }

    fun status(body: ByteArray): List<Pair<String, Any?>> {
        val f = PbReader.parse(body) ?: return listOf("parse" to "fail", "len" to body.size)
        return listOf("status" to PbReader.int(f, 1))
    }
}
