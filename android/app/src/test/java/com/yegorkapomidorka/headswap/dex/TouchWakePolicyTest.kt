package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchWakePolicyTest {
    @Test fun wakesOnDownWhenAsleepUpToA15() {
        assertTrue(TouchWakePolicy.shouldWake(33, isDown = true, interactive = false, manufacturer = "Xiaomi"))
        assertTrue(TouchWakePolicy.shouldWake(35, isDown = true, interactive = false, manufacturer = "Xiaomi"))
    }

    @Test fun noWakeOnA16() =
        assertFalse(TouchWakePolicy.shouldWake(36, isDown = true, interactive = false, manufacturer = "Xiaomi"))

    @Test fun noWakeWhenAwakeOrUnknown() {
        assertFalse(TouchWakePolicy.shouldWake(33, isDown = true, interactive = true, manufacturer = "Xiaomi"))
        assertFalse(TouchWakePolicy.shouldWake(33, isDown = true, interactive = null, manufacturer = "Xiaomi"))
    }

    @Test fun onlyOnDown() =
        assertFalse(TouchWakePolicy.shouldWake(33, isDown = false, interactive = false, manufacturer = "Xiaomi"))

    /** h1052: S21U A15 пропускает ввод во сне, будить не надо. */
    @Test fun noWakeOnSamsung() {
        assertFalse(TouchWakePolicy.shouldWake(35, isDown = true, interactive = false, manufacturer = "samsung"))
        assertFalse(TouchWakePolicy.shouldWake(33, isDown = true, interactive = false, manufacturer = "Samsung"))
    }

    @Test fun unknownManufacturerStillWakes() =
        assertTrue(TouchWakePolicy.shouldWake(35, isDown = true, interactive = false, manufacturer = null))
}
