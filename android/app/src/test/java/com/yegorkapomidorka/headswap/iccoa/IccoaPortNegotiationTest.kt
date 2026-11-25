// Derived from FT (github.com/OneAboveAll1964/ft-app), Apache-2.0: IccoaPortNegotiationTest.kt. Изменено для Headswap; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import com.yegorkapomidorka.headswap.PbReader
import com.yegorkapomidorka.headswap.PbWriter
import org.junit.Assert.*
import org.junit.Test

class IccoaPortNegotiationTest {
    @Test fun knownSensorRequestReturnsBoundPort() {
        val request = PbWriter().int32(1, 1).toByteArray()
        val parsed = PbReader.parse(IccoaPortNegotiation.responseFor(request)!!)!!
        assertEquals(1, PbReader.int(parsed, 1))
        assertEquals(57239, PbReader.int(parsed, 2))
    }
    @Test fun incompleteOrAmbiguousRequestsNeverReceivePorts() {
        val good = PbWriter().int32(1, 1).toByteArray()
        assertNull(IccoaPortNegotiation.responseFor(good + byteArrayOf(0x10)))
        assertNull(IccoaPortNegotiation.responseFor(good + good))
        assertNull(IccoaPortNegotiation.responseFor(good + byteArrayOf(0x0A, 0x00)))
        assertNull(IccoaPortNegotiation.responseFor(byteArrayOf(0x08)))
        assertNull(IccoaPortNegotiation.responseFor(byteArrayOf(0x08, 0x81.toByte())))
    }
    @Test fun unknownTypesAreNotAssignedUnverifiedPorts() {
        assertNull(IccoaPortNegotiation.responseFor(PbWriter().int32(1, 2).toByteArray()))
        assertNull(IccoaPortNegotiation.responseFor(PbWriter().int32(1, 3).toByteArray()))
        assertNull(IccoaPortNegotiation.responseFor(ByteArray(0)))
        assertNull(IccoaPortNegotiation.responseFor(PbWriter().int32(1, 1).bool(2, true).toByteArray()))
    }
}
