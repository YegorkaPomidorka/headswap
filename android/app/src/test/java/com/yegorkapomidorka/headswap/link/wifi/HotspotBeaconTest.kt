package com.yegorkapomidorka.headswap.link.wifi

import com.yegorkapomidorka.headswap.link.diag.WLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections

class HotspotBeaconTest {
    private val wl = Collections.synchronizedList(ArrayList<String>())

    @Before
    fun setUp() {
        WLog.clock = { System.currentTimeMillis() }
        WLog.sink = { wl += it }
    }

    private fun v4(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test
    fun subnetMembership() {
        val ap = LanIface("wlan2", v4("192.168.43.1"), 24, v4("192.168.43.255"))
        assertTrue(ap.contains(v4("192.168.43.17")))
        assertFalse(ap.contains(v4("192.168.44.17")))
        assertFalse(ap.contains(v4("10.0.0.5")))
        val wide = LanIface("ap0", v4("10.20.30.40"), 16, v4("10.20.255.255"))
        assertTrue(wide.contains(v4("10.20.1.2")))
        assertFalse(wide.contains(v4("10.21.1.2")))
    }

    @Test
    fun hostsOfSubnet() {
        val ap = LanIface("wlan2", v4("192.168.43.1"), 24, v4("192.168.43.255"))
        val h = ap.hosts()
        assertEquals("254 узла минус свой", 253, h.size)
        assertFalse(h.contains(v4("192.168.43.1")))
        assertFalse(h.contains(v4("192.168.43.0")))
        assertFalse(h.contains(v4("192.168.43.255")))
        assertTrue(h.contains(v4("192.168.43.254")))
        assertEquals("/29: 6 узлов минус свой", 5, LanIface("x", v4("10.0.0.1"), 29, v4("10.0.0.7")).hosts().size)
        assertTrue("/16 не свипаем", LanIface("ap0", v4("10.20.30.40"), 16, v4("10.20.255.255")).hosts().isEmpty())
    }

    @Test
    fun interfaceNames() {
        for (n in listOf("wlan0", "wlan1", "wlan2", "ap0", "swlan0", "eth0")) assertTrue(n, LanIface.eligibleName(n))
        for (n in listOf("rmnet_data0", "ccmni1", "lo", "dummy0", "tun0", "p2p-wlan0-0", "v4-rmnet_data0")) assertFalse(n, LanIface.eligibleName(n))
        assertFalse(LanIface.sweepName("wlan0"))
        assertTrue(LanIface.sweepName("wlan2"))
    }

    @Test
    fun hotspotNames() {
        // h420: Xiaomi/Qualcomm держит хотспот на wlan1; wlan0 - всегда STA
        for (n in listOf("swlan0", "ap0")) assertTrue(n, HotspotDetect.apIface(n, emptySet()))
        for (n in listOf("swlan0", "ap0")) assertTrue("$n без данных о STA", HotspotDetect.apIface(n, null))
        assertTrue(HotspotDetect.apIface("wlan1", emptySet()))
        assertTrue(HotspotDetect.apIface("wlan2", setOf("wlan0")))
        assertFalse("wlan1 - второй STA (сеть машины)", HotspotDetect.apIface("wlan1", setOf("wlan0", "wlan1")))
        assertFalse("STA неизвестны: wlan1 не трогаем", HotspotDetect.apIface("wlan1", null))
        assertFalse(HotspotDetect.apIface("wlan0", emptySet()))
        for (n in listOf("rmnet_data0", "p2p-wlan0-0", "lo", "eth0", "wlan")) assertFalse(n, HotspotDetect.apIface(n, emptySet()))
    }

    @Test
    fun codec() {
        assertArrayEquals("CarlifeHost".toByteArray(), BeaconCodec.BEACON)
        val probe = """{"carlifehost":"carlife","deviceInfo":{}}""".toByteArray()
        assertTrue(BeaconCodec.isProbe(probe, probe.size))
        assertFalse("регистр важен", BeaconCodec.isProbe("CarlifeHost".toByteArray(), 11))
        assertFalse(BeaconCodec.isProbe(probe, 5))
    }

    @Test
    fun beaconAndResponderOnLoopback() {
        val lo = LanIface("wlan9", v4("127.0.0.1"), 30, v4("127.0.0.1"))   // broadcast = loopback: маяк приходит сюда
        DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { hu ->
            hu.soTimeout = 3000
            val b = HotspotBeacon(ifaces = { listOf(lo) }, intervalMs = 100, sweepMs = 60_000, beaconPort = hu.localPort, probePort = 0)
            b.start()
            try {
                val buf = ByteArray(64)
                val p = DatagramPacket(buf, buf.size)
                hu.receive(p)
                assertEquals("CarlifeHost", String(buf, 0, p.length))
                // опрос 8999 -> JSON готовности обратно отправителю
                var port = -1
                for (i in 0 until 50) {
                    port = b.probePortBound
                    if (port > 0) break
                    Thread.sleep(20)
                }
                val q = """{"carlifehost":"carlife"}""".toByteArray()
                hu.send(DatagramPacket(q, q.size, InetAddress.getLoopbackAddress(), port))
                while (true) {
                    val r = DatagramPacket(buf, buf.size)
                    hu.receive(r)
                    val s = String(buf, 0, r.length)
                    if (s == "CarlifeHost") continue   // маяк продолжает идти
                    assertEquals(String(BeaconCodec.READY), s)
                    break
                }
                assertEquals(listOf(lo), b.current)
            } finally {
                b.stop("test")
            }
            assertTrue(b.sent > 0)
            assertEquals(1L, b.probes)
            assertTrue(wl.any { it.startsWith("WL udp beacon-first iface=\"wlan9 127.0.0.1/30\"") })
            assertTrue(wl.any { it.startsWith("WL udp beacon-stop why=test") })
        }
    }
}
