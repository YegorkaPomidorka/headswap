// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DexVideoGeometryTest {
    // Real baseline SPS vectors also used by the existing SpsCrop parser tests.
    private val native720 = hex("6742801fda014016e9a8283028368509a8")
    private val encoded1080 = hex("6742c028d900780227e5c044000003000400000300f03c60c920")
    private val pps = hex("68ce3c80")
    private val idr = hex("6580")
    private val sc = byteArrayOf(0, 0, 0, 1)
    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun real720SpsConfirmsNativeSourceAndIgnoresPps() {
        val geometry = DexVideoGeometry()
        geometry.acceptSession(1280, 720)
        val dimensions = geometry.acceptConfig(sc + native720 + sc + pps).single()
        assertEquals(1280, dimensions.codedW)
        assertEquals(720, dimensions.visibleH)
        assertTrue(geometry.beforeFrame(sc + idr).isEmpty())
    }

    @Test(expected = IOException::class)
    fun rejects1080SessionEvenBeforeVideo() {
        DexVideoGeometry().acceptSession(1920, 1080)
    }

    @Test(expected = IOException::class)
    fun rejects1080SpsBehindA720SessionHeader() {
        val geometry = DexVideoGeometry()
        geometry.acceptSession(1280, 720)
        geometry.acceptConfig(sc + encoded1080)
    }

    @Test fun acceptsSplitConfigAndThreeByteStartCodes() {
        val geometry = DexVideoGeometry()
        geometry.acceptSession(1280, 720)
        assertTrue(geometry.acceptConfig(sc + pps).isEmpty())
        assertEquals(1, geometry.acceptConfig(byteArrayOf(0, 0, 1) + native720).size)
        geometry.beforeFrame(sc + idr)
    }

    @Test(expected = IOException::class)
    fun ppsAloneCannotAuthorizeUnknownFrameGeometry() {
        val geometry = DexVideoGeometry()
        geometry.acceptSession(1280, 720)
        geometry.acceptConfig(sc + pps)
        geometry.beforeFrame(sc + idr)
    }

    @Test fun acceptsSpsInFirstIdrWhenCodecDoesNotSendSeparateConfig() {
        val geometry = DexVideoGeometry()
        geometry.acceptSession(1280, 720)
        assertEquals(1, geometry.beforeFrame(sc + native720 + sc + pps + sc + idr).size)
        assertTrue(geometry.beforeFrame(sc + idr).isEmpty())
    }

    @Test(expected = IOException::class)
    fun resetSessionMustVerifyItsNewSpsAgain() {
        val geometry = DexVideoGeometry()
        geometry.acceptSession(1280, 720)
        geometry.acceptConfig(sc + native720)
        geometry.acceptSession(1280, 720)
        geometry.beforeFrame(sc + idr)
    }

    @Test(expected = IOException::class)
    fun corruptSpsCannotBeTreatedAsVerified() {
        val geometry = DexVideoGeometry()
        geometry.acceptSession(1280, 720)
        geometry.acceptConfig(sc + byteArrayOf(0x67, 0))
    }

    @Test(expected = IOException::class)
    fun configBeforeSessionIsRejected() {
        DexVideoGeometry().acceptConfig(sc + native720)
    }
}
