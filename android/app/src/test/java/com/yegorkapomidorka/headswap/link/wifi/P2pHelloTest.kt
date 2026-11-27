package com.yegorkapomidorka.headswap.link.wifi

import com.yegorkapomidorka.headswap.link.diag.WLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections

class P2pHelloTest {
    private val wl = Collections.synchronizedList(ArrayList<String>())

    @Before
    fun setUp() {
        WLog.clock = { System.currentTimeMillis() }
        WLog.sink = { wl += it }
    }

    private fun v4(s: String) = InetAddress.getByName(s) as Inet4Address

    /** Формат как у FT (CarLifeService.helloForCar): ГУ ищет carlifehost и deviceInfo. */
    @Test
    fun helloJson() {
        val s = String(BeaconCodec.hello("OnePlus 12", "Android_ab12"), Charsets.UTF_8)
        assertEquals("""{"carlifehost":"carlife","deviceInfo":{"bluetoothName":"OnePlus 12","wifiP2pName":"Android_ab12"}}""", s)
        assertTrue(BeaconCodec.isProbe(s.toByteArray(), s.length))
    }

    @Test
    fun helloEscapesAndNulls() {
        val s = String(BeaconCodec.hello("a\"b\\c\n", null), Charsets.UTF_8)
        assertEquals("""{"carlifehost":"carlife","deviceInfo":{"bluetoothName":"a\"b\\c\u000a","wifiP2pName":""}}""", s)
    }

    @Test
    fun ownerGuessIsDotOne() {
        assertEquals(v4("192.168.49.1"), P2pHello.guessOwner(LanIface("p2p0", v4("192.168.49.74"), 24, v4("192.168.49.255"))))
        assertEquals(v4("192.168.49.1"), P2pHello(LanIface("p2p0", v4("192.168.49.74"), 24, v4("192.168.49.255")), null, { ByteArray(0) }).owner)
    }

    /** Маяк уходит владельцу на 7999, привет на 8999; ответ ГУ на сокет привета считается. */
    @Test
    fun sendsBeaconAndHelloAndCountsReplies() {
        val beaconSock = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).apply { soTimeout = 3000 }
        val helloSock = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).apply { soTimeout = 3000 }
        val lo = LanIface("lo", v4("127.0.0.1"), 32, v4("127.0.0.1"))
        val h = P2pHello(lo, v4("127.0.0.1"), { BeaconCodec.hello("bt", "p2p") }, intervalMs = 200,
            beaconPort = beaconSock.localPort, helloPort = helloSock.localPort)
        try {
            h.start()
            val buf = ByteArray(1500)
            val b = DatagramPacket(buf, buf.size)
            beaconSock.receive(b)
            assertEquals("CarlifeHost", String(b.data, 0, b.length, Charsets.US_ASCII))
            val p = DatagramPacket(ByteArray(1500), 1500)
            helloSock.receive(p)
            assertTrue(String(p.data, 0, p.length, Charsets.UTF_8).contains("\"wifiP2pName\":\"p2p\""))
            helloSock.send(DatagramPacket("ok".toByteArray(), 2, p.socketAddress))
            val until = System.currentTimeMillis() + 3000
            while (h.replies == 0L && System.currentTimeMillis() < until) Thread.sleep(20)
            assertEquals(1L, h.replies)
            assertTrue(wl.any { it.contains("hello-reply") })
        } finally {
            h.stop("тест")
            beaconSock.close()
            helloSock.close()
        }
    }
}
