package com.yegorkapomidorka.headswap.link.box

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Векторы — из живого захвата рукопожатия бокса (sdDongle_4.log, 27.11). */
class BoxHandshakeTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // то, что слал телефон (Google AA), и что слал бокс
    private val startReqFrame = hex("0010 0005 0800 1000 1A08 3835326638623932 2000")
    private val startRespFrame = hex("0014 0001 0A0D 3139322E3136382E312E313031 10 CD34 18 01")
    private val infoRespFrame = hex("0034 0003 0A13 57 69 72 65 6C 65 73 73 44 6F 6E 67 6C 65 2D 35 34 45 32 12 08 3132333435363738 1A11 63 63 3A 63 34 3A 62 32 3A 61 39 3A 38 30 3A 35 34 20 08")
    private val versionReqFrame = hex("0005 0004 18 00 20 BC 28")

    @Test fun startRequestMatchesCapture() {
        assertArrayEquals(startReqFrame, BoxHandshake.startRequest("852f8b92"))
    }

    @Test fun parsesStartResponse() {
        val w = BoxHandshake.parseStartResponse(startRespFrame.copyOfRange(4, startRespFrame.size))
        assertEquals("192.168.1.101", w.ip)
        assertEquals(6733, w.port)
    }

    @Test fun parsesInfoResponse() {
        val c = BoxHandshake.parseInfoResponse(infoRespFrame.copyOfRange(4, infoRespFrame.size))
        assertEquals("WirelessDongle-54E2", c.ssid)
        assertEquals("12345678", c.psk)
        assertEquals("cc:c4:b2:a9:80:54", c.bssid)
        assertEquals(8, c.security)
    }

    @Test fun fullHandshakeLearnsWifiAndSendsExpected() {
        // бокс шлёт: VersionRequest -> StartResponse -> InfoResponse
        val boxOut = versionReqFrame + startRespFrame + infoRespFrame
        val phoneOut = ByteArrayOutputStream()
        val outcome = BoxHandshake.run(ByteArrayInputStream(boxOut), phoneOut, "852f8b92")

        assertEquals("192.168.1.101", outcome.wifi.ip)
        assertEquals(6733, outcome.wifi.port)
        assertEquals("WirelessDongle-54E2", outcome.creds.ssid)
        assertEquals("12345678", outcome.creds.psk)

        // телефон должен прислать: StartRequest, InfoRequest, status7, status6
        val expected = startReqFrame +
            BoxHandshake.infoRequest() +
            BoxHandshake.status7() +
            BoxHandshake.status6()
        assertArrayEquals(expected, phoneOut.toByteArray())
    }
}
