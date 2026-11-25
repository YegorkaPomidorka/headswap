// Derived from FT (github.com/OneAboveAll1964/ft-app), Apache-2.0: IccoaCarConfigTest.kt. Изменено для Headswap; см. NOTICE.
package com.yegorkapomidorka.headswap.iccoa

import com.yegorkapomidorka.headswap.PbWriter
import org.junit.Assert.*
import org.junit.Test

class IccoaCarConfigTest {
    @Test fun decodesReferenceCapabilitiesWithoutGuessingBluetoothFields() {
        val config = PbWriter().int32(18, 769).int32(19, 1).int32(20, 0)
            .int32(21, 1).int32(22, 1).int32(23, 0).int32(24, 160)
            .string(1, "private-car-name").string(7, "11:22:33:44:55:66").toByteArray()
        val description = IccoaCarConfig.describe(config)
        assertTrue(description.contains("workModes=769 (normal cast, mirror window, launcher window)"))
        assertTrue(description.contains("poi=1; rotation=0; home=1; back=1; hevc=0; dpi=160"))
        assertTrue(description.contains("1:bytes(16)"))
        assertTrue(description.contains("7:bytes(17)"))
        assertFalse(description.contains("private-car-name"))
        assertFalse(description.contains("11:22:33:44:55:66"))
        assertFalse(description.contains("bluetooth"))
    }

    @Test fun absentCapabilitiesStayUnknownAndUnknownBitsArePreserved() {
        assertEquals("shape=", IccoaCarConfig.describe(byteArrayOf()))
        val description = IccoaCarConfig.describe(PbWriter().int32(18, 2049).toByteArray())
        assertTrue(description.contains("normal cast, unknown 0x800"))
        assertFalse(description.contains("dpi="))
    }

    @Test fun rejectsMalformedAndOversizedConfigWithoutDumpingPayload() {
        assertThrows(IllegalArgumentException::class.java) { IccoaCarConfig.describe(byteArrayOf(0x12, 4, 1)) }
        assertThrows(IllegalArgumentException::class.java) { IccoaCarConfig.describe(ByteArray(16_385)) }
        assertThrows(IllegalArgumentException::class.java) {
            IccoaCarConfig.describe(PbWriter().string(18, "not-a-number").toByteArray())
        }
    }
}
