package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateSourceTest {
    @Test
    fun flagOffNeverListens() {
        for (sdk in listOf(30, 31, 36)) for (granted in listOf(true, false)) {
            assertFalse("sdk=$sdk granted=$granted", CallStateSource.shouldListen(false, sdk, granted))
        }
    }

    @Test
    fun flagOnNeedsPermissionFromApi31() {
        assertTrue(CallStateSource.shouldListen(true, 31, true))
        assertFalse(CallStateSource.shouldListen(true, 31, false))
        assertFalse(CallStateSource.shouldListen(true, 36, false))
    }

    @Test
    fun flagOnBelowApi31NeedsNoPermission() {
        assertTrue(CallStateSource.shouldListen(true, 30, false))
        assertTrue(CallStateSource.shouldListen(true, 26, true))
    }
}
