package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchWakePolicyTest {
    @Test fun wakesOnDownWhenAsleepUpToA15() {
        assertTrue(TouchWakePolicy.shouldWake(33, isDown = true, interactive = false))
        assertTrue(TouchWakePolicy.shouldWake(35, isDown = true, interactive = false))
    }

    @Test fun noWakeOnA16() = assertFalse(TouchWakePolicy.shouldWake(36, isDown = true, interactive = false))

    @Test fun noWakeWhenAwakeOrUnknown() {
        assertFalse(TouchWakePolicy.shouldWake(33, isDown = true, interactive = true))
        assertFalse(TouchWakePolicy.shouldWake(33, isDown = true, interactive = null))
    }

    @Test fun onlyOnDown() = assertFalse(TouchWakePolicy.shouldWake(33, isDown = false, interactive = false))
}
