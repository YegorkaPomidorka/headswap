package com.yegorkapomidorka.headswap.iccoa

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/** Чистая часть подъёма по BLE: отбор машины в эфире, CLIENT_INFO, сборка SERVER_INFO. */
class IccoaBleInfoTest {
    private val token = ByteArray(16) { (it + 1).toByte() }

    private fun collect(vararg fragments: String): IccoaWifiInfo? {
        val info = IccoaServerInfo()
        var result: IccoaWifiInfo? = null
        for (f in fragments) result = info.add(f.toByteArray())
        return result
    }

    private fun code(block: () -> Unit): String = try {
        block()
        "no error"
    } catch (e: IccoaBleError) {
        e.code
    }

    @Test fun hintByServiceServiceDataOrName() {
        assertTrue(IccoaBleInfo.hint(null, listOf("2ABCC850-9935-4F8A-BA84-123456789100")))
        assertTrue(IccoaBleInfo.hint("", listOf("0000fcfb-0000-1000-8000-00805f9b34fb")))
        assertTrue(IccoaBleInfo.hint("Toyota CarLink", emptyList()))
        assertTrue(IccoaBleInfo.hint("my-ICCOA-hu", emptyList()))
        assertFalse(IccoaBleInfo.hint("JBL Flip", listOf("0000180f-0000-1000-8000-00805f9b34fb")))
        assertFalse(IccoaBleInfo.hint(null, emptyList()))
    }

    @Test fun tokenNeedsSixteenNonZeroBytes() {
        assertEquals("0102030405060708090a0b0c0d0e0f10", IccoaBleInfo.token(mapOf(IccoaBleInfo.TOKEN to token)))
        assertNull(IccoaBleInfo.token(mapOf(IccoaBleInfo.TOKEN to ByteArray(16))))
        assertNull(IccoaBleInfo.token(mapOf(IccoaBleInfo.TOKEN to ByteArray(15) { 1 })))
        assertNull(IccoaBleInfo.token(mapOf(IccoaBleInfo.TOKEN to ByteArray(17) { 1 })))
        assertNull(IccoaBleInfo.token(mapOf("00000001-0000-1000-8000-00805f9b34fb" to token)))
    }

    @Test fun clientInfoNormalCarriesPinAndFixedProfile() {
        val bytes = IccoaBleInfo.clientInfo("a1b2c3", "Pixel 8", "02:11:22:33:44:55", 6, "123456")
        assertTrue(bytes.size <= IccoaBleInfo.MAX_CLIENT_INFO)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("a1b2c3", json.getString("id"))
        assertEquals("Pixel 8", json.getString("model"))
        assertEquals("Headswap", json.getString("name"))
        assertEquals("123456", json.getString("pinCodeOrAuthentication"))
        assertEquals(1, json.getInt("band"))
        assertEquals("02:11:22:33:44:55", json.getString("mac"))
        assertEquals(1002, json.getInt("type"))
        assertEquals("Xiaomi", json.getString("manufactures"))
        assertEquals(6, json.getInt("channel"))
        assertEquals(9, json.length())
    }

    @Test fun clientInfoQuickHasNoPin() {
        val text = IccoaBleInfo.clientInfo("a1b2c3", "Pixel 8", "02:11:22:33:44:55", 1, null).toString(Charsets.UTF_8)
        val json = JSONObject(text)
        assertFalse(json.has("pinCodeOrAuthentication"))
        assertEquals(8, json.length())
        assertEquals(1002, json.getInt("type"))
    }

    @Test fun clientInfoRefusesBadPinAndOversize() {
        for (pin in listOf("", "12345", "1234567", "12345a", "١٢٣٤٥٦")) {
            assertThrows(IllegalArgumentException::class.java) { IccoaBleInfo.clientInfo("a1b2c3", "m", "02:11:22:33:44:55", 1, pin) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            IccoaBleInfo.clientInfo("a1b2c3", "m".repeat(600), "02:11:22:33:44:55", 1, "123456")
        }
    }

    @Test fun macIsLocalUnicastAndChannelFollowsFrequency() {
        val random = Random(7)
        repeat(50) { assertTrue(IccoaBleInfo.validMac(IccoaBleInfo.newMac(random))) }
        assertFalse(IccoaBleInfo.validMac("00:11:22:33:44:55"))     // глобальный
        assertFalse(IccoaBleInfo.validMac("03:11:22:33:44:55"))     // групповой
        assertFalse(IccoaBleInfo.validMac("02:11:22:33:44"))
        assertEquals(1, IccoaBleInfo.wifiChannel(2412))
        assertEquals(11, IccoaBleInfo.wifiChannel(2462))
        assertEquals(14, IccoaBleInfo.wifiChannel(2484))
        assertEquals(36, IccoaBleInfo.wifiChannel(5180))
        assertEquals(149, IccoaBleInfo.wifiChannel(5745))
        assertEquals(1, IccoaBleInfo.wifiChannel(-1))
        assertEquals("x.x.x.23", IccoaBleInfo.mask("192.168.43.23"))
        assertNull(IccoaBleInfo.mask(null))
    }

    @Test fun serverInfoInOnePiece() {
        val info = collect("""{"ssid":"CarAP","psk":"secret-psk","mac":"AA:BB:CC:DD:EE:FF","type":1002}""")!!
        assertEquals("CarAP", info.ssid)
        assertEquals("secret-psk", info.psk)
        assertEquals("aa:bb:cc:dd:ee:ff", info.bssid)
    }

    @Test fun serverInfoFromFragmentsWithOptionalMac() {
        val collector = IccoaServerInfo()
        assertNull(collector.add("""{"ssid":"Car""".toByteArray()))
        assertNull(collector.add("""AP","psk":"secret""".toByteArray()))
        val info = collector.add("""-psk","type":1002}""".toByteArray() + "\r\n".toByteArray())!!
        assertEquals("CarAP" to "secret-psk", info.ssid to info.psk)
        assertNull(info.bssid)
        assertEquals(49, collector.bytes)
        assertEquals(1002, collector.type)
        // мусор вместо BSSID точку доступа не сужает, а не роняет вход
        assertNull(collect("""{"ssid":"a","psk":"12345678","mac":"none","type":1002}""")!!.bssid)
    }

    @Test fun fragmentStartingWithBraceRestartsMessage() {
        val info = collect("""{"ssid":"old","psk":"lost""", """{"ssid":"new",""", """"psk":"12345678","type":1002}""")!!
        assertEquals("new", info.ssid)
    }

    @Test fun serverInfoLimitIs4096Bytes() {
        val collector = IccoaServerInfo()
        assertNull(collector.add("{".toByteArray() + ByteArray(4095) { 'a'.code.toByte() }))
        assertEquals("server-info-too-big", code { collector.add("a".toByteArray()) })
        // после отказа сборщик пуст и принимает новое сообщение
        assertNotNull(collector.add("""{"ssid":"a","psk":"12345678","type":1002}""".toByteArray()))
    }

    @Test fun serverInfoWithoutPskOrSsidIsRefused() {
        assertEquals("server-info-fields", code { collect("""{"ssid":"CarAP","type":1002}""") })
        assertEquals("server-info-fields", code { collect("""{"psk":"12345678","type":1002}""") })
    }

    @Test fun wifiDirectOfferIsUnsupported() {
        assertEquals("p2p-unsupported", code { collect("""{"ssid":"DIRECT-x","psk":"12345678","type":1001}""") })
        // без type машина имеет в виду 1001
        assertEquals("p2p-unsupported", code { collect("""{"ssid":"DIRECT-x","psk":"12345678"}""") })
    }

    @Test fun secretsStayOutOfTextAndErrors() {
        val info = collect("""{"ssid":"CarAP","psk":"secret-psk","mac":"aa:bb:cc:dd:ee:ff","type":1002}""")!!
        for (secret in listOf("CarAP", "secret-psk", "aa:bb")) assertFalse(secret in info.toString())
        val error = try {
            collect("""{"ssid":"CarAP","psk":"secret-psk",,}""")
            null
        } catch (e: IccoaBleError) {
            e
        }!!
        assertEquals("server-info-json", error.code)
        assertNull(error.cause)
        for (secret in listOf("CarAP", "secret-psk")) assertFalse(secret in error.toString() + error.message)
    }
}
