package com.yegorkapomidorka.headswap.iccoa

import com.yegorkapomidorka.headswap.PbReader
import com.yegorkapomidorka.headswap.PbWriter
import org.junit.Assert.*
import org.junit.Test

/** Расширения Proto.kt под ICCOA: int64-варинт, fixed64, double. */
class IccoaProtoTest {
    @Test fun int64Fixed64AndDoubleRoundTrip() {
        val bytes = PbWriter().int64(1, 1_700_000_000_123L).int64(2, -1).fixed64(3, 0x0102030405060708L)
            .double(4, 54.7104).int32(5, 7).toByteArray()
        val fields = PbReader.parse(bytes)!!
        assertEquals(1_700_000_000_123L, PbReader.long(fields, 1))
        assertEquals(-1L, PbReader.long(fields, 2))
        assertEquals(0x0102030405060708L, PbReader.fixed64(fields, 3))
        assertEquals(54.7104, PbReader.double(fields, 4)!!, 0.0)
        assertEquals(7, PbReader.int(fields, 5))
        assertNull(PbReader.double(fields, 1))
        assertNull(PbReader.long(fields, 3))
    }

    @Test fun wireBytesMatchProtobufEncoding() {
        // GPS из ucar.proto: f2 lat double = 1.0 → тег 0x11 и 8 байт little-endian.
        assertArrayEquals(byteArrayOf(0x11, 0, 0, 0, 0, 0, 0, 0xf0.toByte(), 0x3f),
            PbWriter().double(2, 1.0).toByteArray())
        assertArrayEquals(byteArrayOf(0x08, 0xac.toByte(), 0x02), PbWriter().int64(1, 300).toByteArray())
    }
}
